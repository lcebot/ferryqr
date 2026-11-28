@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.history.HistoryEntry
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * The favorites' contents in their order while one is being moved, before the order is kept. The
 * list's rows and the tiles each move favorites through one, so both move them the same way.
 */
@Stable
internal class FavoriteOrder private constructor(private val keys: SnapshotStateList<String>) : List<String> by keys {
    constructor() : this(mutableStateListOf())

    fun reset(order: List<String>) {
        keys.clear()
        keys.addAll(order)
    }

    /** Moves [key] to [to]; false when it is not there, [to] is outside the order, or it is already there. */
    fun move(key: String, to: Int): Boolean {
        val from = keys.indexOf(key)
        if (from < 0 || to !in keys.indices || to == from) return false
        keys.add(to, keys.removeAt(from))
        return true
    }

    /** Brings the order in line with [current]: one added joins at the top, where it would go anyway, and one removed leaves. */
    fun sync(current: List<String>) {
        keys.retainAll(current.toSet())
        keys.addAll(0, current.filter { it !in keys })
    }
}

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
 * Where an order the favorites were moved into is kept. The list's rows and the tiles both keep
 * theirs through one, so every way of moving favorites ends in the same place.
 */
internal fun interface FavoriteOrderKeeper {
    /** Keeps [order], the contents of the favorites in their new order. */
    fun commit(order: List<String>)
}

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
        // Stepped as the springs are, so a pause, as while the page is hidden, does not scroll the
        // list by all of it at once.
        val frames = FrameSteps()
        while (true) {
            val seconds = frames.elapsed(withFrameNanos { it }) / 1_000_000_000f
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
 * The favorites reordered as rows of the list, each [rowKey] in the list, right below the list item
 * [headerKey], their title. While [active], the favorites follow [order], their contents in their
 * new order, which [keeper] keeps as each drag that moved a row ends, as the tiles keep theirs on
 * each drop. The row being dragged is [dragging], drawn [dragOffset] from its place, and while
 * [settling], the one gliding into its place after the finger lifted. It sets its dragged look down
 * as the finger lifts and stays above its neighbours only until it lands, so letting go answers at
 * once. Its slot, where it will land, shows the [zone] the tiles show for a tile in hand, once the
 * row has [moved], and the zone fades as the row settles into it.
 */
@Stable
internal class ListReorderState(
    private val listState: LazyListState,
    private val haptics: Haptics,
    private val scope: CoroutineScope,
    private val rowKey: (String) -> Any,
    private val headerKey: Any,
    keeper: FavoriteOrderKeeper,
) : FavoritesReorder, FavoriteOrderKeeper by keeper {
    var active by mutableStateOf(false)
        private set
    val order = FavoriteOrder()
    var dragging by mutableStateOf<String?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set
    var settling by mutableStateOf<Job?>(null)
        private set

    /** Whether the row in hand has moved since it was picked up. */
    var moved by mutableStateOf(false)
        private set

    /**
     * Where the slot of the row in hand is, in a measure of its own that moves by the distance its
     * slot moves each time the order changes, which the zone glides along.
     */
    var landing by mutableFloatStateOf(0f)
        private set

    /** Marks where the row in hand lands. */
    val zone = DropZone(Float.VectorConverter, 0f)

    /** The row whose slot the zone marks, kept while the zone fades after the row is set down. */
    private var marked by mutableStateOf<String?>(null)

    /** Whether the zone shows: once the row in hand has moved, until the finger lets it go. */
    val zoneShown: Boolean get() = active && dragging != null && settling == null && moved

    /** The order last kept, so a drag that moved nothing keeps nothing. */
    private var kept: List<String> = emptyList()

    override val held: Any? get() = dragging.takeIf { active && settling == null }

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

    /** Starts reordering [favorites] as they are. */
    fun start(favorites: List<HistoryEntry>) {
        order.reset(favorites.map { it.text })
        kept = order.toList()
        firstRow = -1
        findFirstRow(listState.layoutInfo.visibleItemsInfo)
        active = true
    }

    /**
     * Ends reordering, keeping the order reached. Every finished move is kept already; a drag whose
     * gesture was cut off before it ended is kept here.
     */
    fun finish() {
        keep()
        active = false
    }

    /**
     * Brings the order in line with [favorites] as they change meanwhile: one added, from a row
     * below, joins at the top, where it would go anyway, and one removed leaves.
     */
    fun sync(favorites: List<HistoryEntry>) {
        if (!active) return
        order.sync(favorites.map { it.text })
    }

    /**
     * Picks up [text], or says it is refused: while a row is held, a second finger on another row
     * takes nothing over.
     */
    fun startDrag(text: String): Boolean {
        if (dragging != null && settling == null && dragging != text) return false
        settling?.cancel()
        settling = null
        // Caught again on its way into place, the row goes on from where it is.
        if (dragging != text) {
            dragOffset = 0f
            moved = false
            // A new row's slot is measured from where the zone stands, so it does not glide over
            // from the slot of the row before.
            landing = zone.stop()
            marked = text
        }
        dragging = text
        haptics.step()
        return true
    }

    fun drag(amount: Float) {
        dragOffset += amount
        if (amount != 0f) moved = true
        follow()
    }

    /**
     * Keeps the order the drag moved the row into, at once, so leaving the page without Done loses
     * nothing, then glides the row from where the finger left it into its place on [spec] and sets
     * it down.
     */
    fun endDrag(spec: FiniteAnimationSpec<Float>) {
        keep()
        settling = scope.launch {
            animate(dragOffset, 0f, animationSpec = spec) { value, _ -> dragOffset = value }
            dragging = null
            settling = null
        }
    }

    /**
     * Moves [text] to [to] in the order, for screen readers, which move a row at a time. Each move
     * is kept at once, as a drag is once it ends.
     */
    fun move(text: String, to: Int) {
        if (!order.move(text, to)) return
        haptics.step()
        keep()
    }

    /** Keeps the order reached, unless it is kept already. */
    private fun keep() {
        val reached = order.toList()
        if (reached == kept) return
        kept = reached
        commit(reached)
    }

    /**
     * Moves the row being dragged past a neighbour once its middle crosses the neighbour's, and
     * keeps it under the finger by taking the distance it moved off the drag.
     */
    private fun follow() {
        val text = dragging ?: return
        val rows = listState.layoutInfo.visibleItemsInfo
        val row = rows.rowOf(text) ?: return
        val index = order.indexOf(text)
        findFirstRow(rows)
        // Until the list is laid out again after a move, its positions are the old ones, and would
        // move the row straight back.
        if (firstRow < 0 || row.index != firstRow + index) return
        val middle = row.offset + row.size / 2 + dragOffset
        val next = order.getOrNull(index + 1)?.let { rows.rowOf(it) }
        val previous = order.getOrNull(index - 1)?.let { rows.rowOf(it) }
        // The list keeps its place by its first visible row; pinned here, a moved first row does not
        // drag the list along with it.
        fun pin() = listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        when {
            next != null && middle > next.offset + next.size / 2 -> {
                pin()
                order.move(text, index + 1)
                val shift = (next.offset + next.size) - (row.offset + row.size)
                dragOffset -= shift
                landing += shift
                haptics.step()
            }
            previous != null && middle < previous.offset + previous.size / 2 -> {
                pin()
                order.move(text, index - 1)
                val shift = row.offset - previous.offset
                dragOffset += shift
                landing -= shift
                haptics.step()
            }
        }
    }

    /**
     * The top and bottom of the zone, from the top of the list, in the slot of the row it marks or
     * gliding into it, or null while that row is not laid out.
     */
    fun zoneSpan(): ClosedFloatingPointRange<Float>? {
        val info = listState.layoutInfo
        val row = info.visibleItemsInfo.rowOf(marked ?: return null) ?: return null
        val top = info.beforeContentPadding + row.offset + (zone.drawnAt?.let { it - landing } ?: 0f)
        return top..top + row.size
    }

    override fun reach(info: LazyListLayoutInfo, bottom: Float): HeldReach? {
        val text = dragging ?: return null
        val rows = info.visibleItemsInfo
        val row = rows.rowOf(text) ?: return null
        if (order.isEmpty()) return null
        val top = row.offset + dragOffset
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

    override fun onScrolled(distance: Float) {
        dragOffset += distance
        follow()
    }
}
