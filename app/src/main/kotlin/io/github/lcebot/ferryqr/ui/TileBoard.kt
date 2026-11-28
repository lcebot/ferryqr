package io.github.lcebot.ferryqr.ui

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.util.WeakHashMap

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
 * The favorites as tiles, as they move a tile in hand for a [FavoriteDragSession]. What the tiles
 * are laid out from comes from the history as it is composed, through [update], and the height of
 * their rows from the layout, through [measured], for the gestures alone, which act on what is on
 * screen between compositions.
 */
@Stable
internal class TileBoard : FavoriteDragLayout {
    /**
     * What the finger was last over, so the tile in hand moves once on coming over another tile
     * and not again while the finger stays over it, as the tiles move around beneath.
     */
    private var lastOver: String? = null

    private var inputs = TileInputs.NONE
    private var rowsShown = 0

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
    }

    /** Takes the heights of the rows as the tiles were last laid out. */
    fun measured(heights: RowHeights) {
        this.heights = heights
    }

    /** The tiles arranged in [order], which a drag moves on ahead of what the history last composed. */
    private fun arrange(order: List<String>): TileArrangement {
        arranged?.let { (from, keys, arrangement) -> if (from === inputs && keys == order) return arrangement }
        val keys = order.toList()
        return inputs.arrange(keys).also { arranged = Triple(inputs, keys, it) }
    }

    override fun place(key: String, order: List<String>): Rect? {
        val arrangement = arrange(order)
        return arrangement.byKey[key]?.let { arrangement.rectOf(it, heights) }
    }

    override fun pickedUp(key: String, fresh: Boolean) {
        lastOver = key
    }

    /** Moves the tile in hand into the place of the tile the finger is over, or past the end of a row it is held past. */
    override fun hover(key: String, at: Rect, grab: Offset, order: List<String>): HeldMove? {
        val hover = arrange(order).hover(key, order, at.topLeft + grab, rowsShown, heights)
        val over = hover?.over ?: key
        if (over == lastOver) return null
        lastOver = over
        return hover?.let { HeldMove(it.at) }
    }

    /** Where the tile in hand is, within the list item [TILES_KEY] that holds the tiles. */
    override fun reach(key: String, at: Rect, order: List<String>, info: LazyListLayoutInfo, bottom: Float): HeldReach? {
        val block = info.visibleItemsInfo.firstOrNull { it.key == TILES_KEY } ?: return null
        val top = block.offset + at.top
        return HeldReach(top, top + at.height, -block.offset.toFloat(), block.offset + block.size - bottom)
    }

    /**
     * The tiles around each pull, with the arrangement and the rows shown they were worked out
     * among. Held only as long as the pull itself, which the list lets go of once it settles.
     */
    private val neighbours = WeakHashMap<Pull, Triple<TileArrangement, Int, Map<String, Relation>>>()

    /**
     * How [tile] stands to [pull] among [arrangement], or null where it takes no part. The tiles
     * around each pull are worked out once for it, and looked up on every step of every finger.
     */
    fun relation(pull: Pull, tile: String, arrangement: TileArrangement): Relation? {
        if (pull.group != TILES_GROUP) return null
        // What the swipe shows behind the pulled tile leans with the tiles beside it, so they do not
        // slide in under it.
        if (pull.key == tile) return Relation(0f, 0f, 1f, Corners.ALL, Corners.ALL, backdrop = NEAR_TILE_SHARE)
        val worked = neighbours[pull]?.takeIf { (among, rows, _) -> among === arrangement && rows == rowsShown }
            ?: Triple(arrangement, rowsShown, tileNeighbours(pull.key as? String ?: return null, arrangement, rowsShown))
                .also { neighbours[pull] = it }
        return worked.third[tile]
    }
}
