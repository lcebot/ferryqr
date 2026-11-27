package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.github.lcebot.ferryqr.history.HistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What the tiles are laid out from: their contents in order, how wide each one's own content makes
 * it, the room across, the gap between tiles, and the columns of a grid, if they line up in one.
 */
internal data class TileInputs(
    val keys: List<String>,
    val widths: Map<String, Int>,
    val available: Int,
    val gap: Int,
    val columns: Int?,
) {
    fun arrange(keys: List<String> = this.keys): TileArrangement = arrangeTiles(keys, { widths[it] ?: 0 }, available, gap, columns)

    companion object {
        val NONE = TileInputs(emptyList(), emptyMap(), 0, 0, null)
    }
}

/**
 * The favorites as tiles, their order while one is dragged, and the tile in hand, which history
 * scrolls the list for as a [FavoritesReorder]. Screens read only its snapshot state. What the tiles
 * are laid out from comes from the history as it is composed, through [update], and the height of
 * their rows from the layout, through [measured], for the gestures alone, which act on what is on
 * screen between compositions. Every order the tiles are moved into is kept through [keeper].
 */
@Stable
internal class TileBoard(
    private val haptics: Haptics,
    keeper: FavoriteOrderKeeper,
) : FavoritesReorder, FavoriteOrderKeeper by keeper {
    /** The favorites' contents in the order the tiles show them while one is in hand. */
    val order = FavoriteOrder()

    /** Every favorite shown and not yet faded out, so one that leaves can fade where it was. */
    val retained = mutableStateMapOf<String, HistoryEntry>()

    /** Where each tile was last placed, where one that leaves fades out. */
    val lastRects = HashMap<String, Rect>()

    /** The tile in hand, or settling into its place once let go. */
    var key by mutableStateOf<String?>(null)
        private set

    /** Where the tile in hand is drawn, from the start edge of the favorites. */
    var at by mutableStateOf(Offset.Zero)
        private set
    var size by mutableStateOf(Size.Zero)
        private set
    var lifted by mutableStateOf(false)
        private set

    /** Whether the tile in hand has moved far enough to be dragged rather than only held. */
    var moved by mutableStateOf(false)
        private set
    private var grab = Offset.Zero
    private var travel = Offset.Zero
    private var settling: Job? = null

    /**
     * What the finger was last over, so the tile in hand moves once on coming over another tile
     * and not again while the finger stays over it, as the tiles move around beneath.
     */
    private var lastOver: String? = null

    private var inputs = TileInputs.NONE
    private var rowsShown = 0

    /** The order the gestures work on, which moves ahead of [inputs] until the history is composed again. */
    private var keys: List<String> = emptyList()

    /**
     * How tall each row of tiles comes out as the list item lays its tiles out, the same as a
     * history row with as many lines, measured each time the tiles are laid out.
     */
    private var heights = RowHeights(IntArray(0), 0)

    /** The arrangement last worked out and what it was worked out for, as every step of a drag asks again. */
    private var arranged: Triple<TileInputs, List<String>, TileArrangement>? = null

    /** Takes what the history last composed the tiles from, and how many of their rows it shows. */
    fun update(inputs: TileInputs, rowsShown: Int) {
        this.inputs = inputs
        this.rowsShown = rowsShown
        keys = inputs.keys
    }

    /** Takes the heights of the rows as the tiles were last laid out. */
    fun measured(heights: RowHeights) {
        this.heights = heights
    }

    private fun arrange(): TileArrangement {
        arranged?.let { (from, order, arrangement) -> if (from === inputs && order == keys) return arrangement }
        return inputs.arrange(keys).also { arranged = Triple(inputs, keys, it) }
    }

    override val held: Any? get() = key.takeIf { lifted && moved }

    /** Where the tile in hand is, within the list item [TILES_KEY] that holds the tiles. */
    override fun reach(info: LazyListLayoutInfo, bottom: Float): HeldReach? {
        val block = info.visibleItemsInfo.firstOrNull { it.key == TILES_KEY } ?: return null
        val top = block.offset + at.y
        return HeldReach(top, top + size.height, -block.offset.toFloat(), block.offset + block.size - bottom)
    }

    override fun onScrolled(distance: Float) = scrolled(distance)

    private var neighboursOf: Triple<Pull, TileArrangement, Int>? = null
    private var neighbours: Map<String, Relation> = emptyMap()

    /**
     * How [tile] stands to [pull] among [arrangement], or null where it takes no part. The tiles
     * around a pull are worked out once for it, and looked up on every step of the finger.
     */
    fun relation(pull: Pull, tile: String, arrangement: TileArrangement): Relation? {
        if (pull.group != TILES_GROUP) return null
        if (pull.key == tile) return Relation(0f, 0f, 1f, Corners.ALL, Corners.ALL, backdrop = NEAR_TILE_SHARE)
        val worked = neighboursOf
        if (worked == null || worked.first !== pull || worked.second !== arrangement || worked.third != rowsShown) {
            neighbours = tileNeighbours(pull.key as? String ?: return null, arrangement, rowsShown)
            neighboursOf = Triple(pull, arrangement, rowsShown)
        }
        return neighbours[tile]
    }

    /** Picks up the tile [tile], held at [grab] from its start and top edges. */
    fun pickUp(tile: String, grab: Offset) {
        settling?.cancel()
        settling = null
        val arrangement = arrange()
        val spot = arrangement.byKey[tile] ?: return
        order.reset(keys)
        key = tile
        val rect = arrangement.rectOf(spot, heights)
        at = rect.topLeft
        size = rect.size
        this.grab = grab
        travel = Offset.Zero
        lastOver = tile
        lifted = true
        moved = false
    }

    /** Moves the tile in hand by [delta], from the start edge; past [slop] it is being dragged. */
    fun drag(delta: Offset, slop: Float) {
        if (key == null || !lifted) return
        at += delta
        travel += delta
        if (!moved && travel.getDistance() > slop) moved = true
        if (moved) follow()
    }

    /** The list scrolled the favorites by [distance] under the finger, which stays where it is. */
    fun scrolled(distance: Float) {
        if (key == null || !lifted) return
        at += Offset(0f, distance)
        follow()
    }

    /** Moves the tile in hand into the place it is held over. */
    private fun follow() {
        val tile = key ?: return
        val finger = at + grab
        val hover = arrange().hover(tile, keys, finger, rowsShown, heights)
        val over = hover?.over ?: tile
        if (over == lastOver) return
        lastOver = over
        if (hover == null) return
        val next = keys.filter { it != tile }.toMutableList().apply { add(hover.at, tile) }
        if (next == keys) return
        order.reset(next)
        // Until the history is composed again, the next move must already see the new order.
        keys = next
        haptics.step()
    }

    /**
     * Moves [tile] [by] places in the order shown and keeps the new order at once, for screen
     * readers, which move a tile at a time with no drop to wait for.
     */
    fun shift(tile: String, by: Int) {
        val from = keys.indexOf(tile)
        val to = from + by
        if (from < 0 || to !in keys.indices || to == from) return
        val next = keys.toMutableList().apply { add(to, removeAt(from)) }
        // Until the history is composed again, a further move must already see the new order.
        keys = next
        haptics.step()
        commit(next)
    }

    /**
     * Lets go of the tile in hand. One only held stays where it is; one dragged has its new order
     * kept and glides into its place on [spec].
     */
    fun drop(scope: CoroutineScope, spec: FiniteAnimationSpec<Rect>) {
        val tile = key ?: return
        lifted = false
        lastOver = null
        if (!moved) {
            key = null
            return
        }
        commit(order.toList())
        val arrangement = arrange()
        val target = arrangement.byKey[tile]?.let { arrangement.rectOf(it, heights) }
        settling = scope.launch {
            if (target != null) {
                Animatable(Rect(at, size), Rect.VectorConverter).animateTo(target, spec) {
                    at = value.topLeft
                    size = value.size
                }
            }
            key = null
            moved = false
            settling = null
        }
    }

    /** Lets go of everything kept for a tile that has faded out. */
    fun forget(tile: String) {
        retained.remove(tile)
        lastRects.remove(tile)
    }
}
