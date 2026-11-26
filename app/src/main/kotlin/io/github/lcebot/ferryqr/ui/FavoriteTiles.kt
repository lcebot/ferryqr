@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.dispatch.Cards
import io.github.lcebot.ferryqr.history.HistoryEntry
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A tile is laid out as a history row is, with the list item's own padding, so it stands as tall
 * as the rows below it: its icon, then its name over its kind.
 */
private val TILE_ICON = 40.dp

/** The list item's own space between its leading content and its text. */
private val TILE_ICON_GAP = 12.dp

/**
 * What a tile adds beside its text: the list item's padding at both ends, the icon and the space
 * after it, with a pixel to spare so rounding never cuts the text short.
 */
internal fun tileChrome(layoutDirection: LayoutDirection): Dp {
    val padding = ListItemDefaults.ContentPadding
    return padding.calculateStartPadding(layoutDirection) + TILE_ICON + TILE_ICON_GAP +
        padding.calculateEndPadding(layoutDirection) + 1.dp
}

/** Columns of the grid are at least this wide, which makes four across a phone held upright. */
internal val GRID_COLUMN = 80.dp

/** How far past the edge of the favorites a swiped tile flies, beyond the list's margin and the system bars. */
private val FLY_MARGIN = 48.dp

/** The group the tiles form for a swipe. */
private const val TILES_GROUP = "favorites:tiles"

/**
 * The share of the finger's travel the tiles beside a pulled tile lean by: a little more than the
 * rows next to a pulled row, so that the tiles three steps away still visibly move.
 */
private const val NEAR_TILE_SHARE = 0.21f

/** How much more weakly a tile is pulled across an edge above or below it than across one beside it. */
private const val VERTICAL_SHARE = 0.6f

/** How much more weakly a tile one step further from a pulled tile leans than the nearer one. */
private const val NEIGHBOUR_DECAY = 0.5f

/** How many steps from a pulled tile the tiles around it lean. */
private const val NEIGHBOUR_STEPS = 3

/**
 * Where a tile goes, with [x] measured from the start edge of the favorites, so the same
 * arrangement mirrors for right-to-left text. [first] and [last] mark the ends of its row. How far
 * down its row is depends on how tall a tile comes out, which only laying one out tells.
 */
internal class TileSpot(
    val key: String,
    val x: Int,
    val width: Int,
    val row: Int,
    val first: Boolean,
    val last: Boolean,
)

/** What a finger over the tiles is over, and where among the other tiles the held one goes for it. */
internal class Hover(val over: String, val at: Int)

internal class TileArrangement(
    val spots: List<TileSpot>,
    val rows: Int,
    private val rowEnds: IntArray,
    val gap: Int,
) {
    val byKey: Map<String, TileSpot> = spots.associateBy { it.key }

    fun inRow(row: Int): List<TileSpot> = spots.filter { it.row == row }

    fun rectOf(spot: TileSpot, heights: RowHeights): Rect {
        val top = heights.top(spot.row).toFloat()
        return Rect(spot.x.toFloat(), top, (spot.x + spot.width).toFloat(), top + heights.height(spot.row))
    }

    /**
     * The favorites read as one block, rounded at the corners of its outline and tight between its
     * tiles, as a segmented list is between its rows. A row shorter than the one above or below
     * leaves the end of the longer one as a corner of the outline too.
     */
    fun cornersOf(spot: TileSpot, rowsShown: Int): TileCorners {
        val lastRow = rowsShown - 1
        val end = spot.x + spot.width
        fun shorter(row: Int) = row < 0 || row > lastRow || row >= rowEnds.size || rowEnds[row] < end - 1
        return TileCorners(
            topStart = spot.first && spot.row == 0,
            topEnd = spot.last && shorter(spot.row - 1),
            bottomStart = spot.first && spot.row == lastRow,
            bottomEnd = spot.last && shorter(spot.row + 1),
        )
    }

    /**
     * What the finger at [finger] is over among the first [rowsShown] rows while the tile [key] is
     * held, and where among the other tiles the held one goes for it, or null over its own place. A
     * tile held over another takes that tile's place, as an icon does on a launcher's home screen,
     * and the tiles between move aside toward the place it left; held past the end of a row, it
     * follows the row's last tile. Each tile counts the half of the gaps beside it as its own.
     */
    fun hover(key: String, keys: List<String>, finger: Offset, rowsShown: Int, heights: RowHeights): Hover? {
        val shown = minOf(rows, rowsShown)
        if (shown <= 0 || key !in keys) return null
        val row = heights.rowAt(finger.y, shown)
        val inRow = inRow(row)
        if (inRow.isEmpty()) return null
        val over = inRow.firstOrNull { finger.x < it.x + it.width + gap / 2f }
        val others = keys.filter { it != key }
        return when {
            over == null -> inRow.last().takeIf { it.key != key }?.let { Hover("end:$row", others.indexOf(it.key) + 1) }
            over.key == key -> null
            else -> Hover(over.key, keys.indexOf(over.key))
        }
    }
}

/**
 * How tall each row of tiles is, as the tiles in it came out, and how far down each starts with
 * [gap] between them. A row not yet measured, as one a drag has just opened, takes the tallest.
 */
internal class RowHeights(private val heights: IntArray, private val gap: Int) {
    private val fallback = heights.maxOrNull() ?: 0
    private val tops = IntArray(heights.size).also { tops ->
        for (row in 1 until heights.size) tops[row] = tops[row - 1] + heights[row - 1] + gap
    }

    fun height(row: Int): Int = heights.getOrElse(row) { fallback }

    fun top(row: Int): Int = when {
        row <= 0 -> 0
        row < tops.size -> tops[row]
        tops.isEmpty() -> row * (fallback + gap)
        else -> tops.last() + heights.last() + gap + (row - tops.size) * (fallback + gap)
    }

    /** How tall the first [rows] rows stand together. */
    fun total(rows: Int): Int = if (rows <= 0) 0 else top(rows - 1) + height(rows - 1)

    /** The row among the first [rows] that [y] falls in, the gap below a row counting as its own. */
    fun rowAt(y: Float, rows: Int): Int {
        for (row in 0 until rows - 1) if (y < top(row + 1)) return row
        return (rows - 1).coerceAtLeast(0)
    }
}

internal class TileCorners(val topStart: Boolean, val topEnd: Boolean, val bottomStart: Boolean, val bottomEnd: Boolean)

/**
 * Lays out tiles of the widths [widthOf] gives, in rows [available] wide. Without [columns], each
 * row takes as many tiles as fit, and every row but the last shares what it has left over evenly
 * among its tiles, so it is filled; the last keeps its tiles at their own widths, as the last line
 * of justified text does, rather than stretching a few tiles across the whole width. With
 * [columns], each tile spans as many columns as its width needs, so tiles line up in columns, and
 * a row ends where the next tile no longer fits.
 */
internal fun arrangeTiles(
    keys: List<String>,
    widthOf: (String) -> Int,
    available: Int,
    gap: Int,
    columns: Int?,
): TileArrangement {
    if (available <= 0 || keys.isEmpty()) return TileArrangement(emptyList(), 0, IntArray(0), gap)
    val spots = ArrayList<TileSpot>(keys.size)
    val rowEnds = ArrayList<Int>()
    fun addRow(row: List<Pair<String, IntRange>>) {
        val index = rowEnds.size
        row.forEachIndexed { i, (key, span) ->
            spots += TileSpot(key, span.first, span.last - span.first, index, i == 0, i == row.lastIndex)
        }
        rowEnds += row.last().second.last
    }
    var row = mutableListOf<Pair<String, IntRange>>()
    if (columns == null) {
        val rows = mutableListOf<MutableList<Pair<String, Int>>>()
        var used = 0
        keys.forEach { key ->
            val width = widthOf(key).coerceIn(1, available)
            val last = rows.lastOrNull()
            if (last != null && used + gap + width <= available) {
                last += key to width
                used += gap + width
            } else {
                rows += mutableListOf(key to width)
                used = width
            }
        }
        rows.forEachIndexed { index, tiles ->
            val spare = if (index == rows.lastIndex) 0 else available - tiles.sumOf { it.second } - gap * (tiles.size - 1)
            var x = 0
            addRow(
                tiles.mapIndexed { i, (key, width) ->
                    // The pixels that do not divide evenly go to the first tiles, one each.
                    val share = spare / tiles.size + if (i < spare % tiles.size) 1 else 0
                    val start = x
                    x += width + share + gap
                    key to (start..start + width + share)
                },
            )
        }
    } else {
        val pitch = (available + gap).toFloat() / columns
        fun edge(column: Int) = (column * pitch).roundToInt()
        var column = 0
        keys.forEach { key ->
            val span = ceil((widthOf(key) + gap) / pitch).toInt().coerceIn(1, columns)
            if (column + span > columns) {
                addRow(row)
                row = mutableListOf()
                column = 0
            }
            row += key to (edge(column)..edge(column + span) - gap)
            column += span
        }
        if (row.isNotEmpty()) addRow(row)
    }
    return TileArrangement(spots, rowEnds.size, rowEnds.toIntArray(), gap)
}

/** How many grid columns fit across [available], with at least two. */
internal fun gridColumns(available: Int, gap: Int, column: Int): Int = max(2, (available + gap) / (column + gap))

/**
 * How the tiles stand to the pulled tile [pulled], a notification's swipe carried over to two
 * dimensions. Tiles that share an edge are neighbours: those beside each other in a row, and those
 * in rows next to each other that overlap. The tiles next to the pulled one are a step from it,
 * their neighbours two, and so on to [NEIGHBOUR_STEPS]. A tile leans by [NEAR_TILE_SHARE], less by
 * [VERTICAL_SHARE] for each edge above or below crossed on the way to it, and by [NEIGHBOUR_DECAY]
 * for each step past the first, along whichever way pulls it most. What the swipe shows behind the
 * pulled tile leans as a tile beside it does.
 *
 * Tiles in a row lean the same way, so a tile ahead of another in the way the finger goes, leaning
 * less, would close the gap between them and run into it. Along each row in the way the finger
 * goes, a tile therefore leans at least as far as the one behind it: the tiles behind the nearest
 * fall away from it, and those ahead of it move with it, so the gaps in a row only ever widen.
 *
 * Only the corners of the tiles next to the pulled one that touch it round, as a notification's
 * do where it meets the one pulled, and they stay rounded once it is loose. Only the first
 * [rowsShown] rows take part.
 */
internal fun tileNeighbours(pulled: String, arrangement: TileArrangement, rowsShown: Int): Map<String, Relation> {
    val origin = arrangement.byKey[pulled] ?: return emptyMap()
    val rows = arrangement.spots.filter { it.row < rowsShown }.groupBy { it.row }
    val edges = HashMap<String, MutableList<Pair<TileSpot, Float>>>()
    fun link(a: TileSpot, b: TileSpot, pull: Float) {
        edges.getOrPut(a.key) { mutableListOf() } += b to pull
        edges.getOrPut(b.key) { mutableListOf() } += a to pull
    }
    rows.forEach { (row, tiles) ->
        tiles.zipWithNext { a, b -> link(a, b, 1f) }
        rows[row + 1]?.forEach { below ->
            tiles.forEach { above ->
                if (above.x < below.x + below.width && below.x < above.x + above.width) link(above, below, VERTICAL_SHARE)
            }
        }
    }
    val strength = HashMap<String, Float>()
    val steps = HashMap<String, Int>()
    var reached = mapOf(pulled to 1f)
    for (step in 1..NEIGHBOUR_STEPS) {
        val next = HashMap<String, Float>()
        reached.forEach { (from, pull) ->
            edges[from]?.forEach { (tile, across) ->
                if (tile.key == pulled) return@forEach
                val value = pull * across * if (step == 1) 1f else NEIGHBOUR_DECAY
                steps.putIfAbsent(tile.key, step)
                if (value > (strength[tile.key] ?: 0f)) {
                    strength[tile.key] = value
                    next[tile.key] = value
                }
            }
        }
        reached = next
    }
    // What the swipe shows in the pulled tile's place counts in its row as a tile beside it.
    fun share(tile: TileSpot) = if (tile.key == pulled) NEAR_TILE_SHARE else NEAR_TILE_SHARE * (strength[tile.key] ?: 0f)
    val towardEnd = HashMap<String, Float>()
    val towardStart = HashMap<String, Float>()
    rows.values.forEach { tiles ->
        var most = 0f
        tiles.forEach { tile ->
            most = maxOf(most, share(tile))
            towardEnd[tile.key] = most
        }
        most = 0f
        tiles.asReversed().forEach { tile ->
            most = maxOf(most, share(tile))
            towardStart[tile.key] = most
        }
    }
    val slack = arrangement.gap
    val keys = (towardEnd.keys + towardStart.keys).filter { it != pulled && ((towardEnd[it] ?: 0f) > 0f || (towardStart[it] ?: 0f) > 0f) }
    return keys.associateWith { key ->
        val tile = arrangement.byKey.getValue(key)
        val touching = if (steps[key] == 1) {
            val end = tile.x + tile.width
            fun along(x: Int) = x >= origin.x - slack && x <= origin.x + origin.width + slack
            when {
                tile.row == origin.row -> if (tile.x < origin.x) Corners.END else Corners.START
                tile.row < origin.row ->
                    (if (along(tile.x)) Corners.BOTTOM_START else 0) or (if (along(end)) Corners.BOTTOM_END else 0)
                else -> (if (along(tile.x)) Corners.TOP_START else 0) or (if (along(end)) Corners.TOP_END else 0)
            }
        } else {
            0
        }
        Relation(towardEnd[key] ?: 0f, towardStart[key] ?: 0f, NEIGHBOUR_ROUNDNESS[0], touching, touching)
    }
}

/**
 * The favorites as tiles, their order while one is dragged, and the tile in hand. The fields from
 * [keys] on are measured afresh each time the history is composed, so a drag between compositions
 * works on what is on screen.
 */
@Stable
internal class TileBoard(private val haptics: Haptics) {
    /** The favorites' contents in the order the tiles show them while one is in hand. */
    val order = mutableStateListOf<String>()

    /** Every favorite shown and not yet faded out, so one that leaves can fade where it was. */
    val retained = mutableStateMapOf<String, HistoryEntry>()

    /** Where each tile was last placed, where one that leaves fades out. */
    val lastRects = HashMap<String, Rect>()

    /**
     * Tiles deleted while their deletion can still be undone. One swiped away stays, out of sight
     * where it flew to, until then, so undoing flies it back into its place.
     */
    val undoable = HashSet<String>()

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

    var keys: List<String> = emptyList()
    var widths: Map<String, Int> = emptyMap()
    var available = 0
    var gap = 0
    var columns: Int? = null
    var rowsShown = 0

    /**
     * How tall each row of tiles comes out as the list item lays its tiles out, the same as a
     * history row with as many lines, measured each time the tiles are laid out.
     */
    var heights = RowHeights(IntArray(0), 0)

    fun arrange(keys: List<String> = this.keys) = arrangeTiles(keys, { widths[it] ?: 0 }, available, gap, columns)

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
        order.clear()
        order.addAll(keys)
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
        order.clear()
        order.addAll(next)
        // Until the history is composed again, the next move must already see the new order.
        keys = next
        haptics.step()
    }

    /**
     * Lets go of the tile in hand. One only held stays where it is; one dragged glides into its
     * place on [spec] once [onReorder] has kept the new order.
     */
    fun drop(scope: CoroutineScope, spec: FiniteAnimationSpec<Rect>, onReorder: () -> Unit) {
        val tile = key ?: return
        lifted = false
        lastOver = null
        if (!moved) {
            key = null
            return
        }
        onReorder()
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
        undoable.remove(tile)
        retained.remove(tile)
        lastRects.remove(tile)
    }
}

/**
 * How far a tile has come into view: its opacity changes on [effects] and its scale on [spatial],
 * as Material motion separates what changes how a thing looks from what changes its size.
 */
private class TileShow(visible: Boolean) {
    val alpha = Animatable(if (visible) 1f else 0f)
    val scale = Animatable(if (visible) 1f else HIDDEN_SCALE)

    suspend fun show(visible: Boolean, effects: FiniteAnimationSpec<Float>, spatial: FiniteAnimationSpec<Float>) = coroutineScope {
        launch { alpha.animateTo(if (visible) 1f else 0f, effects) }
        launch { scale.animateTo(if (visible) 1f else HIDDEN_SCALE, spatial) }
    }

    suspend fun snap(visible: Boolean) {
        alpha.snapTo(if (visible) 1f else 0f)
        scale.snapTo(if (visible) 1f else HIDDEN_SCALE)
    }
}

/**
 * Where a tile in its place is drawn, and how wide, while it moves to a new place, whose width can
 * differ as rows share out what they have left. The tiles are laid out by hand, so each follows its
 * place from where it is drawn, as a lazy list's items do, rather than from where a separate pass
 * of the layout might put it.
 */
private class TileMotion(at: Rect) {
    val rect: Animatable<Rect, AnimationVector4D> = Animatable(at, Rect.VectorConverter)
    var target = at

    /** Heads for [place] from wherever the tile is drawn, unless it is already heading there. */
    fun follow(place: Rect, scope: CoroutineScope, spec: FiniteAnimationSpec<Rect>) {
        if (place == target) return
        target = place
        scope.launch { rect.animateTo(place, spec) }
    }
}

/**
 * The favorites as tiles. A tap opens one, and a swipe deletes it or takes it out of the favorites
 * as a swipe on a row does, the tiles around it leaning with it. A long press lifts it and opens
 * its menu, unfolding the favorites first, and dragging it then closes the menu and moves it into
 * the place of the tile it is held over, [onReorder] keeping the order once it is let go. Tiles
 * slide into their new places, and come and go fading and growing or shrinking, on the theme's
 * motion.
 */
@Composable
internal fun FavoriteTiles(
    board: TileBoard,
    favorites: Map<String, HistoryEntry>,
    arrangement: TileArrangement,
    list: MagneticList,
    actions: EntryActions,
    onShift: (HistoryEntry, Int) -> Unit,
    onLift: () -> Unit,
    onReorder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val detachAt = with(density) { DETACH_AT.toPx() }
    val flyMargin = with(density) { FLY_MARGIN.toPx() }
    val motion = MaterialTheme.motionScheme
    val settleSpec = motion.fastSpatialSpec<Rect>()
    val moveSpec = motion.defaultSpatialSpec<Rect>()
    val heightSpec = motion.defaultSpatialSpec<IntSize>()
    val showSpec = motion.defaultEffectsSpec<Float>()
    val growSpec = motion.defaultSpatialSpec<Float>()
    val colors = MaterialTheme.colorScheme
    // Where the tile in hand will land, as a reorderable list marks it: a drop zone in its own
    // colour and in the outline of the tile in hand.
    val zoneColor = colors.surfaceContainerLow
    val zoneShape = ListItemDefaults.shapes().draggedShape
    val reorder by rememberUpdatedState(onReorder)
    val lift by rememberUpdatedState(onLift)
    val laidOut by rememberUpdatedState(arrangement)

    val present = board.keys.mapNotNull { favorites[it] }
    val presentKeys = board.keys.toSet()
    SideEffect {
        present.forEach { if (board.retained[it.text] != it) board.retained[it.text] = it }
    }
    val leaving = board.retained.values.filter { it.text !in presentKeys }
    val tiles = present.map { it to false } + leaving.map { it to true }
    // Tiles there from the start are simply there; those that come later grow and fade in.
    val initial = remember { presentKeys }
    val shown = remember { HashMap<String, TileShow>() }
    val motions = remember { HashMap<String, TileMotion>() }
    val swipes = remember { HashMap<String, EntrySwipe>() }
    val ghost = remember { Animatable(0f) }
    // Kept apart from the state the layout reads, as the layout sets it.
    val ghostMotion = remember { arrayOfNulls<TileMotion>(1) }
    val ghostShown = board.key != null && board.lifted && board.moved
    LaunchedEffect(ghostShown) { ghost.animateTo(if (ghostShown) 1f else 0f, showSpec) }

    fun visible(tile: String, gone: Boolean): Boolean {
        if (gone) return false
        if (board.key == tile) return true
        val spot = arrangement.byKey[tile] ?: return false
        return spot.row < board.rowsShown
    }

    fun forgetTile(tile: String) {
        board.forget(tile)
        shown.remove(tile)
        swipes.remove(tile)
    }

    /** How far a tile swiped toward [side] travels to leave past the edge of the favorites. */
    fun flyDistance(tile: String, side: Float): Float {
        val spot = laidOut.byKey[tile] ?: return 0f
        val left = if (rtl) board.available - spot.x - spot.width else spot.x
        return (if (side > 0f) board.available - left else left + spot.width) + flyMargin
    }

    Layout(
        // The list moves the rows below along with the tiles as they fold and unfold, bringing rows
        // into view and out of it as a scroll would, rather than all at once.
        modifier = modifier.animateSize(heightSpec),
        content = {
            tiles.forEach { (entry, gone) ->
                key(entry.text) {
                    val tile = entry.text
                    val isVisible = visible(tile, gone)
                    val progress = shown.getOrPut(tile) { TileShow(isVisible && tile in initial) }
                    val magnet = rememberMagnet(list, detachAt) { pull -> board.relation(pull, tile, laidOut) }
                    val swipe = rememberEntrySwipe(
                        entry = entry,
                        group = TILES_GROUP,
                        index = 0,
                        list = list,
                        flyTo = { side -> flyDistance(tile, side) },
                        onDelete = actions.onDelete,
                        onFavorite = actions.onFavorite,
                        leavesOnFavorite = true,
                    )
                    swipes[tile] = swipe
                    LaunchedEffect(isVisible, gone) {
                        when {
                            // One swiped away has already left the screen. Deleted, it waits there
                            // while undoing can bring it back; taken out of the favorites, it goes.
                            gone && swipe.acting -> {
                                progress.snap(false)
                                if (tile !in board.undoable) forgetTile(tile)
                            }
                            // Brought back by undo, it flies back into its place from where it went.
                            !gone && swipe.acting -> {
                                progress.snap(true)
                                swipe.comeBack()
                            }
                            else -> {
                                progress.show(isVisible, showSpec, growSpec)
                                if (gone) forgetTile(tile)
                            }
                        }
                    }
                    val active by rememberUpdatedState(isVisible)
                    val dragged = board.key == tile
                    val spot = arrangement.byKey[tile]
                    val corners = if (spot != null && !gone) arrangement.cornersOf(spot, board.rowsShown) else ALL_INNER
                    val shape = tileShape(corners, magnet)
                    val index = board.keys.indexOf(tile)
                    var menu by remember { mutableStateOf(false) }
                    var touch by remember { mutableStateOf(Offset.Zero) }
                    // A long press that lifts the tile is not also a tap when the finger lifts
                    // without moving; each new touch starts unmarked.
                    val longPressed = remember { booleanArrayOf(false) }
                    Box(
                        Modifier
                            .graphicsLayer {
                                alpha = progress.alpha.value
                                scaleX = progress.scale.value
                                scaleY = progress.scale.value
                            }
                            .entrySwipe(swipe, enabled = isVisible && board.key == null)
                            .trackTouch {
                                touch = it
                                longPressed[0] = false
                            }
                            .pointerInput(tile, rtl) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        if (active) {
                                            longPressed[0] = true
                                            haptics.longPress()
                                            lift()
                                            val grab = if (rtl) Offset(size.width - offset.x, offset.y) else offset
                                            board.pickUp(tile, grab)
                                            menu = true
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        val wasMoved = board.moved
                                        board.drag(if (rtl) Offset(-amount.x, amount.y) else amount, viewConfiguration.touchSlop)
                                        if (!wasMoved && board.moved) menu = false
                                    },
                                    onDragEnd = { board.drop(scope, settleSpec) { reorder() } },
                                    onDragCancel = { board.drop(scope, settleSpec) { reorder() } },
                                )
                            },
                    ) {
                        // What the swipe shows behind the tile leans with the tiles beside it, so they do
                        // not slide in under it.
                        SwipeBackground(
                            swipe.direction,
                            swipe.armed,
                            swipe.becomesFavorite,
                            shape,
                            Modifier.matchParentSize().graphicsLayer { translationX = magnet.backdrop.value.value },
                        )
                        val moreLabel = stringResource(R.string.entry_more)
                        val earlier = stringResource(R.string.favorite_move_earlier)
                        val later = stringResource(R.string.favorite_move_later)
                        FavoriteTile(
                            entry = entry,
                            // Kept with the tile, so one fading out still shows its name.
                            title = remember(entry.label, entry.format) { tileTitle(context, entry) },
                            shape = shape,
                            lifted = dragged && board.lifted,
                            raised = swipe.pull != null || swipe.acting,
                            onClick = {
                                if (!longPressed[0] && active) actions.onOpen(entry)
                            },
                            // Without a finger to drag with, the order changes a tile at a time.
                            modifier = Modifier
                                .graphicsLayer { translationX = swipe.shift.value.value + magnet.lean.value.value }
                                .semantics {
                                    onLongClick(moreLabel) {
                                        menu = true
                                        true
                                    }
                                    customActions = listOfNotNull(
                                        CustomAccessibilityAction(earlier) { onShift(entry, -1); true }.takeIf { index > 0 },
                                        CustomAccessibilityAction(later) { onShift(entry, 1); true }
                                            .takeIf { index in 0 until board.keys.lastIndex },
                                    )
                                },
                        )
                        EntryMenu(entry = entry, at = touch, expanded = menu && !gone, onDismiss = { menu = false }, actions = actions)
                    }
                }
            }
            Box(Modifier.graphicsLayer { alpha = ghost.value }.background(zoneColor, zoneShape))
        },
    ) { all, constraints ->
        // The drop zone comes after the tiles.
        val measurables = all.subList(0, tiles.size)
        val zone = all[tiles.size]
        // Tiles in their places take the height the list item gives itself at the width they are
        // drawn at, as the rows below do, and each row is as tall as its tallest tile. Only a name
        // too long for a whole row wraps to a second line, and such a tile has its row to itself,
        // so a row never holds tiles of different heights. The tile in hand and those fading out
        // keep the size they were drawn at, and a row holding only the tile in hand keeps its height.
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val tallest = IntArray(arrangement.rows)
        // A spatial spring narrowing a tile into a row that no longer widens it overshoots past its
        // own width before it settles, which would wrap its text for a moment. A tile is never laid
        // out narrower than its own width, so the spring springs back only as far as that.
        fun atLeastOwn(tile: String, width: Float): Int =
            max(width.roundToInt(), minOf(board.widths[tile] ?: 0, board.available)).coerceAtLeast(0)
        measurables.forEachIndexed { i, measurable ->
            val (entry, gone) = tiles[i]
            if (gone || entry.text == board.key) return@forEachIndexed
            val spot = arrangement.byKey[entry.text] ?: return@forEachIndexed
            val width = atLeastOwn(entry.text, motions[entry.text]?.rect?.value?.width ?: spot.width.toFloat())
            placeables[i] = measurable.measure(Constraints(minWidth = width, maxWidth = width))
                .also { tallest[spot.row] = max(tallest[spot.row], it.height) }
        }
        board.key?.let { arrangement.byKey[it] }?.let { spot ->
            if (tallest[spot.row] == 0) tallest[spot.row] = board.size.height.roundToInt()
        }
        val heights = RowHeights(tallest, arrangement.gap)
        board.heights = heights
        // Where each tile is drawn: a tile in its place glides to a new one, in place and width,
        // from where it is drawn; the tile in hand and one fading out are drawn where they are, and
        // a tile set down starts afresh in its place.
        val placed = measurables.mapIndexed { i, measurable ->
            val (entry, gone) = tiles[i]
            val tile = entry.text
            val drawn = when {
                tile == board.key -> {
                    motions.remove(tile)
                    Rect(board.at, board.size)
                }
                gone -> {
                    motions.remove(tile)
                    board.lastRects[tile]
                }
                else -> arrangement.byKey[tile]?.let { spot ->
                    val place = arrangement.rectOf(spot, heights)
                    val moving = motions.getOrPut(tile) { TileMotion(place) }
                    moving.follow(place, scope, moveSpec)
                    moving.rect.value
                }
            } ?: return@mapIndexed null
            val placeable = placeables[i] ?: measurable.measure(
                Constraints.fixed(atLeastOwn(tile, drawn.width), drawn.height.roundToInt().coerceAtLeast(0)),
            )
            placeable to drawn.topLeft
        }
        // The drop zone marks where the tile in hand will land, as large as its place there, and
        // follows that place as the tiles do theirs; appearing, it starts in the place.
        val landing = board.key?.let { arrangement.byKey[it] }?.let { arrangement.rectOf(it, heights) }
        val zoneDrawn = landing?.let { place ->
            val moving = ghostMotion[0]?.takeIf { ghost.value > 0f } ?: TileMotion(place).also { ghostMotion[0] = it }
            moving.follow(place, scope, moveSpec)
            moving.rect.value
        }
        val zonePlaceable = zoneDrawn?.let {
            zone.measure(Constraints.fixed(it.width.roundToInt().coerceAtLeast(0), it.height.roundToInt().coerceAtLeast(0)))
        }
        layout(constraints.maxWidth, heights.total(board.rowsShown)) {
            if (zoneDrawn != null && zonePlaceable != null && ghost.value > 0f) {
                zonePlaceable.placeRelative(zoneDrawn.left.roundToInt(), zoneDrawn.top.roundToInt())
            }
            placed.forEachIndexed { i, item ->
                val (placeable, at) = item ?: return@forEachIndexed
                val (entry, gone) = tiles[i]
                val tile = entry.text
                // Once faded out, a hidden tile is not placed, so it takes no touches.
                if (!visible(tile, gone) && (shown[tile]?.alpha?.value ?: 0f) == 0f) return@forEachIndexed
                if (!gone) board.lastRects[tile] = Rect(at, Size(placeable.width.toFloat(), placeable.height.toFloat()))
                // The tile in hand and one being swiped, flying off or flying back are what the user
                // is doing, so they are drawn over every other tile.
                val swiped = swipes[tile]?.let { it.pull != null || it.acting } == true
                placeable.placeRelative(
                    at.x.roundToInt(),
                    at.y.roundToInt(),
                    zIndex = if (tile == board.key || swiped) 1f else 0f,
                )
            }
        }
    }
}

/** Level 4, the elevation a list item takes while dragged. */
private val SWIPED_ELEVATION = 8.dp

/** How small a tile is as it fades out or before it fades in. */
private const val HIDDEN_SCALE = 0.8f

private val ALL_INNER = TileCorners(topStart = false, topEnd = false, bottomStart = false, bottomEnd = false)

/**
 * One favorite as a tile: its icon, and its name over its kind, in the list item's own padding,
 * colours, press shape and dragged look. While [raised], swiped, it takes the dragged elevation. Lifted, the tile turns tertiary container itself and its
 * avatar blends into it, leaving the icon in the colour a reorderable list gives a dragged item's.
 */
@Composable
private fun FavoriteTile(
    entry: HistoryEntry,
    title: String,
    shape: Shape,
    lifted: Boolean,
    raised: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    var start by remember { mutableStateOf<DragInteraction.Start?>(null) }
    LaunchedEffect(lifted) {
        if (lifted && start == null) {
            start = DragInteraction.Start().also { interactions.emit(it) }
        } else if (!lifted) {
            start?.let { interactions.emit(DragInteraction.Stop(it)) }
            start = null
        }
    }
    val payload = remember(entry.text, entry.format) { Classifier.classify(entry.text, entry.format) }
    val kind = remember(payload) { Cards.kindOf(payload) }
    val colors = MaterialTheme.colorScheme
    ListItem(
        onClick = onClick,
        modifier = modifier,
        interactionSource = interactions,
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(TILE_ICON)
                    .clip(MaterialShapes.Cookie4Sided.toShape())
                    .background(colors.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(kind.icon), contentDescription = null, tint = colors.onTertiaryContainer, modifier = Modifier.size(20.dp))
            }
        },
        supportingContent = { Text(stringResource(kind.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.colors(containerColor = colors.surfaceContainer, contentColor = colors.onSurface),
        // A tile swiped across the tiles beside it is raised as a dragged list item is, so its
        // shadow keeps it apart from tiles of the same colour it passes over; the list item moves
        // between its elevations on its own spatial motion.
        elevation = ListItemDefaults.elevation(elevation = if (raised) SWIPED_ELEVATION else 0.dp),
    ) {
        // Two lines at most and broken as a history row's title is, which only a name too long
        // for a whole row comes to.
        val anywhere = remember(title) { breakAnywhere(title) }
        Text(
            anywhere ?: title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = if (anywhere != null) LocalTextStyle.current.copy(lineBreak = LineBreak.Simple) else LocalTextStyle.current,
        )
    }
}

/**
 * The segmented corners of a tile, following it as it becomes or stops being a corner of the
 * block on the fast spatial motion the list item morphs its own shape on, and rounding further
 * where [magnet] rounds them as a tile nearby is swiped.
 */
@Composable
private fun tileShape(corners: TileCorners, magnet: Magnet): Shape {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    fun size(outer: Boolean) = if (outer) OUTER_CORNER else INNER_CORNER
    val topStart by animateDpAsState(size(corners.topStart), spec, label = "tile-top-start")
    val topEnd by animateDpAsState(size(corners.topEnd), spec, label = "tile-top-end")
    val bottomEnd by animateDpAsState(size(corners.bottomEnd), spec, label = "tile-bottom-end")
    val bottomStart by animateDpAsState(size(corners.bottomStart), spec, label = "tile-bottom-start")
    fun rounded(base: Dp, round: Follower) = maxOf(base, lerp(INNER_CORNER, OUTER_CORNER, round.value.value.coerceIn(0f, 1f)))
    return RoundedCornerShape(
        topStart = rounded(topStart, magnet.topStart),
        topEnd = rounded(topEnd, magnet.topEnd),
        bottomEnd = rounded(bottomEnd, magnet.bottomEnd),
        bottomStart = rounded(bottomStart, magnet.bottomStart),
    )
}
