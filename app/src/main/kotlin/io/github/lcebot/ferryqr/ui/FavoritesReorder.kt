package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.history.HistoryEntry
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

/** A way of reordering the favorites in the history list, which scrolls for the favorite held. */
internal interface FavoritesReorder {
    /** The favorite held and being dragged, or null. */
    val held: Any?

    /** Where the held favorite is and how far the favorites reach past the edges of the list, whose bottom is [bottom]. */
    fun reach(info: LazyListLayoutInfo, bottom: Float): HeldReach?

    /** The list scrolled the favorites by [distance] under the finger, which stays where it is. */
    fun onScrolled(distance: Float)
}

/**
 * While a favorite of [reorder] is held near the top or bottom of the visible list, and favorites
 * reach past that edge, the list scrolls toward them. Only the list's content moves: the held row
 * or tile stays under the finger, taking each scroll off its drag, the top bar keeps its size since
 * the scroll does not pass through it, and the list goes no further than the first or last favorite
 * plus [REORDER_EDGE]. It stops as the finger lifts. The list is drawn behind the navigation bar,
 * [bottomInset] tall, so its edge for scrolling is the top of the bar.
 */
@Composable
internal fun ReorderAutoScroll(listState: LazyListState, reorder: FavoritesReorder, bottomInset: Dp) {
    val density = LocalDensity.current
    val edge = with(density) { REORDER_EDGE.toPx() }
    val fastest = with(density) { REORDER_SCROLL_SPEED.toPx() }
    val barHeight = with(density) { bottomInset.toPx() }
    val held = reorder.held
    LaunchedEffect(held, reorder, edge, fastest, barHeight) {
        if (held == null) return@LaunchedEffect
        // The way the list has been scrolling, which it keeps up until the margin even once the
        // favorites no longer reach past the edge.
        var going = 0
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val seconds = if (last == 0L) 0f else (now - last) / 1_000_000_000f
            last = now
            val info = listState.layoutInfo
            // Offset 0 is the top of the visible list, just below the top bar; the bottom is where
            // the navigation bar starts covering the list, which runs on behind it.
            val bottom = info.viewportEndOffset - barHeight
            val reach = reorder.reach(info, bottom)
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
            reorder.onScrolled(listState.scrollBy(way * speed * seconds))
        }
    }
}

/**
 * The favorites reordered as rows of the list, each [rowKey] in the list. While [active], the
 * favorites follow [order], their contents in their new order, which is kept when done. The row
 * being dragged is [dragging], drawn [dragOffset] from its place, and while [settling], the one
 * gliding into its place after the finger lifted. It sets its dragged look down as the finger
 * lifts and stays above its neighbours only until it lands, so letting go answers at once.
 */
@Stable
internal class ListReorderState(
    private val listState: LazyListState,
    private val haptics: Haptics,
    private val scope: CoroutineScope,
    private val rowKey: (String) -> Any,
) : FavoritesReorder {
    var active by mutableStateOf(false)
        private set
    val order = mutableStateListOf<String>()
    var dragging by mutableStateOf<String?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set
    var settling by mutableStateOf<Job?>(null)
        private set

    override val held: Any? get() = dragging.takeIf { active && settling == null }

    /** Starts reordering [favorites] as they are. */
    fun start(favorites: List<HistoryEntry>) {
        order.clear()
        order.addAll(favorites.map { it.text })
        active = true
    }

    /** Ends reordering and gives the order reached. */
    fun finish(): List<String> {
        active = false
        return order.toList()
    }

    /**
     * Brings the order in line with [favorites] as they change meanwhile: one added, from a row
     * below, joins at the top, where it would go anyway, and one removed leaves.
     */
    fun sync(favorites: List<HistoryEntry>) {
        if (!active) return
        val current = favorites.map { it.text }
        order.retainAll(current.toSet())
        order.addAll(0, current.filter { it !in order })
    }

    fun startDrag(text: String) {
        settling?.cancel()
        settling = null
        // Caught again on its way into place, the row goes on from where it is.
        if (dragging != text) dragOffset = 0f
        dragging = text
        haptics.step()
    }

    fun drag(amount: Float) {
        dragOffset += amount
        follow()
    }

    /** Glides the row from where the finger left it into its place on [spec], then sets it down. */
    fun endDrag(spec: FiniteAnimationSpec<Float>) {
        settling = scope.launch {
            animate(dragOffset, 0f, animationSpec = spec) { value, _ -> dragOffset = value }
            dragging = null
            settling = null
        }
    }

    /** Moves [text] to [to] in the order, for screen readers, which move a row at a time. */
    fun move(text: String, to: Int) {
        val from = order.indexOf(text)
        if (from < 0 || to !in order.indices) return
        order.add(to, order.removeAt(from))
        haptics.step()
    }

    /**
     * Moves the row being dragged past a neighbour once its middle crosses the neighbour's, and
     * keeps it under the finger by taking the distance it moved off the drag.
     */
    private fun follow() {
        val text = dragging ?: return
        val rows = listState.layoutInfo.visibleItemsInfo
        val row = rows.firstOrNull { it.key == rowKey(text) } ?: return
        val index = order.indexOf(text)
        // Until the list is laid out again after a move, its positions are the old ones, and would
        // move the row straight back; the favorites start below their header.
        if (row.index != index + 1) return
        val middle = row.offset + row.size / 2 + dragOffset
        val next = order.getOrNull(index + 1)?.let { key -> rows.firstOrNull { it.key == rowKey(key) } }
        val previous = order.getOrNull(index - 1)?.let { key -> rows.firstOrNull { it.key == rowKey(key) } }
        // The list keeps its place by its first visible row; pinned here, a moved first row does not
        // drag the list along with it.
        fun pin() = listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        when {
            next != null && middle > next.offset + next.size / 2 -> {
                pin()
                order.add(index + 1, order.removeAt(index))
                dragOffset -= (next.offset + next.size) - (row.offset + row.size)
                haptics.step()
            }
            previous != null && middle < previous.offset + previous.size / 2 -> {
                pin()
                order.add(index - 1, order.removeAt(index))
                dragOffset += row.offset - previous.offset
                haptics.step()
            }
        }
    }

    override fun reach(info: LazyListLayoutInfo, bottom: Float): HeldReach? {
        val text = dragging ?: return null
        val rows = info.visibleItemsInfo
        val row = rows.firstOrNull { it.key == rowKey(text) } ?: return null
        if (order.isEmpty()) return null
        val top = row.offset + dragOffset
        // The held row is a favorite in view, so the first one out of view lies above and the last
        // one below.
        val first = rows.firstOrNull { it.key == rowKey(order.first()) }
        val lastRow = rows.firstOrNull { it.key == rowKey(order.last()) }
        return HeldReach(
            top = top,
            bottom = top + row.size,
            above = first?.let { -it.offset.toFloat() } ?: Float.POSITIVE_INFINITY,
            below = lastRow?.let { it.offset + it.size - bottom } ?: Float.POSITIVE_INFINITY,
        )
    }

    override fun onScrolled(distance: Float) {
        dragOffset += distance
        follow()
    }
}
