@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * How close to the top or bottom of the visible list a favorite being dragged makes the list scroll
 * toward the favorites out of view: 48dp, the edge zone of the Reorderable library for Compose and
 * the size of a touch target. Scrolling stops once the first or last favorite is as far inside the
 * edge, so the row dragged to it rests outside the zone. Material leaves this to the app.
 */
private val REORDER_EDGE = 48.dp

/**
 * How fast the list scrolls per second with the row at the edge or past it, slower in proportion
 * the less far into [REORDER_EDGE] it is: RecyclerView's ItemTouchHelper scrolls at most 20dp a
 * frame, which is this at 60 frames a second.
 */
private val REORDER_SCROLL_SPEED = 1200.dp

/**
 * Nearing where it stops, the list scrolls at most this many times the distance left each second,
 * so it eases into place instead of halting at full speed.
 */
private const val REORDER_SCROLL_EASE = 10f

/**
 * Where a favorite being dragged is drawn and how far the favorites reach past the top and bottom
 * edges of the visible list, in the list's pixels.
 */
internal class HeldReach(val top: Float, val bottom: Float, val above: Float, val below: Float)

/**
 * Where a favorite in hand will land, marked as a reorderable list marks it: a zone under the
 * favorite in hand, in its own colour and in the outline of a dragged list item. The list's rows
 * and the tiles each mark it with one, so both mark it alike. How far it is in view is [alpha], and
 * where it is drawn, in the coordinates of [T], follows the landing place through [follow].
 */
@Stable
internal class DropZone<T, V : AnimationVector>(converter: TwoWayConverter<T, V>, initial: T) {
    val alpha = Animatable(0f)

    private val place = SpringFollower(Animatable(initial, converter))

    private val loop = FollowerLoop { listOf(place) }

    /** Where the zone is drawn, or null before it has been put anywhere. */
    val drawnAt: T? get() = place.takeIf { it.placed }?.value?.value

    /** Stops the zone where it is drawn, and says where that is. */
    fun stop(): T {
        place.stopAnimating()
        return place.value.value
    }

    /**
     * Moves the zone to each landing place [target] gives, on [move], until cancelled. Appearing, the
     * zone starts in its place; still in view, it glides to the next one, as the items around it
     * slide to theirs.
     */
    suspend fun follow(target: () -> T?, move: () -> AnimationSpec<T>) {
        coroutineScope {
            launch { loop.run() }
            snapshotFlow(target).collect { to ->
                if (to == null) return@collect
                if (alpha.value == 0f) place.unplace()
                loop.moveTo(place, to, move())
            }
        }
    }
}

/**
 * Shows [zone] while [shown], fading it on the effects spec, as only the look of the place changes,
 * and moves it to each landing place [target] gives on the spatial spring items move on.
 */
@Composable
internal fun <T> FollowDropZone(zone: DropZone<T, *>, shown: Boolean, target: () -> T?) {
    val motion = MaterialTheme.motionScheme
    val showSpec = motion.defaultEffectsSpec<Float>()
    val move by rememberUpdatedState(motion.defaultSpatialSpec<T>())
    val where by rememberUpdatedState(target)
    LaunchedEffect(zone, shown) { zone.alpha.animateTo(if (shown) 1f else 0f, showSpec) }
    LaunchedEffect(zone) { zone.follow({ where() }) { move } }
}

/** Draws [zone] behind the element's content, over the rect [at] gives in the element's own bounds, if any. */
@Composable
internal fun Modifier.dropZone(zone: DropZone<*, *>, at: DrawScope.() -> Rect?): Modifier {
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    val shape = ListItemDefaults.shapes().draggedShape
    return drawBehind {
        val alpha = zone.alpha.value
        if (alpha <= 0f) return@drawBehind
        val rect = at() ?: return@drawBehind
        translate(rect.left, rect.top) {
            drawOutline(shape.createOutline(rect.size, layoutDirection, this), color, alpha)
        }
    }
}

/**
 * While a favorite of [session] is held near the top or bottom of the visible list, and favorites
 * reach past that edge, the list scrolls toward them. Only the list's content moves: the held row
 * or tile stays under the finger, taking each scroll off its drag, the top bar keeps its size since
 * the scroll does not pass through it, and the list goes no further than the first or last favorite
 * plus [REORDER_EDGE]. It stops as the finger lifts. The list is drawn behind the navigation bar,
 * [bottomInset] tall, so its edge for scrolling is the top of the bar.
 */
@Composable
internal fun ReorderAutoScroll(listState: LazyListState, session: FavoriteDragSession, bottomInset: Dp) {
    val density = LocalDensity.current
    val edge = with(density) { REORDER_EDGE.toPx() }
    val fastest = with(density) { REORDER_SCROLL_SPEED.toPx() }
    val barHeight = with(density) { bottomInset.toPx() }
    val held = session.held
    LaunchedEffect(held, session, edge, fastest, barHeight) {
        if (held == null) return@LaunchedEffect
        // The way the list has been scrolling, which it keeps up until the margin even once the
        // favorites no longer reach past the edge.
        var going = 0
        // Stepped as the springs are, so a pause, as while the page is hidden, does not scroll the
        // list by all of it at once.
        val frames = FrameSteps()
        while (true) {
            val seconds = frames.elapsed(withFrameNanos { it }) / 1_000_000_000f
            val info = listState.layoutInfo
            // Offset 0 is the top of the visible list, just below the top bar; the bottom is where
            // the navigation bar starts covering the list, which runs on behind it.
            val bottom = info.viewportEndOffset - barHeight
            val reach = session.reach(info, bottom)
            if (reach == null) {
                going = 0
                continue
            }
            val upDepth = (edge - reach.top) / edge
            val downDepth = (reach.bottom - (bottom - edge)) / edge
            val way = when {
                upDepth > 0f && (reach.above > 0f || (going < 0 && reach.above > -edge)) -> -1
                downDepth > 0f && (reach.below > 0f || (going > 0 && reach.below > -edge)) -> 1
                else -> 0
            }
            going = way
            if (way == 0 || seconds == 0f) continue
            val depth = (if (way < 0) upDepth else downDepth).coerceIn(0f, 1f)
            // Slows on the way to the margin rather than stopping at full speed.
            val left = (if (way < 0) reach.above else reach.below) + edge
            // Within half a pixel the list has arrived, so the easing does not creep on forever.
            if (left < 0.5f) continue
            val speed = min(fastest * depth, left * REORDER_SCROLL_EASE)
            if (speed <= 0f) continue
            session.onScrolled(listState.scrollBy(way * speed * seconds))
        }
    }
}


/**
 * The favorites moved as rows of the list, each [rowKey] in the list, right below the list item
 * [headerKey], their title, while [session] has the list's mode on. The row in hand is drawn apart
 * from its place by as far as the finger took it, and passes a neighbour once its middle crosses the
 * neighbour's. It sets its dragged look down as the finger lifts and stays above its neighbours only
 * until it lands, so letting go answers at once. Its slot, where it will land, shows the [zone] the
 * tiles show for a tile in hand, once the row has moved, and the zone fades as the row settles into it.
 */
@Stable
internal class ListDragLayout(
    private val listState: LazyListState,
    private val session: FavoriteDragSession,
    private val haptics: Haptics,
    private val rowKey: (String) -> Any,
    private val headerKey: Any,
) : FavoriteDragLayout {
    /**
     * Where the slot of the row in hand is, in a measure of its own that moves by the distance its
     * slot moves each time the order changes, which the zone glides along.
     */
    var landing by mutableFloatStateOf(0f)
        private set

    /** Marks where the row in hand lands. */
    val zone = DropZone(Float.VectorConverter, 0f)

    /** Whether the zone shows: once the row in hand has moved, until the finger lets it go. */
    val zoneShown: Boolean get() = session.active && session.zoneShown(this)

    /**
     * Where the first favorite is among the list's items, one past the title as it was last seen
     * laid out, or -1 before it has been. Nothing above the favorites changes while they are
     * reordered, so the place holds while the title is scrolled out of view.
     */
    private var firstRow = -1

    /** The row of the favorite [text] among [this], laid out in view, or null. */
    private fun List<LazyListItemInfo>.rowOf(text: String): LazyListItemInfo? {
        val key = rowKey(text)
        return firstOrNull { it.key == key }
    }

    /** Takes the place of the favorites from the title, where it is laid out in view. */
    private fun findFirstRow(rows: List<LazyListItemInfo>) {
        rows.firstOrNull { it.key == headerKey }?.let { firstRow = it.index + 1 }
    }

    /** Turns on the list's mode for moving the favorites. */
    fun start() {
        firstRow = -1
        findFirstRow(listState.layoutInfo.visibleItemsInfo)
        session.start()
    }

    /** Picks up the row of [text], or says it is refused, as [FavoriteDragSession.pickUp] does. */
    fun pickUp(text: String): Boolean {
        if (!session.pickUp(text, this)) return false
        haptics.step()
        return true
    }

    /** A row is drawn apart from its place in the list, so its place is where it is drawn from. */
    override fun place(key: String, order: List<String>): Rect = Rect.Zero

    override fun pickedUp(key: String, fresh: Boolean) {
        // A new row's slot is measured from where the zone stands, so it does not glide over from the
        // slot of the row before.
        if (fresh) landing = zone.stop()
    }

    /**
     * Moves the row in hand past a neighbour once its middle crosses the neighbour's, and moves the
     * zone's measure by as far as its slot moves. The neighbour's slot takes the place the row in
     * hand leaves, so it moves the other way, by as far as that.
     */
    override fun hover(key: String, at: Rect, grab: Offset, order: List<String>): HeldMove? {
        val rows = listState.layoutInfo.visibleItemsInfo
        val row = rows.rowOf(key) ?: return null
        val index = order.indexOf(key)
        findFirstRow(rows)
        // Until the list is laid out again after a move, its positions are the old ones, and would
        // move the row straight back.
        if (firstRow < 0 || row.index != firstRow + index) return null
        val middle = row.offset + row.size / 2 + at.top
        val nextKey = order.getOrNull(index + 1)
        val previousKey = order.getOrNull(index - 1)
        val next = nextKey?.let { rows.rowOf(it) }
        val previous = previousKey?.let { rows.rowOf(it) }
        val step = when {
            nextKey != null && next != null && middle > next.offset + next.size / 2 -> HeldMove(
                index + 1,
                Offset(0f, ((next.offset + next.size) - (row.offset + row.size)).toFloat()),
                mapOf(nextKey to Offset(0f, (row.offset - next.offset).toFloat())),
            )
            previousKey != null && previous != null && middle < previous.offset + previous.size / 2 -> HeldMove(
                index - 1,
                Offset(0f, (previous.offset - row.offset).toFloat()),
                mapOf(previousKey to Offset(0f, ((row.offset + row.size) - (previous.offset + previous.size)).toFloat())),
            )
            else -> return null
        }
        // The list keeps its place by its first visible row; pinned here, a moved first row does not
        // drag the list along with it.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        landing += step.shift.y
        return step
    }

    /**
     * The top and bottom of the zone, from the top of the list, in the slot of the row it marks or
     * gliding into it, or null while that row is not laid out.
     */
    fun zoneSpan(): ClosedFloatingPointRange<Float>? {
        val info = listState.layoutInfo
        val row = info.visibleItemsInfo.rowOf(session.marked ?: return null) ?: return null
        val top = info.beforeContentPadding + row.offset + (zone.drawnAt?.let { it - landing } ?: 0f)
        return top..top + row.size
    }

    override fun reach(key: String, at: Rect, order: List<String>, info: LazyListLayoutInfo, bottom: Float): HeldReach? {
        val rows = info.visibleItemsInfo
        val row = rows.rowOf(key) ?: return null
        if (order.isEmpty()) return null
        val top = row.offset + at.top
        // The held row is a favorite in view, so the first one out of view lies above and the last
        // one below.
        val first = rows.rowOf(order.first())
        val lastRow = rows.rowOf(order.last())
        return HeldReach(
            top = top,
            bottom = top + row.size,
            above = first?.let { -it.offset.toFloat() } ?: Float.POSITIVE_INFINITY,
            below = lastRow?.let { it.offset + it.size - bottom } ?: Float.POSITIVE_INFINITY,
        )
    }
}
