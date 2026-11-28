/*
 * The magnetic swipe of Android 16's notifications, after SystemUI's
 * MagneticNotificationRowManagerImpl, NotificationTargetsHelper and SwipeHelper.
 * Copyright (C) 2025 The Android Open Source Project. Licensed under the Apache License, Version 2.0:
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.lcebot.ferryqr.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.history.HistoryEntry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Finger travel at which a pulled row comes loose from its neighbours and letting go acts at any
 * speed. Notifications come loose at 72dp; a little more keeps a swipe that only checks what is
 * behind a row from acting.
 */
internal val DETACH_AT = 80.dp

/**
 * Finger travel back under which a loose row rejoins its neighbours, as far below [DETACH_AT] as a
 * notification's 56dp is below its 72dp, so a finger resting near the point does not switch back
 * and forth.
 */
internal val ATTACH_AT = 64.dp

/** A release this fast decides a swipe of any length, as it dismisses a notification. */
internal val DISMISS_VELOCITY = 500.dp

/** A loose row let go starts flying off at least this fast, as a notification does. */
internal val MIN_DISMISS_VELOCITY = 2000.dp

/**
 * How many widths past its place a row flying off is aimed, so it crosses the edge still moving. A
 * quarter past leaves in about a tenth of a second at about the speed it was let go, keeping the
 * pace of the spring without waiting for it to come to rest.
 */
internal const val FLY_PAST = 1.25f

/**
 * What an entry's swipe acts with, as the entry was last composed: the [entry], at [index] of
 * [group], whether right is toward the start, the [list] and [magnet] it pulls and leans with, the
 * [haptics] it plays, its thresholds in pixels, the springs it settles on, keeps up with the finger
 * on and fades what it shows behind the entry on, and what letting it go does. [flyTo] gives how
 * far it flies to leave toward a side, and [leavesOnFavorite] keeps it gone once a favorite swipe
 * acts, as a favorite tile that leaves the favorites does.
 */
internal class SwipeSetup(
    val entry: HistoryEntry,
    val group: Any,
    val index: Int,
    val rtl: Boolean,
    val list: MagneticList,
    val magnet: Magnet,
    val haptics: SwipeHaptics,
    val detachAt: Float,
    val attachAt: Float,
    val dismissVelocity: Float,
    val minDismissVelocity: Float,
    val settle: AnimationSpec<Float>,
    val quick: AnimationSpec<Float>,
    val fade: AnimationSpec<Float>,
    val flyTo: (side: Float) -> Float,
    val onDelete: (HistoryEntry) -> Long,
    val onFavorite: (HistoryEntry, Boolean) -> Unit,
    val leavesOnFavorite: Boolean,
)

/**
 * One entry's swipe, as a notification's: [shift] is where it is drawn apart from the finger,
 * following at half its pace, pulling the entries around it, until it comes loose at [DETACH_AT]
 * and follows the finger itself. A swipe toward the start deletes the entry, and one toward the
 * end makes it a favorite or stops it being one. It outlives the composition of its entry, as a
 * swipe let go finishes, or waits for undo, in the list's scope, so it acts with the [SwipeSetup]
 * its entry was last composed with.
 */
@Stable
internal class EntrySwipe {
    val shift = SpringFollower(Animatable(0f))
    private val shifts = listOf(shift)

    /** Steps [shift] while it follows the finger, so each move of the finger only sends it on. */
    private val loop = FollowerLoop { shifts }

    var pull by mutableStateOf<Pull?>(null)
        private set

    /** Let go to act, until the entry is back in place or gone. */
    var acting by mutableStateOf(false)
        private set

    /**
     * How much of what the swipe shows behind the entry is in view: it fades out once an action
     * has taken the entry away, and fades in as undoing brings the entry flying back over it.
     */
    val backdrop = Animatable(1f)

    /**
     * Whether undo has brought the entry back and it waits its turn to fly back or is on its way, so
     * an entry composed again meanwhile does not start its return over.
     */
    internal var returning = false

    /**
     * What a favorite swipe will do, fixed when it starts, so an entry sliding back after the
     * change still shows the change it made rather than the opposite one.
     */
    var becomesFavorite by mutableStateOf(true)
        internal set

    /** Whether right is toward the start, as the entry was last composed. */
    var rtl by mutableStateOf(false)
        private set

    private var release: Job? = null
    private val tracker = VelocityTracker1D(isDataDifferential = true)
    private lateinit var setup: SwipeSetup

    /** Takes the finger's horizontal travel, for [entrySwipe] to hand it. */
    val draggable: DraggableState = DraggableState { delta -> move(delta) }

    val direction by derivedStateOf {
        val x = shift.value.value
        when {
            x == 0f -> SwipeToDismissBoxValue.Settled
            (x > 0f) != rtl -> SwipeToDismissBoxValue.StartToEnd
            else -> SwipeToDismissBoxValue.EndToStart
        }
    }

    val armed: Boolean get() = pull?.phase == PullPhase.Detached || acting

    /** Takes what the swipe acts with, from the composition of its entry once that is kept. */
    fun configure(setup: SwipeSetup) {
        this.setup = setup
        // A row deleted above one still held moves it up a place, and its neighbours with it.
        pull?.index = setup.index
        rtl = setup.rtl
    }

    /** Takes the entry up under a finger, which an entry caught while it moves is swiped on from where it is. */
    suspend fun start() {
        val setup = this.setup
        val list = setup.list
        val magnet = setup.magnet
        release?.cancel()
        list.scope.launch { backdrop.snapTo(1f) }
        acting = false
        shift.held = false
        shift.value.updateBounds(null, null)
        tracker.resetTracking()
        // Taken up from where it shows, so it stays under the finger: what it leans with the pulls
        // of other fingers becomes its own shift at once, and its lean is held at rest until it is
        // let go, since a pulled entry follows only its own finger.
        magnet.lean.held = true
        val leaning = magnet.lean.value.value
        val moving = shift.value.velocity
        val from = shift.value.value + leaning
        // The shift first, which may wait for a release still running to stop, then the lean
        // straight after it, so no frame shows the one moved without the other.
        if (leaning != 0f) shift.value.snapTo(from)
        magnet.lean.snapTo(0f)
        if (from != 0f) shift.animateWith(setup.quick, moving) else shift.stopAnimating()
        // An attached entry shows a share of the finger's travel and a loose one all of it, so the
        // finger's travel that shows the entry where it is depends on which it is taken as: attached
        // while it shows less than an attached entry can, loose beyond that.
        val loose = abs(from) >= PULLED_SHARE * setup.detachAt
        val swiped = Pull(setup.entry.text, setup.group, setup.index, rtl).apply {
            finger = if (loose) from else from / PULLED_SHARE
            if (loose) phase = PullPhase.Detached
        }
        // The pull of a release caught before it settled gives way to the new one at once, so the
        // entry never pulls its neighbours twice.
        pull?.let { list.pulls.remove(it) }
        pull = swiped
        list.pulls.add(swiped)
    }

    /** Moves the finger holding the entry on by [delta], coming loose or rejoining as it crosses the thresholds. */
    private fun move(delta: Float) {
        val swiped = pull ?: return
        tracker.addDataPoint(SystemClock.uptimeMillis(), delta)
        swiped.finger += delta
        val travel = abs(swiped.finger)
        when (swiped.phase) {
            PullPhase.Pulling ->
                if (travel >= setup.detachAt) {
                    swiped.phase = PullPhase.Detached
                    setup.haptics.threshold()
                } else {
                    setup.haptics.pull(PULLED_SHARE * travel / setup.detachAt)
                }
            PullPhase.Detached ->
                if (travel <= setup.attachAt) {
                    swiped.phase = PullPhase.Pulling
                    setup.haptics.threshold()
                }
            PullPhase.Released -> Unit
        }
    }

    /**
     * Moves [shift] after the finger of [swiped] while it holds the entry, thrown on at the finger's
     * speed as the entry comes loose and back as it rejoins.
     */
    suspend fun follow(swiped: Pull) {
        coroutineScope {
            launch { loop.run() }
            var phase = swiped.phase
            snapshotFlow { swiped.phase to swiped.finger }.collect { (now, finger) ->
                // Let go, the release moves the entry, which holds the shift meanwhile.
                if (now == PullPhase.Released) return@collect
                if (now != phase) {
                    // Coming loose throws the entry on at the finger's speed; rejoining throws it back.
                    val speed = abs(tracker.calculateVelocity()) * sign(finger)
                    if (now == PullPhase.Detached) shift.animateWith(setup.quick, speed) else shift.animateWith(setup.quick, -speed)
                    phase = now
                }
                loop.aim(shift, if (now == PullPhase.Detached) finger else PULLED_SHARE * finger)
            }
        }
    }

    /**
     * Lets the entry go at [velocity]. Let go to act, it flies off toward the side it was swiped to,
     * aimed past the distance [SwipeSetup.flyTo] gives for that side and stopped there, so the action
     * follows as soon as it has left. A favorite swipe slides it back once done, unless
     * [SwipeSetup.leavesOnFavorite]. A delete swipe leaves the swipe with the list's departures,
     * under the undo session the deletion gives, while undoing can bring the entry back.
     */
    fun stop(velocity: Float) {
        val swiped = pull ?: return
        val list = setup.list
        val magnet = setup.magnet
        val detached = swiped.phase == PullPhase.Detached
        val side = sign(swiped.finger)
        val fast = abs(velocity) >= setup.dismissVelocity
        // A loose entry acts unless flung back; an attached one only when flung on.
        val acts = side != 0f && if (detached) !(fast && sign(velocity) != side) else fast && sign(velocity) == side
        swiped.releaseVelocity = velocity
        swiped.dismissing = acts
        swiped.phase = PullPhase.Released
        // From here the release moves the entry, and the spring that followed the finger must not step it.
        shift.held = true
        release = list.scope.launch {
            try {
                if (acts) {
                    if (!detached) setup.haptics.threshold()
                    acting = true
                    val speed = if (detached) max(setup.minDismissVelocity, abs(velocity)) else abs(velocity)
                    // Aimed past where it has left and stopped there, so the action follows at once
                    // rather than once the spring has come to rest there.
                    val edge = setup.flyTo(side)
                    if (edge > 0f) shift.value.updateBounds(-edge, edge)
                    shift.value.animateTo(side * edge * FLY_PAST, setup.settle, side * speed)
                    // Gone, the entry lets go of its neighbours as it acts, so they settle back at
                    // once rather than once what the swipe showed behind it has faded, and the rows
                    // the action moves are not taken for its neighbours meanwhile.
                    list.pulls.remove(swiped)
                    val entry = setup.entry
                    if ((side > 0f) != rtl) {
                        setup.onFavorite(entry, !entry.favorite)
                        if (!setup.leavesOnFavorite) {
                            shift.value.animateTo(0f, setup.settle)
                            acting = false
                        } else {
                            backdrop.animateTo(0f, setup.fade)
                        }
                    } else {
                        list.departures.depart(entry.text, this@EntrySwipe, setup.onDelete(entry))
                        backdrop.animateTo(0f, setup.fade)
                    }
                } else {
                    shift.value.animateTo(0f, setup.settle, velocity)
                }
            } finally {
                list.pulls.remove(swiped)
                if (pull === swiped) {
                    pull = null
                    // Let go, it leans with the other fingers again, from rest.
                    magnet.lean.held = false
                }
            }
        }
    }

    /** Brings an entry that flew off back into its place, as when its deletion is undone. */
    fun comeBack() {
        val list = setup.list
        release?.cancel()
        returning = true
        release = list.scope.launch {
            try {
                launch {
                    backdrop.snapTo(0f)
                    backdrop.animateTo(1f, setup.fade)
                }
                shift.held = true
                shift.value.updateBounds(null, null)
                shift.value.animateTo(0f, setup.settle)
                acting = false
                shift.held = false
            } finally {
                returning = false
                list.departures.landed(setup.entry.text, this@EntrySwipe)
            }
        }
    }
}

/**
 * The swipe of [entry], at [index] of [group] in [list], leaning [magnet]'s neighbours. Let go to
 * act, it flies off toward the side it was swiped to as far as [flyTo] gives for that side, as
 * [EntrySwipe.stop] tells. An entry composed again after it left takes the swipe it left with up
 * again as its [swipe]. Brought back by undo, the entry flies back into its place in its turn, once
 * it is [listed] again rather than only composed while it leaves.
 */
@Composable
internal fun rememberEntrySwipe(
    entry: HistoryEntry,
    group: Any,
    index: Int,
    list: MagneticList,
    magnet: Magnet,
    flyTo: (side: Float) -> Float,
    onDelete: (HistoryEntry) -> Long,
    onFavorite: (HistoryEntry, Boolean) -> Unit,
    leavesOnFavorite: Boolean = false,
    listed: Boolean = true,
    swipe: EntrySwipe = remember { list.departures.returning(entry.text) ?: EntrySwipe() },
): EntrySwipe {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val swipeHaptics = remember(list) { list.haptics(context, haptics) }
    val density = LocalDensity.current
    val motion = MaterialTheme.motionScheme
    val setup = SwipeSetup(
        entry = entry,
        group = group,
        index = index,
        rtl = LocalLayoutDirection.current == LayoutDirection.Rtl,
        list = list,
        magnet = magnet,
        haptics = swipeHaptics,
        detachAt = with(density) { DETACH_AT.toPx() },
        attachAt = with(density) { ATTACH_AT.toPx() },
        dismissVelocity = with(density) { DISMISS_VELOCITY.toPx() },
        minDismissVelocity = with(density) { MIN_DISMISS_VELOCITY.toPx() },
        settle = motion.defaultSpatialSpec<Float>(),
        // Coming loose, rejoining and being caught are short moves to keep up with the finger.
        quick = motion.fastSpatialSpec<Float>(),
        fade = motion.defaultEffectsSpec<Float>(),
        flyTo = flyTo,
        onDelete = onDelete,
        onFavorite = onFavorite,
        leavesOnFavorite = leavesOnFavorite,
    )
    // Handed to the swipe once the composition it comes from is kept, never while it is composed.
    SideEffect { swipe.configure(setup) }

    LaunchedEffect(swipe.pull) {
        swipe.pull?.let { swipe.follow(it) }
    }
    DisposableEffect(list) {
        onDispose {
            swipe.pull?.let { list.pulls.remove(it) }
            magnet.lean.held = false
        }
    }
    // An entry swiped away and brought back by undo flies back from where it went, whether it is
    // composed again for that or was still composed, as a row still fading out is.
    val back = listed && list.departures.returning(entry.text) === swipe
    LaunchedEffect(back) {
        if (back) list.departures.returnInTurn(entry.text, swipe)
    }
    LaunchedEffect(swipe.direction, entry.favorite) {
        if (swipe.direction == SwipeToDismissBoxValue.Settled) swipe.becomesFavorite = !entry.favorite
    }
    return swipe
}

/** Lets [swipe] follow horizontal drags; [enabled] is false where the entry takes no swipes. */
internal fun Modifier.entrySwipe(swipe: EntrySwipe, enabled: Boolean = true): Modifier = draggable(
    swipe.draggable,
    Orientation.Horizontal,
    enabled = enabled,
    onDragStarted = { swipe.start() },
    onDragStopped = { swipe.stop(it) },
)
