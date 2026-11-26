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
 * The list item's own space between its leading content and its text. A tile is laid out as a
 * history row is, with the list item's own padding, so it stands as tall as the rows below it: its
 * avatar, then its name over its kind.
 */
private val TILE_ICON_GAP = 12.dp

/**
 * What a tile adds beside its text: the list item's padding at both ends, the icon and the space
 * after it, with a pixel to spare so rounding never cuts the text short.
 */
internal fun tileChrome(layoutDirection: LayoutDirection): Dp {
    val padding = ListItemDefaults.ContentPadding
    return padding.calculateStartPadding(layoutDirection) + ENTRY_AVATAR + TILE_ICON_GAP +
        padding.calculateEndPadding(layoutDirection) + 1.dp
}

/** Columns of the grid are at least this wide, which makes four across a phone held upright. */
internal val GRID_COLUMN = 80.dp

/** How far past the edge of the favorites a swiped tile flies, beyond the list's margin and the system bars. */
private val FLY_MARGIN = 48.dp

/** The group the tiles form for a swipe. */
internal const val TILES_GROUP = "favorites:tiles"

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
    inputs: TileInputs,
    arrangement: TileArrangement,
    rowsShown: Int,
    pendingUndo: Set<String>,
    list: MagneticList,
    actions: EntryActions,
    onShift: (HistoryEntry, Int) -> Unit,
    onLift: () -> Unit,
    onReorder: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    val latestInputs by rememberUpdatedState(inputs)
    val undoable by rememberUpdatedState(pendingUndo)
    // Where each tile is in the order, for the actions that move it a place at a time.
    val places = remember(inputs.keys) { inputs.keys.withIndex().associate { (index, key) -> key to index } }

    val present = inputs.keys.mapNotNull { favorites[it] }
    val presentKeys = places.keys
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
        return spot.row < rowsShown
    }

    fun forgetTile(tile: String) {
        board.forget(tile)
        shown.remove(tile)
        swipes.remove(tile)
    }

    /** How far a tile swiped toward [side] travels to leave past the edge of the favorites. */
    fun flyDistance(tile: String, side: Float): Float {
        val spot = laidOut.byKey[tile] ?: return 0f
        val available = latestInputs.available
        val left = if (rtl) available - spot.x - spot.width else spot.x
        return (if (side > 0f) available - left else left + spot.width) + flyMargin
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
                                if (tile !in undoable) forgetTile(tile)
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
                    val corners = if (spot != null && !gone) arrangement.cornersOf(spot, rowsShown) else ALL_INNER
                    val shape = tileShape(corners, magnet)
                    val index = places[tile] ?: -1
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
                            // Kept with the tile, so one fading out still shows its name.
                            presentation = rememberPresentation(entry),
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
                                            .takeIf { index in 0 until inputs.keys.lastIndex },
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
            max(width.roundToInt(), minOf(inputs.widths[tile] ?: 0, inputs.available)).coerceAtLeast(0)
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
        board.measured(heights)
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
        layout(constraints.maxWidth, heights.total(rowsShown)) {
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
 * One favorite as a tile: its icon, and its name over its kind, as [presentation] gives them, in
 * the list item's own padding, colours, press shape and dragged look, with the avatar and title a
 * history row has. While [raised], swiped, it takes the dragged elevation.
 */
@Composable
private fun FavoriteTile(
    presentation: EntryPresentation,
    shape: Shape,
    lifted: Boolean,
    raised: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        onClick = onClick,
        modifier = modifier,
        interactionSource = rememberLiftedInteraction(lifted),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { EntryAvatar(presentation.kind.icon, favorite = true) },
        supportingContent = { Text(stringResource(presentation.kind.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.colors(containerColor = colors.surfaceContainer, contentColor = colors.onSurface),
        // A tile swiped across the tiles beside it is raised as a dragged list item is, so its
        // shadow keeps it apart from tiles of the same colour it passes over; the list item moves
        // between its elevations on its own spatial motion.
        elevation = ListItemDefaults.elevation(elevation = if (raised) SWIPED_ELEVATION else 0.dp),
    ) {
        // Only a name too long for a whole row comes to a second line.
        EntryTitle(presentation.tileTitle)
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
