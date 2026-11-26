/*
 * The magnetic swipe of Android 16's notifications, after SystemUI's
 * MagneticNotificationRowManagerImpl, NotificationTargetsHelper and SwipeHelper.
 * Copyright (C) 2025 The Android Open Source Project. Licensed under the Apache License, Version 2.0:
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VectorizedAnimationSpec
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
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

/** The share of the finger's travel the pulled row follows while still attached. */
internal const val PULLED_SHARE = 0.5f

/**
 * The shares the rows two and one away follow, on either side of [PULLED_SHARE]: half as much
 * again as a notification's 0.04 and 0.12, so the neighbours visibly cling to a row before it
 * comes loose.
 */
private val TRANSLATION_MULTIPLIERS = floatArrayOf(0.06f, 0.18f, PULLED_SHARE, 0.18f, 0.06f)

/**
 * How strongly each row, from three above the pulled one to three below, rounds as the pull
 * grows. The rows three away are only rounded, on the side facing it, and do not move.
 */
private val ROUNDNESS_MULTIPLIERS = floatArrayOf(0.5f, 0.7f, 0.9f, 1f, 0.9f, 0.7f, 0.5f)
private const val MAX_PRE_DETACH_ROUNDNESS = 0.8f

internal enum class PullPhase { Pulling, Detached, Released }

/**
 * A row being swiped, as the rows around it see it. [finger] is the finger's travel in pixels, right
 * positive; [rtl] says whether right is toward the start.
 */
@Stable
internal class Pull(val key: Any, val group: Any, index: Int, val rtl: Boolean = false) {
    var index by mutableIntStateOf(index)
    var finger by mutableFloatStateOf(0f)
    var phase by mutableStateOf(PullPhase.Pulling)
    var dismissing by mutableStateOf(false)
    var releaseVelocity = 0f
}

/**
 * The one row of a list being swiped, if any. A row let go finishes in [scope], the list's, since a
 * row scrolled out of view leaves composition and would take its own coroutines with it, dropping
 * a delete or favorite already decided.
 */
@Stable
internal class MagneticList(val scope: CoroutineScope) {
    var pull by mutableStateOf<Pull?>(null)

    /**
     * The swipes of rows swiped away and deleted, by content, kept while undoing can bring them
     * back: a row brought back takes up its swipe again and flies back into its place from where it
     * went, as a tile does.
     */
    val departed = HashMap<String, EntrySwipe>()
}

/**
 * A value set directly while nothing animates it, and retargeted while an animation carries it, as
 * a notification moves while a magnetic spring runs, the way SpringAnimation.animateToFinalPosition
 * moves the end of a running spring.
 *
 * The animation is stepped one frame at a time from wherever it has got to, with the velocity it
 * has, rather than started anew on each target: an animation started anew on every frame of a drag
 * would never leave its first frame, which only records when it began.
 */
internal class Follower {
    val value = Animatable(0f)
    private var spec: VectorizedAnimationSpec<AnimationVector1D>? = null
    private var velocity = 0f
    private var lastFrame = 0L

    /**
     * While set, [follow] leaves the value alone, for another animation to move it: a step of a
     * running spring would otherwise cancel that animation.
     */
    var held = false

    /** The next targets are reached through [spec], from [velocity] when given or else the current one. */
    fun animateWith(spec: AnimationSpec<Float>, velocity: Float? = null) {
        this.spec = spec.vectorize(Float.VectorConverter)
        if (velocity != null) this.velocity = velocity
        lastFrame = 0L
    }

    /** Targets after this are set directly. */
    fun stopAnimating() {
        spec = null
        velocity = 0f
        lastFrame = 0L
    }

    /** Moves to [target]: at once while nothing animates the value, else frame by frame until it arrives. */
    suspend fun follow(target: Float) {
        while (!held) {
            val running = spec
            if (running == null) {
                value.snapTo(target)
                return
            }
            val now = withFrameNanos { it }
            val elapsed = if (lastFrame == 0L) 0L else now - lastFrame
            lastFrame = now
            if (elapsed <= 0L) continue
            val from = AnimationVector1D(value.value)
            val to = AnimationVector1D(target)
            val speed = AnimationVector1D(velocity)
            if (running.getDurationNanos(from, to, speed) <= elapsed) {
                stopAnimating()
                value.snapTo(target)
                return
            }
            val next = running.getValueFromNanos(elapsed, from, to, speed).value
            val nextVelocity = running.getVelocityFromNanos(elapsed, from, to, speed).value
            value.snapTo(next)
            velocity = nextVelocity
        }
    }
}

/**
 * How an entry moves and rounds for the pull of another entry in its group, or of itself: [lean] is
 * how far it moves with the finger, [backdrop] how far what a swipe shows behind it moves, and each
 * corner's value from 0 to 1 how far it rounds toward the large corner.
 */
@Stable
internal class Magnet {
    val lean = Follower()
    val backdrop = Follower()
    val topStart = Follower()
    val topEnd = Follower()
    val bottomStart = Follower()
    val bottomEnd = Follower()
}

/** How many steps of half a dp a corner takes between [INNER_CORNER] and [OUTER_CORNER]. */
private val ROUNDING_STEPS = (OUTER_CORNER - INNER_CORNER) / 0.5.dp

/**
 * How far [corner] rounds, from 0 to 1, in steps that move the corner by half a dp, which is as
 * fine as a corner shows. Composition reads only this, so a row or tile builds its shape again a
 * few dozen times over a whole pull rather than on every frame of the finger.
 */
@Composable
internal fun rememberRounding(corner: Follower): State<Float> = remember(corner) {
    derivedStateOf { (corner.value.value.coerceIn(0f, 1f) * ROUNDING_STEPS).roundToInt() / ROUNDING_STEPS }
}

/**
 * [x] on whole pixels, for moving an entry sideways in its layer. Text moved across fractions of
 * a pixel is drawn blurred and flickers at its glyph edges, which on whole pixels stay sharp.
 */
internal fun onWholePixels(x: Float): Float = round(x)

/** The corners of an entry that round, as a set of bits, and the pairs along each side. */
internal object Corners {
    const val TOP_START = 1
    const val TOP_END = 2
    const val BOTTOM_START = 4
    const val BOTTOM_END = 8
    const val TOP = TOP_START or TOP_END
    const val BOTTOM = BOTTOM_START or BOTTOM_END
    const val START = TOP_START or BOTTOM_START
    const val END = TOP_END or BOTTOM_END
    const val ALL = TOP or BOTTOM
}

/**
 * How an entry stands to the one pulled: the share of the finger's travel it leans by when the
 * finger moves toward the end and when it moves toward the start, how strongly it rounds as the
 * pull grows, and the corners that round, while the pulled one is attached and once it is loose.
 * The pulled entry itself leans by nothing, as it moves on its own, while what is shown behind it
 * leans by [backdrop].
 */
internal data class Relation(
    val towardEnd: Float,
    val towardStart: Float,
    val roundness: Float,
    val corners: Int,
    val looseCorners: Int,
    val backdrop: Float = 0f,
)

private data class Mode(val pull: Pull?, val phase: PullPhase?, val dismissing: Boolean)

private data class Targets(
    val mode: Mode,
    val share: Float,
    val backdropShare: Float,
    val lean: Float,
    val backdrop: Float,
    val topStart: Float,
    val topEnd: Float,
    val bottomStart: Float,
    val bottomEnd: Float,
)

private val Unrelated = Targets(Mode(null, null, false), 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

/**
 * Leans the entry with the one [list] has pulled, and rounds its corners, as the notifications
 * around a swiped one do. [relate] says how the entry stands to a pull, or null where it takes no
 * part. [detachAt] is [DETACH_AT] in pixels.
 */
@Composable
internal fun rememberMagnet(list: MagneticList, detachAt: Float, relate: (Pull) -> Relation?): Magnet {
    val magnet = remember { Magnet() }
    // Neighbours settle back with the theme's motion, quickly when rejoining, and the corners change
    // on the fast spatial motion the list item morphs its own shape on.
    val roundness by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Float>())
    val settle by rememberUpdatedState(MaterialTheme.motionScheme.defaultSpatialSpec<Float>())
    val quick by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Float>())
    val relation by rememberUpdatedState(relate)
    LaunchedEffect(list, magnet, detachAt) {
        var mode = Unrelated.mode
        snapshotFlow { list.pull?.let { pull -> relation(pull)?.let { targets(pull, it, detachAt) } } ?: Unrelated }
            .collectLatest { goal ->
                val next = goal.mode
                if (next != mode) {
                    val rejoined = next.phase == PullPhase.Pulling && mode.phase == PullPhase.Detached && next.pull === mode.pull
                    val release = next.pull?.releaseVelocity ?: 0f
                    for ((follower, share) in listOf(magnet.lean to goal.share, magnet.backdrop to goal.backdropShare)) {
                        when {
                            next.phase == PullPhase.Detached -> follower.animateWith(settle)
                            next.phase == PullPhase.Released -> follower.animateWith(settle, release * share)
                            rejoined -> follower.animateWith(quick, 0f)
                            next.pull == null -> follower.animateWith(settle)
                        }
                    }
                    if (next.phase != PullPhase.Pulling || rejoined) {
                        magnet.topStart.animateWith(roundness)
                        magnet.topEnd.animateWith(roundness)
                        magnet.bottomStart.animateWith(roundness)
                        magnet.bottomEnd.animateWith(roundness)
                    }
                    mode = next
                }
                coroutineScope {
                    launch { magnet.lean.follow(goal.lean) }
                    launch { magnet.backdrop.follow(goal.backdrop) }
                    launch { magnet.topStart.follow(goal.topStart) }
                    launch { magnet.topEnd.follow(goal.topEnd) }
                    launch { magnet.bottomStart.follow(goal.bottomStart) }
                    launch { magnet.bottomEnd.follow(goal.bottomEnd) }
                }
            }
    }
    return magnet
}

/**
 * The magnet of the row at [index] of [group]: only the rows of the pulled row's group within three
 * of it take part, as notifications in one section do.
 */
@Composable
internal fun rememberRowMagnet(list: MagneticList, key: Any, group: Any, index: Int, detachAt: Float): Magnet {
    val place by rememberUpdatedState(Triple(key, group, index))
    return rememberMagnet(list, detachAt) { pull -> rowRelation(pull, place) }
}

/** The share of the finger's travel a neighbour [distance] rows away leans by; the pulled row moves on its own. */
private fun leanShare(distance: Int): Float =
    if (distance == 0) 0f else TRANSLATION_MULTIPLIERS.getOrElse(distance + 2) { 0f }

private fun rowRelation(pull: Pull, place: Triple<Any, Any, Int>): Relation? {
    val (key, group, index) = place
    if (pull.group != group) return null
    val distance = if (pull.key == key) 0 else index - pull.index
    if (abs(distance) > 3) return null
    // The rows three away round only on the side facing the pulled one; loose, the pulled row rounds
    // all round and its neighbours on the side facing it.
    val corners = when (distance) {
        -3 -> Corners.BOTTOM
        3 -> Corners.TOP
        else -> Corners.ALL
    }
    val loose = when (distance) {
        -1 -> Corners.BOTTOM
        0 -> Corners.ALL
        1 -> Corners.TOP
        else -> 0
    }
    val share = leanShare(distance)
    return Relation(share, share, ROUNDNESS_MULTIPLIERS[distance + 3], corners, loose)
}

/**
 * How strongly the neighbours of a pulled entry round, from the nearest to those three steps away,
 * as the rows around a pulled row do.
 */
internal val NEIGHBOUR_ROUNDNESS = floatArrayOf(ROUNDNESS_MULTIPLIERS[2], ROUNDNESS_MULTIPLIERS[1], ROUNDNESS_MULTIPLIERS[0])

private fun targets(pull: Pull, relation: Relation, detachAt: Float): Targets {
    val mode = Mode(pull, pull.phase, pull.dismissing)
    val share = if ((pull.finger >= 0f) != pull.rtl) relation.towardEnd else relation.towardStart
    fun corners(mask: Int, value: Float, travel: Float) = Targets(
        mode,
        share,
        relation.backdrop,
        share * travel,
        relation.backdrop * travel,
        if (mask and Corners.TOP_START != 0) value else 0f,
        if (mask and Corners.TOP_END != 0) value else 0f,
        if (mask and Corners.BOTTOM_START != 0) value else 0f,
        if (mask and Corners.BOTTOM_END != 0) value else 0f,
    )
    return when (pull.phase) {
        PullPhase.Pulling -> {
            val pulled = (abs(PULLED_SHARE * pull.finger) / detachAt).coerceAtMost(MAX_PRE_DETACH_ROUNDNESS)
            corners(relation.corners, relation.roundness * pulled, pull.finger)
        }
        // Once the entry comes loose, its neighbours stay as far as the pull took them, where it
        // came loose, and return with the release, rather than settling back while the finger still
        // holds it, as notifications do: the entry still being held, nothing has let them go yet.
        PullPhase.Detached -> corners(relation.looseCorners, 1f, sign(pull.finger) * detachAt)
        // An entry flying off keeps the corners it came loose with until it is gone.
        PullPhase.Released -> if (!pull.dismissing) corners(0, 0f, 0f) else corners(relation.looseCorners, 1f, 0f)
    }
}

/**
 * One entry's swipe, as a notification's: [shift] is where it is drawn apart from the finger,
 * following at half its pace, pulling the entries around it, until it comes loose at [DETACH_AT]
 * and follows the finger itself. A swipe toward the start deletes the entry, and one toward the
 * end makes it a favorite or stops it being one.
 */
@Stable
internal class EntrySwipe {
    val shift = Follower()
    var pull by mutableStateOf<Pull?>(null)
        internal set

    /** Let go to act, until the entry is back in place or gone. */
    var acting by mutableStateOf(false)
        internal set

    /**
     * How much of what the swipe shows behind the entry is in view: it fades out once an action
     * has taken the entry away, and fades in as undoing brings the entry flying back over it.
     */
    val backdrop = Animatable(1f)

    /**
     * What a favorite swipe will do, fixed when it starts, so an entry sliding back after the
     * change still shows the change it made rather than the opposite one.
     */
    var becomesFavorite by mutableStateOf(true)
        internal set
    internal var rtl by mutableStateOf(false)
    internal var release: Job? = null
    internal val tracker = VelocityTracker1D(isDataDifferential = true)
    internal var state: DraggableState? = null
    internal var onStart: () -> Unit = {}
    internal var onStop: (Float) -> Unit = {}
    internal var onReturn: () -> Unit = {}

    /** Brings an entry that flew off back into its place, as when its deletion is undone. */
    fun comeBack() = onReturn()

    val direction by derivedStateOf {
        val x = shift.value.value
        when {
            x == 0f -> SwipeToDismissBoxValue.Settled
            (x > 0f) != rtl -> SwipeToDismissBoxValue.StartToEnd
            else -> SwipeToDismissBoxValue.EndToStart
        }
    }

    val armed: Boolean get() = pull?.phase == PullPhase.Detached || acting
}

/**
 * The swipe of [entry], at [index] of [group] in [list]. Let go to act, it flies off toward the
 * side it was swiped to, aimed past the distance [flyTo] gives for that side and stopped there, so
 * the action follows as soon as it has left. A favorite swipe slides it back once done, unless
 * [leavesOnFavorite], as a favorite tile does that leaves the favorites. An entry composed again
 * after it left goes on with the swipe it [kept].
 */
@Composable
internal fun rememberEntrySwipe(
    entry: HistoryEntry,
    group: Any,
    index: Int,
    list: MagneticList,
    flyTo: (side: Float) -> Float,
    onDelete: (HistoryEntry) -> Unit,
    onFavorite: (HistoryEntry, Boolean) -> Unit,
    leavesOnFavorite: Boolean = false,
    kept: EntrySwipe? = null,
): EntrySwipe {
    val haptics = rememberHaptics()
    val swipeHaptics = rememberSwipeHaptics(haptics)
    val swipe = remember { kept ?: EntrySwipe() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val detachAt = with(density) { DETACH_AT.toPx() }
    val attachAt = with(density) { ATTACH_AT.toPx() }
    val dismissVelocity = with(density) { DISMISS_VELOCITY.toPx() }
    val minDismissVelocity = with(density) { MIN_DISMISS_VELOCITY.toPx() }
    val settle = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    // Coming loose, rejoining and being caught are short moves to keep up with the finger.
    val quick = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val shift = swipe.shift

    LaunchedEffect(swipe.pull) {
        val swiped = swipe.pull ?: return@LaunchedEffect
        var phase = swiped.phase
        snapshotFlow { swiped.phase to swiped.finger }.collectLatest { (now, finger) ->
            if (now == PullPhase.Released) return@collectLatest
            if (now != phase) {
                // Coming loose throws the entry on at the finger's speed; rejoining throws it back.
                val speed = abs(swipe.tracker.calculateVelocity()) * sign(finger)
                if (now == PullPhase.Detached) shift.animateWith(quick, speed) else shift.animateWith(quick, -speed)
                phase = now
            }
            shift.follow(if (now == PullPhase.Detached) finger else PULLED_SHARE * finger)
        }
    }
    DisposableEffect(list) {
        onDispose { if (list.pull?.key == entry.text) list.pull = null }
    }
    LaunchedEffect(swipe.direction, entry.favorite) {
        if (swipe.direction == SwipeToDismissBoxValue.Settled) swipe.becomesFavorite = !entry.favorite
    }

    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val current by rememberUpdatedState(entry)
    val delete by rememberUpdatedState(onDelete)
    val favorite by rememberUpdatedState(onFavorite)
    val place by rememberUpdatedState(index)
    val distance by rememberUpdatedState(flyTo)
    val leaves by rememberUpdatedState(leavesOnFavorite)

    swipe.state = rememberDraggableState { delta ->
        val swiped = swipe.pull ?: return@rememberDraggableState
        swipe.tracker.addDataPoint(SystemClock.uptimeMillis(), delta)
        swiped.finger += delta
        val travel = abs(swiped.finger)
        when (swiped.phase) {
            PullPhase.Pulling ->
                if (travel >= detachAt) {
                    swiped.phase = PullPhase.Detached
                    swipeHaptics.threshold()
                } else {
                    swipeHaptics.pull(PULLED_SHARE * travel / detachAt)
                }
            PullPhase.Detached ->
                if (travel <= attachAt) {
                    swiped.phase = PullPhase.Pulling
                    swipeHaptics.threshold()
                }
            PullPhase.Released -> Unit
        }
    }

    // Handed to the swipe once the composition they come from is kept, never while it is composed.
    SideEffect {
        swipe.rtl = rtl
        swipe.onStart = {
            // An entry caught while it moves is swiped on from where it is.
            swipe.release?.cancel()
            list.scope.launch { swipe.backdrop.snapTo(1f) }
            swipe.acting = false
            shift.held = false
            shift.value.updateBounds(null, null)
            swipe.tracker.resetTracking()
            val from = shift.value.value
            if (from != 0f) shift.animateWith(quick, shift.value.velocity) else shift.stopAnimating()
            val swiped = Pull(entry.text, group, place, swipe.rtl).apply { finger = from }
            swipe.pull = swiped
            list.pull = swiped
        }

        swipe.onReturn = {
            swipe.release?.cancel()
            swipe.release = list.scope.launch {
                launch {
                    swipe.backdrop.snapTo(0f)
                    swipe.backdrop.animateTo(1f, fade)
                }
                shift.held = true
                shift.value.updateBounds(null, null)
                shift.value.animateTo(0f, settle)
                swipe.acting = false
                shift.held = false
            }
        }

        swipe.onStop = { velocity ->
            val swiped = swipe.pull
            if (swiped != null) {
                val detached = swiped.phase == PullPhase.Detached
                val side = sign(swiped.finger)
                val fast = abs(velocity) >= dismissVelocity
                // A loose entry acts unless flung back; an attached one only when flung on.
                val acts = side != 0f && if (detached) !(fast && sign(velocity) != side) else fast && sign(velocity) == side
                swiped.releaseVelocity = velocity
                swiped.dismissing = acts
                swiped.phase = PullPhase.Released
                // From here the release moves the entry, and the spring that followed the finger must not step it.
                shift.held = true
                swipe.release = list.scope.launch {
                    try {
                        if (acts) {
                            if (!detached) swipeHaptics.threshold()
                            swipe.acting = true
                            val speed = if (detached) max(minDismissVelocity, abs(velocity)) else abs(velocity)
                            // Aimed past where it has left and stopped there, so the action follows at
                            // once rather than once the spring has come to rest there.
                            val edge = distance(side)
                            if (edge > 0f) shift.value.updateBounds(-edge, edge)
                            shift.value.animateTo(side * edge * FLY_PAST, settle, side * speed)
                            if ((side > 0f) != swipe.rtl) {
                                favorite(current, !current.favorite)
                                if (!leaves) {
                                    if (list.pull === swiped) list.pull = null
                                    shift.value.animateTo(0f, settle)
                                    swipe.acting = false
                                } else {
                                    swipe.backdrop.animateTo(0f, fade)
                                }
                            } else {
                                delete(current)
                                swipe.backdrop.animateTo(0f, fade)
                            }
                        } else {
                            shift.value.animateTo(0f, settle, velocity)
                        }
                    } finally {
                        if (list.pull === swiped) list.pull = null
                        if (swipe.pull === swiped) swipe.pull = null
                    }
                }
            }
        }
    }
    return swipe
}

/** Lets [swipe] follow horizontal drags; [enabled] is false where the entry takes no swipes. */
internal fun Modifier.entrySwipe(swipe: EntrySwipe, enabled: Boolean = true): Modifier {
    val state = swipe.state ?: return this
    return draggable(
        state,
        Orientation.Horizontal,
        enabled = enabled,
        onDragStarted = { swipe.onStart() },
        onDragStopped = { swipe.onStop(it) },
    )
}

/**
 * The vibrations of a notification swiped: low ticks growing with the pull while it is attached,
 * felt at most every [PULL_INTERVAL_MS], and a click where it comes loose or rejoins. They are touch
 * vibrations, so the system's touch feedback setting governs them. Without the primitives, the
 * click falls back to the gesture threshold feedback, and the pull to the lightest tick a view
 * gives, which cannot grow stronger and so comes more often instead.
 */
internal class SwipeHaptics(context: Context, private val fallback: Haptics) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    private val tick =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK
    private val ticks = vibrator?.areAllPrimitivesSupported(tick) == true
    private val clicks = vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) == true
    private var lastPull = 0L

    /** [pulled] is how far the row has moved, as a share of [DETACH_AT]. */
    fun pull(pulled: Float) {
        val now = SystemClock.elapsedRealtime()
        if (!ticks) {
            // From a tick every FALLBACK_SLOWEST_MS at rest to one every PULL_INTERVAL_MS where the row comes loose.
            val progress = (pulled / PULLED_SHARE).coerceIn(0f, 1f).pow(PULL_EXPONENT)
            val interval = FALLBACK_SLOWEST_MS - (FALLBACK_SLOWEST_MS - PULL_INTERVAL_MS) * progress
            if (now - lastPull < interval) return
            lastPull = now
            fallback.step()
            return
        }
        if (now - lastPull < PULL_INTERVAL_MS) return
        lastPull = now
        val scale = (PULL_SCALE * pulled.pow(PULL_EXPONENT)).pow(PERCEPTION_EXPONENT).coerceIn(0f, 1f)
        val effect = VibrationEffect.startComposition().apply { repeat(PULL_TICKS) { addPrimitive(tick, scale) } }.compose()
        play(effect)
    }

    fun threshold() {
        if (clicks) {
            play(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, CLICK_SCALE).compose())
        } else {
            fallback.threshold()
        }
    }

    private fun play(effect: VibrationEffect) {
        val vibrator = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            // Sonification is what older releases count as touch feedback.
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }

    private companion object {
        const val PULL_INTERVAL_MS = 60
        const val PULL_TICKS = 5
        const val PULL_SCALE = 0.2f
        // Grows the pull faster than linearly, then makes up for how weak vibrations are felt.
        const val PULL_EXPONENT = 1.27f
        const val PERCEPTION_EXPONENT = 1 / 0.89f
        const val CLICK_SCALE = 0.7f
        const val FALLBACK_SLOWEST_MS = 240f
    }
}

@Composable
internal fun rememberSwipeHaptics(haptics: Haptics): SwipeHaptics {
    val context = LocalContext.current
    return remember(context, haptics) { SwipeHaptics(context, haptics) }
}
