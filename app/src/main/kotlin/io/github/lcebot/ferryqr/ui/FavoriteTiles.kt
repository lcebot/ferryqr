@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.ContentNames
import io.github.lcebot.ferryqr.history.HistoryEntry
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
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
 * One tile, kept from when it first shows until it has left: the [entry] it was last listed as, so
 * one leaving still shows it, how far it has come into view, its [swipe], and where it is drawn.
 */
private class TileRecord(var entry: HistoryEntry, visible: Boolean, val swipe: EntrySwipe) {
    val key: String = entry.text
    val show = TileShow(visible)

    /**
     * Where the tile in its place is drawn, and how wide, while it moves to a new place, whose width
     * can differ as rows share out what they have left. The tiles are laid out by hand, so each
     * follows its place from where it is drawn, as a lazy list's items do, rather than from where a
     * separate pass of the layout might put it. The layout only reads where it is drawn, and the
     * board moves it, springing on from where it is and as fast as it moves when its place changes
     * again on the way.
     */
    val motion = SpringFollower(Animatable(Rect.Zero, Rect.VectorConverter))

    /** Where the tile was last placed in its place, where it fades out once it leaves. */
    var lastRect: Rect? = null

    /** Where the tile is drawn while it follows its place, or null while it has none to follow. */
    val drawnAt: Rect? get() = motion.takeIf { it.placed }?.value?.value
}

/** A tile as it is composed: its [record], and its [entry] as listed, or as last listed while it is [gone]. */
private class ShownTile(val entry: HistoryEntry, val record: TileRecord, val gone: Boolean)

/**
 * The tiles of the favorites, each with its [TileRecord] from when it first shows until it has
 * left: faded out, or flown off and let go once undoing can no longer bring it back. Only measuring
 * tells where the tiles and the drop zone go, since it alone knows how tall their rows come out. It
 * leaves those places here as [targets] and [zoneTarget], and [follow] and the [zone] move what is
 * drawn toward them, so measuring starts nothing itself.
 */
@Stable
private class TileBoardState(private val list: MagneticList) {
    private val records = mutableStateMapOf<String, TileRecord>()

    /** Records made for tiles composed for the first time, until that composition is kept. */
    private val made = HashMap<String, TileRecord>()

    /** Whether the first tiles have been kept. Tiles there from the start are simply there; those that come later grow and fade in. */
    private var started = false

    /** Where each tile in its place goes, as measuring last found. */
    var targets by mutableStateOf<Map<String, Rect>>(emptyMap())

    /** Where the tile last picked up will land, as measuring last found, or null once it is neither in hand nor gliding into its place. */
    var zoneTarget by mutableStateOf<Rect?>(null)

    /** Marks where the tile in hand will land. */
    val zone = DropZone(Rect.VectorConverter, Rect.Zero)

    private val loop = FollowerLoop { records.values.map { it.motion } }

    /**
     * The tiles to compose: those of [present] in their order, then those still leaving. A tile
     * there from the start is in view at once where [visible] says it is.
     */
    fun tiles(present: List<HistoryEntry>, visible: (String) -> Boolean): List<ShownTile> {
        val listed = present.mapTo(HashSet()) { it.text }
        val shown = present.map { entry ->
            val record = records[entry.text] ?: made.getOrPut(entry.text) {
                TileRecord(entry, !started && visible(entry.text), list.departures.returning(entry.text) ?: EntrySwipe())
            }
            ShownTile(entry, record, gone = false)
        }
        val leaving = records.values.filter { it.key !in listed }.map { ShownTile(it.entry, it, gone = true) }
        return shown + leaving
    }

    /** Keeps the records of [tiles] once the composition they were composed in is kept. */
    fun keep(tiles: List<ShownTile>) {
        started = true
        made.clear()
        tiles.forEach { tile ->
            if (tile.gone) return@forEach
            val record = tile.record
            record.entry = tile.entry
            if (records[record.key] !== record) records[record.key] = record
        }
    }

    /** Lets go of [record], of a tile that has left; tiles leave only this way. */
    fun forget(record: TileRecord) {
        if (records[record.key] === record) records.remove(record.key)
    }

    /** Moves the tiles drawn toward the places measuring leaves, on [move], until cancelled. */
    suspend fun follow(move: () -> AnimationSpec<Rect>) {
        coroutineScope {
            launch { loop.run() }
            snapshotFlow { targets }.collect { settled ->
                // A tile in hand or fading out has no place to follow, so one set down starts afresh in its place.
                records.values.forEach { if (it.key !in settled) it.motion.unplace() }
                settled.forEach { (tile, place) -> records[tile]?.let { loop.moveTo(it.motion, place, move()) } }
            }
        }
    }
}

/** Whether [tile] is in view: one in hand or gliding into its place, or one in a row shown, and never one [gone]. */
private fun tileVisible(tile: String, gone: Boolean, session: FavoriteDragSession, arrangement: TileArrangement, rowsShown: Int): Boolean {
    if (gone) return false
    if (session.isMoving(tile)) return true
    val spot = arrangement.byKey[tile] ?: return false
    return spot.row < rowsShown
}

/**
 * How far a tile at [spot] swiped toward [side] travels to leave past the edge of the favorites,
 * [available] wide, and [margin] beyond.
 */
private fun flyDistance(spot: TileSpot?, side: Float, available: Int, rtl: Boolean, margin: Float): Float {
    if (spot == null) return 0f
    val left = if (rtl) available - spot.x - spot.width else spot.x
    return (if (side > 0f) available - left else left + spot.width) + margin
}

/**
 * The favorites as tiles. A tap opens one, and a swipe deletes it or takes it out of the favorites
 * as a swipe on a row does, the tiles around it leaning with it. A long press lifts it and opens
 * its menu, unfolding the favorites first, and dragging it then closes the menu and moves it into
 * the place of the tile it is held over, [session] keeping the order once it is let go. Tiles
 * slide into their new places, and come and go fading and growing or shrinking, on the theme's
 * motion. [namesOf] gives the names the history lists show an entry by, as each tile shows it.
 * A tile let go glides into its place on [settleSpec], the spec the rows of the same [session] glide on.
 */
@Composable
internal fun FavoriteTiles(
    board: TileBoard,
    session: FavoriteDragSession,
    settleSpec: FiniteAnimationSpec<Rect>,
    favorites: Map<String, HistoryEntry>,
    namesOf: (HistoryEntry) -> ContentNames?,
    inputs: TileInputs,
    arrangement: TileArrangement,
    rowsShown: Int,
    list: MagneticList,
    actions: EntryActions,
    onLift: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(list) { TileBoardState(list) }
    val motion = MaterialTheme.motionScheme
    val heightSpec = motion.defaultSpatialSpec<Size>()
    val move by rememberUpdatedState(motion.defaultSpatialSpec<Rect>())
    // Where each tile is in the order, for the actions that move it a place at a time.
    val places = remember(inputs.keys) { inputs.keys.withIndex().associate { (index, key) -> key to index } }
    val present = remember(inputs.keys, favorites) { inputs.keys.mapNotNull { favorites[it] } }
    val tiles = state.tiles(present) { tileVisible(it, gone = false, session, arrangement, rowsShown) }
    SideEffect { state.keep(tiles) }

    FollowDropZone(state.zone, shown = session.zoneShown(board)) { state.zoneTarget }
    LaunchedEffect(state) { state.follow { move } }

    Layout(
        content = {
            tiles.forEach { tile ->
                // Each tile composes on its own, so a change to one tile leaves the others be.
                key(tile.record) {
                    TileSlot(
                        record = tile.record,
                        entry = tile.entry,
                        gone = tile.gone,
                        state = state,
                        board = board,
                        session = session,
                        settleSpec = settleSpec,
                        list = list,
                        arrangement = arrangement,
                        rowsShown = rowsShown,
                        available = inputs.available,
                        index = places[tile.record.key] ?: -1,
                        last = inputs.keys.lastIndex,
                        listed = namesOf(tile.entry),
                        actions = actions,
                        onLift = onLift,
                    )
                }
            }
            Box(Modifier.dropZone(state.zone) { size.toRect() })
        },
        // The list moves the rows below along with the tiles as they fold and unfold, bringing rows
        // into view and out of it as a scroll would, rather than all at once.
        modifier = modifier.animateSize(heightSpec),
        measurePolicy = TileMeasurePolicy(tiles, arrangement, inputs, rowsShown, board, session, state),
    )
}

/**
 * One tile of the favorites, [record], showing [entry], or the entry it was last listed as while
 * [gone]. It is [index] in the order, of which [last] is the last place, and [listed] gives the
 * names the history lists show it by.
 */
@Composable
private fun TileSlot(
    record: TileRecord,
    entry: HistoryEntry,
    gone: Boolean,
    state: TileBoardState,
    board: TileBoard,
    session: FavoriteDragSession,
    settleSpec: FiniteAnimationSpec<Rect>,
    list: MagneticList,
    arrangement: TileArrangement,
    rowsShown: Int,
    available: Int,
    index: Int,
    last: Int,
    listed: ContentNames?,
    actions: EntryActions,
    onLift: () -> Unit,
) {
    val tile = record.key
    val haptics = rememberHaptics()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val detachAt = with(density) { DETACH_AT.toPx() }
    val flyMargin = with(density) { FLY_MARGIN.toPx() }
    val motion = MaterialTheme.motionScheme
    val showSpec = motion.defaultEffectsSpec<Float>()
    val growSpec = motion.defaultSpatialSpec<Float>()
    val laidOut by rememberUpdatedState(arrangement)
    val room by rememberUpdatedState(available)
    val lift by rememberUpdatedState(onLift)
    val visible = tileVisible(tile, gone, session, arrangement, rowsShown)
    val active by rememberUpdatedState(visible)
    val magnet = rememberMagnet(list, tile, detachAt, NEAR_TILE_SHARE) { pull -> board.relation(pull, tile, laidOut) }
    val swipe = rememberEntrySwipe(
        entry = entry,
        group = TILES_GROUP,
        index = 0,
        list = list,
        magnet = magnet,
        flyTo = { side -> flyDistance(laidOut.byKey[tile], side, room, rtl, flyMargin) },
        onDelete = actions.onDelete,
        onFavorite = actions.onFavorite,
        leavesOnFavorite = true,
        listed = !gone,
        swipe = record.swipe,
    )
    val waiting = list.departures.isDeparted(tile)
    LaunchedEffect(visible, gone, waiting) {
        when {
            // One swiped away has already left the screen. Deleted, it waits there while undoing
            // can bring it back; taken out of the favorites, it goes.
            gone && swipe.acting -> {
                // What the swipe showed behind it fades before the tile's place is let go.
                swipe.backdrop.animateTo(0f, showSpec)
                record.show.snap(false)
                if (!waiting) state.forget(record)
            }
            // Brought back by undo, it flies back into its place in its turn, in view at once rather
            // than fading in. Its place in a folded row is hidden, so there it stays out of sight
            // rather than flying in below the favorites, over the rows under them.
            !gone && swipe.acting -> record.show.snap(visible)
            else -> {
                record.show.show(visible, showSpec, growSpec)
                if (gone) state.forget(record)
            }
        }
    }
    val spot = arrangement.byKey[tile]
    val corners = if (spot != null && !gone) arrangement.cornersOf(spot, rowsShown) else ALL_INNER
    val shape = rememberSegmentShape(corners, magnet)
    val menu = remember { EntryMenuState() }
    // A long press that lifts the tile is not also a tap when the finger lifts without moving, and
    // only the touch that lifted it drags it and sets it down; each new touch starts unmarked.
    val longPressed = remember { booleanArrayOf(false) }
    val moreLabel = stringResource(R.string.entry_more)
    val earlier = stringResource(R.string.favorite_move_earlier)
    val later = stringResource(R.string.favorite_move_later)
    SwipeableEntry(
        entry = entry,
        swipe = swipe,
        magnet = magnet,
        shape = shape,
        menu = menu,
        actions = actions,
        modifier = Modifier.graphicsLayer {
            alpha = record.show.alpha.value
            scaleX = record.show.scale.value
            scaleY = record.show.scale.value
        },
        swipeEnabled = visible && !session.busy,
        gestures = Modifier
            .trackTouch { longPressed[0] = false }
            .pointerInput(tile, rtl) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val grab = if (rtl) Offset(size.width - offset.x, offset.y) else offset
                        // While another tile is lifted, a second finger lifts none.
                        if (active && !list.held && session.pickUp(tile, board, grab)) {
                            longPressed[0] = true
                            haptics.longPress()
                            lift()
                            menu.expanded = true
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (longPressed[0]) {
                            val wasMoved = session.moved
                            session.drag(if (rtl) Offset(-amount.x, amount.y) else amount, viewConfiguration.touchSlop)
                            if (!wasMoved && session.moved) menu.expanded = false
                        }
                    },
                    onDragEnd = { if (longPressed[0]) session.drop(tile, settleSpec) },
                    onDragCancel = { if (longPressed[0]) session.drop(tile, settleSpec) },
                )
            },
        menuShown = !gone,
    ) {
        FavoriteTile(
            // Kept with the tile, so one fading out still shows its name.
            names = rememberNames(entry, listed),
            shape = shape,
            lifted = session.hand == tile,
            raised = swipe.pull != null || swipe.acting,
            onClick = {
                // While another tile is lifted, a second finger's tap opens nothing.
                if (!longPressed[0] && active && !list.held && session.hand == null) actions.onOpen(entry)
            },
            // Without a finger to drag with, the order changes a tile at a time.
            modifier = Modifier.semantics {
                onLongClick(moreLabel) {
                    menu.expanded = true
                    true
                }
                customActions = listOfNotNull(
                    CustomAccessibilityAction(earlier) { session.move(tile, -1); true }.takeIf { index > 0 },
                    CustomAccessibilityAction(later) { session.move(tile, 1); true }.takeIf { index in 0 until last },
                )
            },
        )
    }
}

/**
 * Lays out [tiles], each in its place in [arrangement] among the first [rowsShown] rows, those in
 * hand or gliding into their places where [session] draws them, and the drop zone, which comes after
 * the tiles, where the tile it marks will land. It leaves the places the tiles and the drop zone go
 * with [state], and the heights of the rows with [board].
 */
private class TileMeasurePolicy(
    private val tiles: List<ShownTile>,
    private val arrangement: TileArrangement,
    private val inputs: TileInputs,
    private val rowsShown: Int,
    private val board: TileBoard,
    private val session: FavoriteDragSession,
    private val state: TileBoardState,
) : MeasurePolicy {
    // A spatial spring narrowing a tile into a row that no longer widens it overshoots past its own
    // width before it settles, which would wrap its text for a moment. A tile is never laid out
    // narrower than its own width, so the spring springs back only as far as that.
    private fun atLeastOwn(tile: String, width: Float): Int =
        max(width.roundToInt(), minOf(inputs.widths[tile] ?: 0, inputs.available)).coerceAtLeast(0)

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val zone = measurables[tiles.size]
        // Tiles in their places take the height the list item gives itself at the width they are
        // drawn at, as the rows below do, and each row is as tall as its tallest tile. Only a name
        // too long for a whole row wraps to a second line, and such a tile has its row to itself,
        // so a row never holds tiles of different heights. Tiles in hand or gliding into their
        // places and those fading out keep the size they are drawn at, and a row holding only tiles
        // that move keeps the height they are drawn at.
        val placeables = arrayOfNulls<Placeable>(tiles.size)
        val tallest = IntArray(arrangement.rows)
        val movingTallest = IntArray(arrangement.rows)
        tiles.forEachIndexed { i, tile ->
            val key = tile.record.key
            if (tile.gone) return@forEachIndexed
            val spot = arrangement.byKey[key] ?: return@forEachIndexed
            val moving = session.positionOf(key)
            if (moving != null) {
                movingTallest[spot.row] = max(movingTallest[spot.row], moving.height.roundToInt())
                return@forEachIndexed
            }
            val width = atLeastOwn(key, tile.record.drawnAt?.width ?: spot.width.toFloat())
            placeables[i] = measurables[i].measure(Constraints(minWidth = width, maxWidth = width))
                .also { tallest[spot.row] = max(tallest[spot.row], it.height) }
        }
        for (row in tallest.indices) if (tallest[row] == 0) tallest[row] = movingTallest[row]
        val heights = RowHeights(tallest, arrangement.gap)
        board.measured(heights)
        // Where each tile is drawn: a tile in its place glides to a new one, in place and width,
        // from where it is drawn; one that moves and one fading out are drawn where they are, and a
        // tile set down starts afresh in its place.
        val settled = HashMap<String, Rect>()
        val placed = tiles.mapIndexed { i, tile ->
            val key = tile.record.key
            val drawn = session.positionOf(key) ?: when {
                tile.gone -> tile.record.lastRect
                else -> arrangement.byKey[key]?.let { spot ->
                    val place = arrangement.rectOf(spot, heights)
                    settled[key] = place
                    tile.record.drawnAt ?: place
                }
            } ?: return@mapIndexed null
            val placeable = placeables[i] ?: measurables[i].measure(
                Constraints.fixed(atLeastOwn(key, drawn.width), drawn.height.roundToInt().coerceAtLeast(0)),
            )
            placeable to drawn.topLeft
        }
        state.targets = settled
        // The drop zone marks where the tile last picked up will land, as large as its place there,
        // and follows that place as the tiles do theirs.
        val landing = session.marked?.takeIf { session.isMoving(it) }?.let { arrangement.byKey[it] }
            ?.let { arrangement.rectOf(it, heights) }
        state.zoneTarget = landing
        val ghost = state.zone.alpha.value
        val zoneDrawn = landing?.let { place -> state.zone.drawnAt?.takeIf { ghost > 0f } ?: place }
        val zonePlaceable = zoneDrawn?.let {
            zone.measure(Constraints.fixed(it.width.roundToInt().coerceAtLeast(0), it.height.roundToInt().coerceAtLeast(0)))
        }
        return layout(constraints.maxWidth, heights.total(rowsShown)) {
            if (zoneDrawn != null && zonePlaceable != null && ghost > 0f) {
                zonePlaceable.placeRelative(zoneDrawn.left.roundToInt(), zoneDrawn.top.roundToInt())
            }
            placed.forEachIndexed { i, item ->
                val (placeable, at) = item ?: return@forEachIndexed
                val tile = tiles[i]
                val record = tile.record
                // Once faded out, a hidden tile is not placed, so it takes no touches.
                if (!tileVisible(record.key, tile.gone, session, arrangement, rowsShown) && record.show.alpha.value == 0f) {
                    return@forEachIndexed
                }
                if (!tile.gone) record.lastRect = Rect(at, Size(placeable.width.toFloat(), placeable.height.toFloat()))
                // A tile in hand or gliding into its place and one being swiped, flying off or flying
                // back are what the user is doing, so they are drawn over every other tile.
                val swiped = record.swipe.pull != null || record.swipe.acting
                placeable.placeRelative(
                    at.x.roundToInt(),
                    at.y.roundToInt(),
                    zIndex = if (session.isMoving(record.key) || swiped) 1f else 0f,
                )
            }
        }
    }
}

/** How small a tile is as it fades out or before it fades in. */
private const val HIDDEN_SCALE = 0.8f

private val ALL_INNER = SegmentCorners(topStart = false, topEnd = false, bottomStart = false, bottomEnd = false)

/**
 * One favorite as a tile: its icon, and its name over its kind, as [names] give them, in
 * the list item's own padding, colours, press shape and dragged look, with the avatar and title a
 * history row has. While [raised], swiped, it takes the dragged elevation.
 */
@Composable
private fun FavoriteTile(
    names: ContentNames,
    shape: Shape,
    lifted: Boolean,
    raised: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // A tile swiped across the tiles beside it is raised to the elevation a dragged list item takes,
    // so its shadow keeps it apart from tiles of the same colour it passes over; the list item moves
    // between its elevations on its own spatial motion.
    val swiped = ListItemDefaults.elevation().draggedElevation
    ListItem(
        onClick = onClick,
        modifier = modifier,
        interactionSource = rememberLiftedInteraction(lifted),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { EntryAvatar(names.kind.icon, favorite = true) },
        supportingContent = { Text(stringResource(names.kind.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.colors(containerColor = colors.surfaceContainer, contentColor = colors.onSurface),
        elevation = ListItemDefaults.elevation(elevation = if (raised) swiped else 0.dp),
    ) {
        // Only a name too long for a whole row comes to a second line.
        EntryTitle(names.tileTitle)
    }
}
