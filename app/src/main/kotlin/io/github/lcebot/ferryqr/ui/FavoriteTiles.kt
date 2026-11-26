@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.dispatch.Cards
import io.github.lcebot.ferryqr.history.HistoryEntry
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
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
 * from the end side, the same sides a swipe on a row uses for the same actions.
 */
internal val TILE_OUT_MARGIN = 24.dp

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

internal class TileArrangement(
    val spots: List<TileSpot>,
    val rows: Int,
    private val rowEnds: IntArray,
    val gap: Int,
) {
    val byKey: Map<String, TileSpot> = spots.associateBy { it.key }

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
     * Where the tile [key] goes among [keys] with the finger at [finger], or null where it already
     * is. In the row the finger is in, a tile it is over takes the held tile before it while the
     * finger is on its start half and after it on its end half; past the end of the row the held
     * tile follows the row's last.
     */
    fun targetFor(key: String, keys: List<String>, finger: Offset, rowsShown: Int, heights: RowHeights): Int? {
        val from = keys.indexOf(key)
        if (from < 0 || spots.isEmpty() || rowsShown <= 0) return null
        val row = heights.rowAt(finger.y, minOf(rows, rowsShown))
        val inRow = spots.filter { it.row == row }
        if (inRow.isEmpty()) return null
        val over = inRow.firstOrNull { finger.x < it.x + it.width + gap } ?: inRow.last()
        if (over.key == key) return null
        val before = finger.x < over.x + over.width / 2f
        val others = keys.filter { it != key }
        val to = others.indexOf(over.key) + if (before) 0 else 1
        return to.takeIf { it != from }
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
 * Lays out tiles of the natural widths [widthOf] gives, in rows [available] wide. Without
 * [columns], each row takes as many tiles as fit and shares what is left over among them, so every
 * row is full. With [columns], each tile spans as many columns as its name needs, and rows end
 * where the next tile no longer fits, so tiles line up in columns and rows may end short.
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
    if (columns == null) {
        val rows = mutableListOf<MutableList<Pair<String, Int>>>()
        var used = 0
        keys.forEach { key ->
            val width = widthOf(key).coerceIn(1, available)
            val row = rows.lastOrNull()
            if (row != null && used + gap + width <= available) {
                row += key to width
                used += gap + width
            } else {
                rows += mutableListOf(key to width)
                used = width
            }
        }
        rows.forEach { row ->
            val spare = available - row.sumOf { it.second } - gap * (row.size - 1)
            var x = 0
            addRow(
                row.mapIndexed { i, (key, width) ->
                    // The pixels that do not divide evenly go to the first tiles, one each.
                    val share = spare / row.size + if (i < spare % row.size) 1 else 0
                    val start = x
                    x += width + share + gap
                    key to (start..start + width + share)
                },
            )
        }
    } else {
        val pitch = (available + gap).toFloat() / columns
        fun edge(column: Int) = (column * pitch).roundToInt()
        var row = mutableListOf<Pair<String, IntRange>>()
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
     * a place that removes the tile, and otherwise moves the tile in the order to where it is held.
     */
    private fun follow() {
        val tile = key ?: return
        val finger = at + grab
        val arrangement = arrange()
        val height = heights.total(rowsShown)
        val now = when {
            finger.y < -outMargin || finger.y > height + outMargin -> DropZone.UNFAVORITE
            finger.x < 0f -> DropZone.DELETE
            finger.x > available -> DropZone.UNFAVORITE
            else -> DropZone.NONE
        }
        if (now != zone) {
            zone = now
            swipeHaptics.threshold()
        }
        if (now != DropZone.NONE) return
        val to = arrangement.targetFor(tile, keys, finger, rowsShown, heights) ?: return
        val next = keys.filter { it != tile }.toMutableList().apply { add(to, tile) }
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
        retained.remove(tile)
        lastRects.remove(tile)
        departed.remove(tile)
    }
}

/**
 * The favorites as tiles. A tap opens one. A long press lifts it and opens its menu, and dragging
 * it then closes the menu and moves it among the others, which make way for it; let go beside or
 * outside the favorites, it is deleted or leaves them, as [onDrop] decides. Tiles slide and resize
 * into their new places, and come and go fading and growing or shrinking, on the theme's motion.
 */
@Composable
internal fun FavoriteTiles(
    board: TileBoard,
    favorites: Map<String, HistoryEntry>,
    arrangement: TileArrangement,
    actions: EntryActions,
    onShift: (HistoryEntry, Int) -> Unit,
    onDrop: (HistoryEntry, DropZone) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val motion = MaterialTheme.motionScheme
    val settleSpec = motion.fastSpatialSpec<Rect>()
    val boundsSpec = motion.defaultSpatialSpec<Rect>()
    val showSpec = motion.defaultEffectsSpec<Float>()
    val bounds = remember(boundsSpec) { BoundsTransform { _, _ -> boundsSpec } }
    val drop by rememberUpdatedState(onDrop)
    val current by rememberUpdatedState(favorites)

    val present = board.keys.mapNotNull { favorites[it] }
    val presentKeys = board.keys.toSet()
    SideEffect {
        present.forEach { if (board.retained[it.text] != it) board.retained[it.text] = it }
    }
    val leaving = board.retained.values.filter { it.text !in presentKeys }
    val tiles = present.map { it to false } + leaving.map { it to true }
    // Tiles there from the start are simply there; those that come later grow and fade in.
    val initial = remember { presentKeys }
    val shown = remember { HashMap<String, Animatable<Float, AnimationVector1D>>() }

    fun visible(tile: String, gone: Boolean): Boolean {
        if (gone) return false
        if (board.key == tile) return true
        val spot = arrangement.byKey[tile] ?: return false
        return spot.row < board.rowsShown
    }

    fun rectOf(tile: String, gone: Boolean, heights: RowHeights): Rect? = when {
        board.key == tile -> Rect(board.at, board.size)
        gone -> board.lastRects[tile]
        else -> arrangement.byKey[tile]?.let { arrangement.rectOf(it, heights) }
    }

    LookaheadScope {
        Layout(
            modifier = modifier,
            content = {
                tiles.forEach { (entry, gone) ->
                    key(entry.text) {
                        val tile = entry.text
                        val isVisible = visible(tile, gone)
                        val progress = shown.getOrPut(tile) { Animatable(if (isVisible && tile in initial) 1f else 0f) }
                        LaunchedEffect(isVisible, gone) {
                            progress.animateTo(if (isVisible) 1f else 0f, showSpec)
                            if (gone) {
                                board.forget(tile)
                                shown.remove(tile)
                            }
                        }
                        val active by rememberUpdatedState(isVisible)
                        val dragged = board.key == tile
                        val departedAs = if (gone) board.departed[tile] else null
                        val zone = if (dragged) board.zone else departedAs ?: DropZone.NONE
                        val spot = arrangement.byKey[tile]
                        val corners = if (spot != null && !gone) arrangement.cornersOf(spot, board.rowsShown) else ALL_INNER
                        val index = board.keys.indexOf(tile)
                        var menu by remember { mutableStateOf(false) }
                        // A long press that lifts the tile is not also a tap when the finger lifts
                        // without moving; each new touch starts unmarked.
                        val longPressed = remember { booleanArrayOf(false) }
                        Box(
                            Modifier
                                .then(if (dragged) Modifier else Modifier.animateBounds(this@LookaheadScope, boundsTransform = bounds))
                                .graphicsLayer {
                                    val p = progress.value
                                    alpha = p
                                    scaleX = HIDDEN_SCALE + (1f - HIDDEN_SCALE) * p
                                    scaleY = scaleX
                                }
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
                            val moreLabel = stringResource(R.string.entry_more)
                            val earlier = stringResource(R.string.favorite_move_earlier)
                            val later = stringResource(R.string.favorite_move_later)
                            FavoriteTile(
                                entry = entry,
                                // Kept with the tile, so one fading out still shows its name.
                                title = remember(entry.label, entry.format) { tileTitle(context, entry) },
                                corners = corners,
                                lifted = (dragged && board.lifted) || departedAs != null,
                                zone = zone,
                                onClick = {
                                    if (!longPressed[0] && active) actions.onOpen(entry)
                                },
                                // Without a finger to drag with, the order changes a tile at a time.
                                modifier = Modifier.semantics {
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
            },
        ) { measurables, constraints ->
            // Tiles in their places take the height the list item gives itself, as the rows below
            // do, and each row is as tall as its tallest tile. Only a name too long for a whole row
            // wraps to a second line, and such a tile has its row to itself, so a row never holds
            // tiles of different heights. The tile in hand and those fading out keep the size they
            // were drawn at, and a row holding only the tile in hand keeps its height.
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
                val rect = rectOf(entry.text, gone, heights) ?: return@mapIndexed null
                val placeable = placeables[i] ?: measurable.measure(
                    Constraints.fixed(rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0)),
                )
                placeable to rect
            }
            layout(constraints.maxWidth, heights.total(board.rowsShown)) {
                placed.forEachIndexed { i, item ->
                    val (placeable, rect) = item ?: return@forEachIndexed
                    val (entry, gone) = tiles[i]
                    val tile = entry.text
                    // Once faded out, a hidden tile is not placed, so it takes no touches.
                    if (!visible(tile, gone) && (shown[tile]?.value ?: 0f) == 0f) return@forEachIndexed
                    if (!gone) board.lastRects[tile] = rect
                    placeable.placeRelative(
                        rect.left.roundToInt(),
                        rect.top.roundToInt(),
                        zIndex = if (tile == board.key) 1f else 0f,
                    )
                }
            }
        }
    }
}

/** How small a tile is as it fades out or before it fades in. */
private const val HIDDEN_SCALE = 0.8f

private val ALL_INNER = TileCorners(topStart = false, topEnd = false, bottomStart = false, bottomEnd = false)

/**
 * One favorite as a tile: its icon, and its name over its kind, each on one line, in the list
 * item's own padding, colours,
 * press shape and dragged look. Held over a place that removes it, it takes the colours of what
 * letting go would do, as a swiped row shows behind it: the error colours to delete, and the
 * primary colours of other entries to leave the favorites.
 */
@Composable
private fun FavoriteTile(
    entry: HistoryEntry,
    title: String,
    corners: TileCorners,
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
            lifted -> colors.tertiary
            else -> colors.tertiaryContainer
        },
        effects,
        label = "tile-icon-container",
    )
    val iconColor by animateColorAsState(
        when {
            zone == DropZone.DELETE -> colors.onError
            zone == DropZone.UNFAVORITE -> colors.onPrimary
            lifted -> colors.onTertiary
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
        shapes = ListItemDefaults.shapes(shape = tileShape(corners)),
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

/** The segmented corners of a tile, following it as it becomes or stops being a corner of the block. */
@Composable
private fun tileShape(corners: TileCorners): Shape {
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Dp>()
    fun size(outer: Boolean) = if (outer) OUTER_CORNER else INNER_CORNER
    val topStart by animateDpAsState(size(corners.topStart), spec, label = "tile-top-start")
    val topEnd by animateDpAsState(size(corners.topEnd), spec, label = "tile-top-end")
    val bottomEnd by animateDpAsState(size(corners.bottomEnd), spec, label = "tile-bottom-end")
    val bottomStart by animateDpAsState(size(corners.bottomStart), spec, label = "tile-bottom-start")
    return RoundedCornerShape(topStart = topStart, topEnd = topEnd, bottomEnd = bottomEnd, bottomStart = bottomStart)
}
