package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The favorites' contents in the order they are shown, which a drag moves on ahead of the history
 * until the order it reaches is kept and the history is read again.
 */
@Stable
internal class FavoriteOrder private constructor(private val keys: SnapshotStateList<String>) : List<String> by keys {
    constructor() : this(mutableStateListOf())

    /** Whether the order is [order] already. */
    fun isSameAs(order: List<String>): Boolean = keys.size == order.size && keys.indices.all { keys[it] == order[it] }

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
}

/**
 * [order] brought in line with [current], the favorites' contents as the history holds them: one
 * added meanwhile, as from a row below, joins at the top, where it would go anyway, and one removed
 * leaves. Worked out where the favorites are composed, so a change to them shows in the same frame
 * as the rest of the history.
 */
internal fun syncOrder(order: List<String>, current: List<String>): List<String> {
    val present = current.toHashSet()
    val known = order.toHashSet()
    return current.filter { it !in known } + order.filter { it in present }
}

/**
 * Where a step of the favorite in hand takes it: [to], its place in the order, and [shift], how far
 * that moves the place it is drawn from, which comes off where it is drawn, so it stays under the
 * finger. [passed] gives, by content, how far the step moves the places other favorites are drawn
 * from, in a layout that draws each favorite from its place; a favorite gliding into its place takes
 * that off where it is drawn too, so it glides on from where it is rather than jumping with its place.
 */
internal class HeldMove(val to: Int, val shift: Offset = Offset.Zero, val passed: Map<String, Offset> = emptyMap())

/**
 * One way the favorites are shown, as rows of the list or as tiles, as it moves a favorite. Where a
 * favorite is drawn is a rect in the layout's own measure: for a row, how far it is drawn from its
 * place in the list, and for a tile, where it is drawn among the tiles.
 */
internal interface FavoriteDragLayout {
    /** Where [key] is drawn in its place among [order], or null where it has none. */
    fun place(key: String, order: List<String>): Rect?

    /** [key] has been picked up, [fresh] unless it was caught gliding into its place. */
    fun pickedUp(key: String, fresh: Boolean)

    /**
     * Where [key], drawn at [at] and held [grab] from its top-left, goes among [order] for where it
     * is held, or null where it stays.
     */
    fun hover(key: String, at: Rect, grab: Offset, order: List<String>): HeldMove?

    /**
     * Where [key], drawn at [at], is and how far the favorites of [order] reach past the edges of the
     * list laid out as [info], whose bottom is [bottom].
     */
    fun reach(key: String, at: Rect, order: List<String>, info: LazyListLayoutInfo, bottom: Float): HeldReach?
}

/**
 * Moving the favorites on the history page, as rows of the list or as tiles, which each give only a
 * [FavoriteDragLayout]. It holds the order they are shown in, which the page hands it as composed
 * through [shown] and a drag moves on ahead of the history, the favorite in hand, and each favorite
 * gliding into its place once let go. Every order reached is kept through [commit]: a drag's as it
 * ends, and a move for screen readers at once. Favorites glide into their places in [scope], the
 * page's, so a favorite whose row or tiles leave the view meanwhile still lands, and each glides on
 * its own, so one picked up while another lands leaves that one to land from where it is. A gliding
 * favorite follows its place as a later drag moves it, so it lands wherever its place is by then.
 */
@Stable
internal class FavoriteDragSession(
    private val scope: CoroutineScope,
    private val haptics: Haptics,
    private val commit: (List<String>) -> Unit,
) {
    /** The favorites' contents in the order shown. */
    val order = FavoriteOrder()

    /** Whether the list's rows are in their mode for moving the favorites; tiles are dragged straight away. */
    var active by mutableStateOf(false)
        private set

    /** The favorite in hand, or null. */
    var hand by mutableStateOf<String?>(null)
        private set

    /** Whether the favorite in hand has moved far enough to be dragged rather than only held. */
    var moved by mutableStateOf(false)
        private set

    /** The favorite last picked up, whose landing place the drop zone marks, kept while the zone fades after it is set down. */
    var marked by mutableStateOf<String?>(null)
        private set

    /**
     * Where each favorite in hand or gliding into its place is drawn. The map changes only as one is
     * picked up or lands, so what reads only which favorites move is not composed again on every frame.
     */
    private val moving = mutableStateMapOf<String, MutableState<Rect>>()

    /** The glide of each favorite let go and not yet in its place. */
    private val settles = HashMap<String, Glide>()

    /** The layout the favorite in hand is moved in. */
    private var layout: FavoriteDragLayout? = null
    private var grab = Offset.Zero
    private var travel = Offset.Zero

    /** Whether the favorite in hand was caught on its way into place, so it glides home even unmoved. */
    private var caught = false

    /** The order last kept, so a drag that moved nothing keeps nothing. */
    private var kept: List<String> = emptyList()

    /** Whether the favorites follow [order] rather than the history: while the list's mode is on, or any favorite moves. */
    val engaged: Boolean get() = active || moving.isNotEmpty()

    /** Whether any favorite is in hand or gliding into its place. */
    val busy: Boolean get() = moving.isNotEmpty()

    /** Whether [key] is in hand or gliding into its place. */
    fun isMoving(key: String): Boolean = key in moving

    /** Where [key] is drawn while it is in hand or gliding into its place, in its layout's measure. */
    fun positionOf(key: String): Rect? = moving[key]?.value

    /** Whether the drop zone of [layout] shows: once the favorite in hand there has moved, until it is let go. */
    fun zoneShown(layout: FavoriteDragLayout): Boolean = hand != null && moved && this.layout === layout

    /** The favorite held and being dragged, which the list scrolls for, or null. */
    val held: String? get() = hand?.takeIf { moved }

    /** Where the favorite in hand is and how far the favorites reach past the edges of the list, whose bottom is [bottom]. */
    fun reach(info: LazyListLayoutInfo, bottom: Float): HeldReach? {
        val key = hand ?: return null
        val at = positionOf(key) ?: return null
        return layout?.reach(key, at, order, info, bottom)
    }

    /**
     * Takes [order], the favorites' contents in the order the page last composed them, which every
     * move after it starts from. A favorite drawn apart from its place with neither a finger nor a
     * glide to bring it back, as when its glide was cut off before it began, is let go.
     */
    fun shown(order: List<String>) {
        if (!this.order.isSameAs(order)) this.order.reset(order)
        moving.keys.filter { it != hand && settles[it]?.job?.isActive != true }.forEach {
            moving.remove(it)
            settles.remove(it)
        }
    }

    /** Turns on the list's mode for moving the favorites. */
    fun start() {
        if (!engaged) kept = order.toList()
        active = true
    }

    /**
     * Turns the list's mode off, keeping the order reached. Every finished move is kept already; a
     * drag whose gesture was cut off before it ended is kept here.
     */
    fun finish() {
        keep()
        active = false
    }

    /**
     * Picks up [key] in [layout], held [grab] from the top-left of where it is drawn, or says it is
     * refused: while a favorite is in hand, a second finger on another takes nothing over. One caught
     * on its way into place goes on from where it is, and any other on its way lands meanwhile.
     */
    fun pickUp(key: String, layout: FavoriteDragLayout, grab: Offset = Offset.Zero): Boolean {
        if (hand != null) return false
        val at = positionOf(key) ?: layout.place(key, order) ?: return false
        val caughtAgain = key in moving
        settles.remove(key)?.job?.cancel()
        if (!engaged) kept = order.toList()
        moving.getOrPut(key) { mutableStateOf(at) }
        this.layout = layout
        hand = key
        moved = false
        caught = caughtAgain
        travel = Offset.Zero
        this.grab = grab
        marked = key
        layout.pickedUp(key, fresh = !caughtAgain)
        return true
    }

    /** Moves the favorite in hand by [delta]; past [slop] it is being dragged. */
    fun drag(delta: Offset, slop: Float = 0f) {
        val key = hand ?: return
        val at = moving[key] ?: return
        at.value = at.value.translate(delta)
        travel += delta
        if (!moved && travel.getDistance() > slop) moved = true
        if (moved) follow(key)
    }

    /** The list scrolled the favorites by [distance] under the finger, which stays where it is. */
    fun onScrolled(distance: Float) {
        val key = hand ?: return
        val at = moving[key] ?: return
        at.value = at.value.translate(0f, distance)
        follow(key)
    }

    /** Moves the favorite in hand into the place its layout finds it held over. */
    private fun follow(key: String) {
        val at = moving[key] ?: return
        val step = layout?.hover(key, at.value, grab, order) ?: return
        if (!order.move(key, step.to)) return
        at.value = at.value.translate(-step.shift)
        step.passed.forEach { (other, shift) ->
            val glide = settles[other] ?: return@forEach
            glide.moved += shift
            moving[other]?.let { it.value = it.value.translate(-shift) }
        }
        haptics.step()
    }

    /**
     * Lets go of [key], if it is in hand. One only held stays where it is; one dragged, or caught on
     * its way, has the order reached kept at once, so leaving the page meanwhile loses nothing, and
     * glides into its place on [spec].
     */
    fun drop(key: String, spec: FiniteAnimationSpec<Rect>) {
        if (hand != key) return
        hand = null
        val at = moving[key] ?: return
        if (!moved && !caught) {
            moving.remove(key)
            return
        }
        keep()
        val layout = this.layout
        val glide = Glide()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (layout != null) settle(key, at, layout, glide, spec)
            } finally {
                // A favorite caught again meanwhile belongs to the finger or a later glide, which sets it down.
                if (settles[key] === glide) {
                    settles.remove(key)
                    moving.remove(key)
                }
            }
        }
        glide.job = job
        settles[key] = glide
        job.start()
    }

    /**
     * Moves [key], drawn at [at], into its place in [layout] on [spec], one frame at a time. The place
     * is asked for again on every frame, so as a later drag rearranges the favorites, the favorite
     * glides on toward wherever its place has moved, on the same spring and at the speed it has. The
     * spring runs in the measure the place was in when it was let go, which [glide] says how far the
     * place has moved from since, so a place that moves under the favorite does not move it.
     */
    private suspend fun settle(key: String, at: MutableState<Rect>, layout: FavoriteDragLayout, glide: Glide, spec: FiniteAnimationSpec<Rect>) {
        val follower = SpringFollower(Animatable(at.value, Rect.VectorConverter))
        follower.animateWith(spec)
        do {
            val home = layout.place(key, order) ?: return
            follower.aim(home.translate(glide.moved))
            val going = follower.step(withFrameNanos { it })
            at.value = follower.value.value.translate(-glide.moved)
        } while (going)
    }

    /**
     * Moves [key] [by] places in the order and keeps the new order at once, for screen readers, which
     * move a favorite a place at a time with no drop to wait for.
     */
    fun move(key: String, by: Int) {
        if (!order.move(key, order.indexOf(key) + by)) return
        haptics.step()
        kept = order.toList()
        commit(kept)
    }

    /**
     * A favorite let go and gliding into its place: its [job], and how far the place it is drawn from
     * has [moved] since it was let go, in a layout that draws each favorite from its place.
     */
    private class Glide {
        var job: Job? = null
        var moved = Offset.Zero
    }

    /** Keeps the order reached, unless it is kept already. */
    private fun keep() {
        val reached = order.toList()
        if (reached == kept) return
        kept = reached
        commit(reached)
    }
}
