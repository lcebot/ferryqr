@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.dispatch.Cards
import io.github.lcebot.ferryqr.history.HistoryEntry
import kotlin.math.abs
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

/**
 * A tile held with the finger this far above or below the favorites leaves them when let go. Held
 * beside them, past their side edges, it is deleted from the start side and leaves the favorites
 * from the end side, the same sides a swipe uses for the same actions.
 */
internal val TILE_OUT_MARGIN = 24.dp

/** How far past the edge of the favorites a swiped tile flies, beyond the list's margin and the system bars. */
private val FLY_MARGIN = 48.dp

/** The group the tiles form for a swipe. */
private const val TILES_GROUP = "favorites:tiles"

/** How much the tiles directly above and below a pulled tile lean, against those beside it in its row. */
private const val VERTICAL_SHARE = 0.6f

/**
 * How much a tile one step further from a pulled tile leans, against the one nearer: a third, as a
 * notification two rows away leans against one next to it.
 */
private const val INDIRECT_SHARE = 1f / 3

/** What letting go of a tile does. */
internal enum class DropZone { NONE, DELETE, UNFAVORITE }

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
 * Lays out tiles of the widths [widthOf] gives, in rows [available] wide, each tile keeping its
 * width wherever it goes. Without [columns], each row takes as many tiles as fit and ends after the
 * last. With [columns], each tile spans as many columns as its width needs, so tiles line up in
 * columns, and a row ends where the next tile no longer fits.
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
        var x = 0
        keys.forEach { key ->
            val width = widthOf(key).coerceIn(1, available)
            if (row.isNotEmpty() && x + width > available) {
                addRow(row)
                row = mutableListOf()
                x = 0
            }
            row += key to (x..x + width)
            x += width + gap
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
    }
    if (row.isNotEmpty()) addRow(row)
    return TileArrangement(spots, rowEnds.size, rowEnds.toIntArray(), gap)
}

/** How many grid columns fit across [available], with at least two. */
internal fun gridColumns(available: Int, gap: Int, column: Int): Int = max(2, (available + gap) / (column + gap))

/**
 * How the tile [key] stands to a pulled tile, a notification's swipe carried over to two
 * dimensions. The tiles next to it in its row lean most, those in the rows above and below that
 * overlap it [VERTICAL_SHARE] as much, and those one step further, the next but one in its row,
 * the nearest at its corners and those two rows away that overlap it, [INDIRECT_SHARE] as much
 * again. Each rounds on the sides facing it, and the nearest stay rounded there once it is loose.
 */
internal fun tileRelation(pull: Pull, key: String, arrangement: TileArrangement, rowsShown: Int): Relation? {
    if (pull.group != TILES_GROUP) return null
    if (pull.key == key) return Relation(0f, 1f, Sides.ALL, Sides.ALL)
    val pulled = arrangement.byKey[pull.key] ?: return null
    val tile = arrangement.byKey[key] ?: return null
    if (tile.row >= rowsShown) return null
    val rows = tile.row - pulled.row
    val across = when {
        tile.x + tile.width <= pulled.x -> Sides.END
        tile.x >= pulled.x + pulled.width -> Sides.START
        else -> 0
    }
    val upDown = when {
        rows < 0 -> Sides.BOTTOM
        rows > 0 -> Sides.TOP
        else -> 0
    }
    val overlaps = tile.x < pulled.x + pulled.width && pulled.x < tile.x + tile.width
    val near = NEAR_SHARE
    val vertical = NEAR_SHARE * VERTICAL_SHARE
    return when (abs(rows)) {
        0 -> {
            val inRow = arrangement.inRow(tile.row)
            when (abs(inRow.indexOf(tile) - inRow.indexOf(pulled))) {
                1 -> Relation(near, NEAR_ROUNDNESS, across, across)
                2 -> Relation(near * INDIRECT_SHARE, FAR_ROUNDNESS, across, 0)
                else -> null
            }
        }
        1 -> if (overlaps) {
            Relation(vertical, NEAR_ROUNDNESS, upDown, upDown)
        } else {
            val inRow = arrangement.inRow(tile.row)
            val corner = tile === inRow.lastOrNull { it.x + it.width <= pulled.x } ||
                tile === inRow.firstOrNull { it.x >= pulled.x + pulled.width }
            if (corner) Relation(vertical * INDIRECT_SHARE, FAR_ROUNDNESS, upDown or across, 0) else null
        }
        2 -> if (overlaps) Relation(vertical * INDIRECT_SHARE, FAR_ROUNDNESS, upDown, 0) else null
        else -> null
    }
}

/**
 * The favorites as tiles, their order while one is dragged, and the tile in hand. The fields from
 * [keys] on are measured afresh each time the history is composed, so a drag between compositions
 * works on what is on screen.
 */
@Stable
internal class TileBoard(private val haptics: Haptics, private val swipeHaptics: SwipeHaptics) {
    /** The favorites' contents in the order the tiles show them while one is in hand. */
    val order = mutableStateListOf<String>()

    /** Favorites let go of to leave, gone from the tiles before the history reports them gone. */
    val removing = mutableStateListOf<String>()

    /** Every favorite shown and not yet faded out, so one that leaves can fade where it was. */
    val retained = mutableStateMapOf<String, HistoryEntry>()

    /** Where each tile was last placed, where one that leaves fades out. */
    val lastRects = HashMap<String, Rect>()

    /** What a tile let go of to leave was doing, so it fades out looking as it did when let go. */
    val departed = HashMap<String, DropZone>()

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
    var zone by mutableStateOf(DropZone.NONE)
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
    var outMargin = 0f

    /**
     * How tall each row of tiles comes out as the list item lays its tiles out, the same as a
     * history row with as many lines, measured each time the tiles are laid out.
     */
    var heights = RowHeights(IntArray(0), 0)

    fun arrange(keys: List<String> = this.keys) = arrangeTiles(keys, { widths[it] ?: 0 }, available, gap, columns)

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
        zone = DropZone.NONE
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

    /**
     * Marks what letting go would do where the finger is, with a click on crossing into or out of
     * a place that removes the tile, and otherwise moves the tile into the place it is held over.
     */
    private fun follow() {
        val tile = key ?: return
        val finger = at + grab
        val now = when {
            finger.y < -outMargin || finger.y > heights.total(rowsShown) + outMargin -> DropZone.UNFAVORITE
            finger.x < 0f -> DropZone.DELETE
            finger.x > available -> DropZone.UNFAVORITE
            else -> DropZone.NONE
        }
        if (now != zone) {
            zone = now
            swipeHaptics.threshold()
        }
        if (now != DropZone.NONE) {
            lastOver = null
            return
        }
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
     * Lets go of the tile in hand. One only held stays where it is; one dragged to a place that
     * removes it fades out where it was let go while [onDrop] removes it; any other glides into its
     * place on [spec] once [onDrop] has kept its new order.
     */
    fun drop(scope: CoroutineScope, spec: FiniteAnimationSpec<Rect>, onDrop: (String, DropZone) -> Unit) {
        val tile = key ?: return
        lifted = false
        lastOver = null
        if (!moved) {
            key = null
            return
        }
        val dropped = zone
        zone = DropZone.NONE
        if (dropped != DropZone.NONE) {
            departed[tile] = dropped
            lastRects[tile] = Rect(at, size)
            removing.add(tile)
            key = null
            moved = false
            onDrop(tile, dropped)
            return
        }
        onDrop(tile, DropZone.NONE)
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
        departed.remove(tile)
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
 * Where a tile in its place is drawn while it moves to a new place. The tiles are laid out by hand,
 * so each follows its place from where it is drawn, as a lazy list's items do, rather than from
 * where a separate pass of the layout might put it.
 */
private class TileMotion(at: Offset) {
    val offset: Animatable<Offset, AnimationVector2D> = Animatable(at, Offset.VectorConverter)
    var target = at
}

/**
 * Reports the height of what it holds as it changes on [spec], while what it holds keeps its own
 * height and is drawn whole. The list then moves the rows below along with the tiles as they fold
 * and unfold, bringing rows into view and out of it as a scroll would, rather than all at once.
 */
private class HeightAnimation(var spec: FiniteAnimationSpec<Int>) : Modifier.Node(), LayoutModifierNode {
    private var height: Animatable<Int, AnimationVector1D>? = null
    private var target = 0

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val shown = height ?: Animatable(placeable.height, Int.VectorConverter).also {
            height = it
            target = placeable.height
        }
        if (placeable.height != target) {
            target = placeable.height
            coroutineScope.launch { shown.animateTo(target, spec) }
        }
        return layout(placeable.width, shown.value.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeable.place(0, 0)
        }
    }
}

private class HeightAnimationElement(val spec: FiniteAnimationSpec<Int>) : ModifierNodeElement<HeightAnimation>() {
    override fun create() = HeightAnimation(spec)

    override fun update(node: HeightAnimation) {
        node.spec = spec
    }

    override fun equals(other: Any?) = other is HeightAnimationElement && other.spec == spec

    override fun hashCode() = spec.hashCode()
}

/**
 * The favorites as tiles. A tap opens one, and a swipe deletes it or takes it out of the favorites
 * as a swipe on a row does, the tiles around it leaning with it. A long press lifts it and opens
 * its menu, unfolding the favorites first, and dragging it then closes the menu and moves it into
 * the place of the tile it is held over; let go beside or outside the favorites, it is deleted or
 * leaves them, as [onDrop] decides. Tiles slide into their new places, and come and go fading and
 * growing or shrinking, on the theme's motion.
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
    onDrop: (HistoryEntry, DropZone) -> Unit,
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
    val moveSpec = motion.defaultSpatialSpec<Offset>()
    val heightSpec = motion.defaultSpatialSpec<Int>()
    val showSpec = motion.defaultEffectsSpec<Float>()
    val growSpec = motion.defaultSpatialSpec<Float>()
    val colors = MaterialTheme.colorScheme
    // Where the tile in hand will land, as a reorderable list marks it: a drop zone in its own
    // colour and in the outline of the tile in hand.
    val zoneColor = colors.surfaceContainerLow
    val zoneShape = ListItemDefaults.shapes().draggedShape
    val drop by rememberUpdatedState(onDrop)
    val lift by rememberUpdatedState(onLift)
    val current by rememberUpdatedState(favorites)
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
    val ghostShown = board.key != null && board.lifted && board.moved && board.zone == DropZone.NONE
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
        modifier = modifier.then(HeightAnimationElement(heightSpec)),
        content = {
            tiles.forEach { (entry, gone) ->
                key(entry.text) {
                    val tile = entry.text
                    val isVisible = visible(tile, gone)
                    val progress = shown.getOrPut(tile) { TileShow(isVisible && tile in initial) }
                    val magnet = rememberMagnet(list, detachAt) { pull -> tileRelation(pull, tile, laidOut, board.rowsShown) }
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
                    val departedAs = if (gone) board.departed[tile] else null
                    val zone = if (dragged) board.zone else departedAs ?: DropZone.NONE
                    val spot = arrangement.byKey[tile]
                    val corners = if (spot != null && !gone) arrangement.cornersOf(spot, board.rowsShown) else ALL_INNER
                    val shape = tileShape(corners, magnet)
                    val index = board.keys.indexOf(tile)
                    var menu by remember { mutableStateOf(false) }
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
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    longPressed[0] = false
                                }
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
                                    onDragEnd = { board.drop(scope, settleSpec) { k, z -> current[k]?.let { drop(it, z) } } },
                                    onDragCancel = { board.drop(scope, settleSpec) { k, z -> current[k]?.let { drop(it, z) } } },
                                )
                            },
                    ) {
                        SwipeBackground(swipe.direction, swipe.armed, swipe.becomesFavorite, shape, Modifier.matchParentSize())
                        val moreLabel = stringResource(R.string.entry_more)
                        val earlier = stringResource(R.string.favorite_move_earlier)
                        val later = stringResource(R.string.favorite_move_later)
                        FavoriteTile(
                            entry = entry,
                            // Kept with the tile, so one fading out still shows its name.
                            title = remember(entry.label, entry.format) { tileTitle(context, entry) },
                            shape = shape,
                            lifted = (dragged && board.lifted) || departedAs != null,
                            zone = zone,
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
                        EntryMenu(entry = entry, expanded = menu && !gone, onDismiss = { menu = false }, actions = actions)
                    }
                }
            }
            Box(Modifier.graphicsLayer { alpha = ghost.value }.background(zoneColor, zoneShape))
        },
    ) { all, constraints ->
        // The drop zone comes after the tiles.
        val measurables = all.subList(0, tiles.size)
        val zone = all[tiles.size]
        // Tiles in their places take the height the list item gives itself, as the rows below do,
        // and each row is as tall as its tallest tile. Only a name too long for a whole row wraps to
        // a second line, and such a tile has its row to itself, so a row never holds tiles of
        // different heights. The tile in hand and those fading out keep the size they were drawn
        // at, and a row holding only the tile in hand keeps its height.
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val tallest = IntArray(arrangement.rows)
        measurables.forEachIndexed { i, measurable ->
            val (entry, gone) = tiles[i]
            if (gone || entry.text == board.key) return@forEachIndexed
            val spot = arrangement.byKey[entry.text] ?: return@forEachIndexed
            placeables[i] = measurable.measure(Constraints(minWidth = spot.width, maxWidth = spot.width))
                .also { tallest[spot.row] = max(tallest[spot.row], it.height) }
        }
        board.key?.let { arrangement.byKey[it] }?.let { spot ->
            if (tallest[spot.row] == 0) tallest[spot.row] = board.size.height.roundToInt()
        }
        val heights = RowHeights(tallest, arrangement.gap)
        board.heights = heights
        val placed = measurables.mapIndexed { i, measurable ->
            val (entry, gone) = tiles[i]
            val tile = entry.text
            val rect = when {
                tile == board.key -> Rect(board.at, board.size)
                gone -> board.lastRects[tile]
                else -> arrangement.byKey[tile]?.let { arrangement.rectOf(it, heights) }
            } ?: return@mapIndexed null
            val placeable = placeables[i] ?: measurable.measure(
                Constraints.fixed(rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0)),
            )
            placeable to rect
        }
        // The drop zone follows the place of the tile in hand as the other tiles do theirs.
        val held = board.key?.let { arrangement.byKey[it] }?.let { arrangement.rectOf(it, heights) }
        val zonePlaceable = held?.let {
            zone.measure(Constraints.fixed(board.size.width.roundToInt(), board.size.height.roundToInt()))
        }
        layout(constraints.maxWidth, heights.total(board.rowsShown)) {
            if (held != null && zonePlaceable != null) {
                // Appearing, it starts in the place; shown, it glides to the next one.
                val moving = ghostMotion[0]?.takeIf { ghost.value > 0f } ?: TileMotion(held.topLeft).also { ghostMotion[0] = it }
                if (moving.target != held.topLeft) {
                    moving.target = held.topLeft
                    scope.launch { moving.offset.animateTo(held.topLeft, moveSpec) }
                }
                if (ghost.value > 0f) zonePlaceable.placeRelative(moving.offset.value.x.roundToInt(), moving.offset.value.y.roundToInt())
            }
            placed.forEachIndexed { i, item ->
                val (placeable, rect) = item ?: return@forEachIndexed
                val (entry, gone) = tiles[i]
                val tile = entry.text
                // A tile in its place glides to a new one from where it is drawn; the tile in hand
                // and one fading out are drawn where they are, and a tile set down starts afresh.
                val at = if (tile == board.key || gone) {
                    motions.remove(tile)
                    rect.topLeft
                } else {
                    val moving = motions.getOrPut(tile) { TileMotion(rect.topLeft) }
                    if (moving.target != rect.topLeft) {
                        moving.target = rect.topLeft
                        scope.launch { moving.offset.animateTo(rect.topLeft, moveSpec) }
                    }
                    moving.offset.value
                }
                // Once faded out, a hidden tile is not placed, so it takes no touches.
                if (!visible(tile, gone) && (shown[tile]?.alpha?.value ?: 0f) == 0f) return@forEachIndexed
                if (!gone) board.lastRects[tile] = Rect(at, rect.size)
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

/** How small a tile is as it fades out or before it fades in. */
private const val HIDDEN_SCALE = 0.8f

private val ALL_INNER = TileCorners(topStart = false, topEnd = false, bottomStart = false, bottomEnd = false)

/**
 * One favorite as a tile: its icon, and its name over its kind, in the list item's own padding,
 * colours, press shape and dragged look. Held over a place that removes it, it takes the colours
 * of what letting go would do, as a swiped row shows behind it: the error colours to delete, and
 * the primary colours of other entries to leave the favorites.
 */
@Composable
private fun FavoriteTile(
    entry: HistoryEntry,
    title: String,
    shape: Shape,
    lifted: Boolean,
    zone: DropZone,
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
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val iconContainer by animateColorAsState(
        when {
            zone == DropZone.DELETE -> colors.error
            zone == DropZone.UNFAVORITE -> colors.primary
            // Lifted, the tile turns tertiary container itself and the avatar blends into it,
            // leaving its icon in the colour a reorderable list gives a dragged item's icon.
            else -> colors.tertiaryContainer
        },
        effects,
        label = "tile-icon-container",
    )
    val iconColor by animateColorAsState(
        when {
            zone == DropZone.DELETE -> colors.onError
            zone == DropZone.UNFAVORITE -> colors.onPrimary
            else -> colors.onTertiaryContainer
        },
        effects,
        label = "tile-icon",
    )
    val (container, content) = when (zone) {
        DropZone.DELETE -> colors.errorContainer to colors.onErrorContainer
        DropZone.UNFAVORITE -> colors.primaryContainer to colors.onPrimaryContainer
        DropZone.NONE -> Color.Unspecified to Color.Unspecified
    }
    val icon = when (zone) {
        DropZone.DELETE -> R.drawable.ic_delete
        DropZone.UNFAVORITE -> R.drawable.ic_star
        DropZone.NONE -> kind.icon
    }
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
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
                    .background(iconContainer),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = icon,
                    transitionSpec = { (scaleIn(spatial, initialScale = 0.6f) + fadeIn(fade)) togetherWith fadeOut(fade) },
                    label = "tile-icon-glyph",
                ) { glyph ->
                    Icon(painterResource(glyph), contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
                }
            }
        },
        supportingContent = { Text(stringResource(kind.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.colors(
            containerColor = colors.surfaceContainer,
            contentColor = colors.onSurface,
            draggedContainerColor = container,
            draggedContentColor = content,
        ),
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
 * block on the fast spatial motion the list item morphs its own shape on, and rounding further on
 * the sides [magnet] rounds as a tile nearby is swiped. A corner rounds as far as the more rounded
 * of its two sides.
 */
@Composable
private fun tileShape(corners: TileCorners, magnet: Magnet): Shape {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    fun size(outer: Boolean) = if (outer) OUTER_CORNER else INNER_CORNER
    val topStart by animateDpAsState(size(corners.topStart), spec, label = "tile-top-start")
    val topEnd by animateDpAsState(size(corners.topEnd), spec, label = "tile-top-end")
    val bottomEnd by animateDpAsState(size(corners.bottomEnd), spec, label = "tile-bottom-end")
    val bottomStart by animateDpAsState(size(corners.bottomStart), spec, label = "tile-bottom-start")
    val top = magnet.top.value.value
    val bottom = magnet.bottom.value.value
    val start = magnet.start.value.value
    val end = magnet.end.value.value
    fun rounded(base: Dp, a: Float, b: Float) = maxOf(base, lerp(INNER_CORNER, OUTER_CORNER, maxOf(a, b).coerceIn(0f, 1f)))
    return RoundedCornerShape(
        topStart = rounded(topStart, top, start),
        topEnd = rounded(topEnd, top, end),
        bottomEnd = rounded(bottomEnd, bottom, end),
        bottomStart = rounded(bottomStart, bottom, start),
    )
}
