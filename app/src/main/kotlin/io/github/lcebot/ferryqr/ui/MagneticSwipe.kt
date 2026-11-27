/*
 * The magnetic swipe of Android 16's notifications, after SystemUI's
 * MagneticNotificationRowManagerImpl, NotificationTargetsHelper and SwipeHelper.
 * Copyright (C) 2025 The Android Open Source Project. Licensed under the Apache License, Version 2.0:
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.runtime.mutableStateListOf
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
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * The share what a swipe shows behind the pulled row follows. It is the nearest to the pull, so it
 * follows clearly more than the rows one away, as they follow more than the rows two away, while
 * staying well behind the row itself, which uncovers it.
 */
private const val BACKDROP_SHARE = 0.3f

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
 * The rows of a list being swiped. A row let go finishes in [scope], the list's, since a row
 * scrolled out of view leaves composition and would take its own coroutines with it, dropping a
 * delete or favorite already decided.
 */
@Stable
internal class MagneticList(val scope: CoroutineScope) {
    /**
     * Every row being swiped, or let go and not yet settled, in the order their swipes started.
     * Several fingers may each swipe a row at once, and the rows around them lean with all of them.
     */
    val pulls = mutableStateListOf<Pull>()

    /**
     * Whether a finger holds any swipe. Meanwhile the entries take no tap or long press, so an entry
     * opened or picked up mid-swipe does not leave the swipe going on, and acting, under it; other
     * fingers may still swipe. Read as a tap lands rather than as it begins, so a press that started
     * before the swipe is refused too.
     */
    val held: Boolean get() = pulls.any { it.phase != PullPhase.Released }

    private var haptics: SwipeHaptics? = null

    /**
     * The vibrations of every swipe in the list, made for the first and shared by the rest, since
     * finding the vibrator and asking what it can play goes to the system on the main thread.
     */
    fun haptics(context: Context, fallback: Haptics): SwipeHaptics =
        haptics ?: SwipeHaptics(context, fallback).also { haptics = it }

    /**
     * The swipes of rows swiped away and deleted, by content, kept while undoing can bring them
     * back: a row brought back takes up its swipe again and flies back into its place from where it
     * went, as a tile does.
     */
    val departed = HashMap<String, EntrySwipe>()

    /** Where each entry the last undo brought back stood in the order they were deleted. */
    private var turns: Map<String, Int> = emptyMap()

    /** Entries brought back and ready to fly back, by content, gathered before any of them starts. */
    private val ready = ArrayList<Pair<String, EntrySwipe>>()

    /** Takes the contents of the entries an undo brings back, in the order they were deleted, earliest first. */
    fun undone(contents: List<String>) {
        turns = contents.withIndex().associate { (turn, content) -> content to turn }
    }

    /**
     * Flies [swipe], of the entry with content [key], back into its place in its turn. Every entry
     * an undo brings back rejoins the list in the same frame, their gaps opening together, so those
     * ready are gathered until the next frame. They then fly back in the order they were deleted,
     * the first at once, so its return overlaps the gaps opening, and each later one a little after
     * the one before, as [returnDelay] spaces them. An entry already on its way is left to it.
     */
    fun returnInTurn(key: String, swipe: EntrySwipe) {
        if (swipe.returning) return
        swipe.returning = true
        ready += key to swipe
        if (ready.size > 1) return
        scope.launch {
            withFrameNanos {}
            val inTurn = ready.sortedBy { (content, _) -> turns[content] ?: Int.MAX_VALUE }
            ready.clear()
            inTurn.forEachIndexed { turn, (_, back) ->
                launch {
                    delay(returnDelay(turn))
                    back.comeBack()
                }
            }
        }
    }
}

/**
 * How long after the first of the entries one undo brings back the second starts flying back: long
 * enough to read as following it, well short of the return itself.
 */
private const val RETURN_STAGGER_MS = 50f

/**
 * Each later wait between two returns lasts this share of the wait before it. The waits shrink, so
 * a long run of returns closes up rather than trailing behind, and however many there are, the
 * last starts within RETURN_STAGGER_MS / (1 - RETURN_STAGGER_DECAY) of the first, a quarter of a
 * second, while the first ones are still on their way, so the returns read as one.
 */
private const val RETURN_STAGGER_DECAY = 0.8f

/** How long after the first the return in [turn] starts, counting from 0 for the first, which starts at once. */
private fun returnDelay(turn: Int): Long =
    (RETURN_STAGGER_MS * (1f - RETURN_STAGGER_DECAY.pow(turn)) / (1f - RETURN_STAGGER_DECAY)).roundToLong()

/**
 * How an entry moves and rounds for the pulls of other entries in its group, or for its own: [lean]
 * is how far it moves with the fingers, [backdrop] how far what a swipe shows behind it moves, and
 * each corner's value from 0 to 1 how far it rounds toward the large corner.
 */
@Stable
internal class Magnet {
    val lean = SpringFollower(Animatable(0f))
    val backdrop = SpringFollower(Animatable(0f))
    val topStart = SpringFollower(Animatable(0f))
    val topEnd = SpringFollower(Animatable(0f))
    val bottomStart = SpringFollower(Animatable(0f))
    val bottomEnd = SpringFollower(Animatable(0f))
    val corners = listOf(topStart, topEnd, bottomStart, bottomEnd)
}

/** How many steps of half a dp a corner takes between [INNER_CORNER] and [OUTER_CORNER]. */
private val ROUNDING_STEPS = (OUTER_CORNER - INNER_CORNER) / 0.5.dp

/**
 * How far [corner] rounds, from 0 to 1, in steps that move the corner by half a dp, which is as
 * fine as a corner shows. Composition reads only this, so a row or tile builds its shape again a
 * few dozen times over a whole pull rather than on every frame of the finger.
 */
@Composable
internal fun rememberRounding(corner: SpringFollower<Float, AnimationVector1D>): State<Float> = remember(corner) {
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

/** Where an entry is moved and how far each of its corners rounds, for one pull or for all of them together. */
private data class Pose(
    val lean: Float,
    val backdrop: Float,
    val topStart: Float,
    val topEnd: Float,
    val bottomStart: Float,
    val bottomEnd: Float,
) {
    val leans: Boolean get() = lean != 0f || backdrop != 0f
    val rounds: Boolean get() = topStart != 0f || topEnd != 0f || bottomStart != 0f || bottomEnd != 0f
}

/**
 * What [pull] does to an entry it bears on: the [pose] it gives it, the phase the pull is in, and
 * the shares of the finger's travel the entry and what is shown behind it lean by.
 */
private data class Contribution(
    val pull: Pull,
    val phase: PullPhase,
    val dismissing: Boolean,
    val share: Float,
    val backdropShare: Float,
    val pose: Pose,
)

/**
 * Leans the entry with the entries [list] has pulled, and rounds its corners, as the notifications
 * around a swiped one do. [relate] says how the entry stands to a pull, or null where it takes no
 * part. [key] is the entry's own, since an entry pulled itself follows only its own finger and none
 * of the pulls around it. [detachAt] is [DETACH_AT] in pixels, and [nearestShare] the share of the
 * finger's travel the entries next to a pulled one lean by, which is as far as several pulls
 * together lean an entry, so that pulls the same way do not carry it further than one pull does.
 */
@Composable
internal fun rememberMagnet(
    list: MagneticList,
    key: Any,
    detachAt: Float,
    nearestShare: Float,
    relate: (Pull) -> Relation?,
): Magnet {
    val magnet = remember { Magnet() }
    // Neighbours settle back with the theme's motion, quickly when rejoining, and the corners change
    // on the fast spatial motion the list item morphs its own shape on.
    val roundness by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Float>())
    val settle by rememberUpdatedState(MaterialTheme.motionScheme.defaultSpatialSpec<Float>())
    val quick by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Float>())
    val relation by rememberUpdatedState(relate)
    val own by rememberUpdatedState(key)
    LaunchedEffect(list, magnet, detachAt, nearestShare) {
        var last: Map<Pull, Contribution> = emptyMap()
        snapshotFlow {
            val bearing = list.pulls.mapNotNull { pull -> relation(pull)?.let { contribution(pull, it, detachAt) } }
            bearing.lastOrNull { it.pull.key == own }?.let { listOf(it) } ?: bearing
        }.collectLatest { current ->
            // Each pull that changed phase, or came to bear on the entry or stopped, sets the
            // springs as it would alone; the one follower of each value then follows all of them
            // together, so a pull starting, coming loose, rejoining or ending moves the entry on
            // from where it is.
            val next = current.associateBy { it.pull }
            last.forEach { (pull, before) -> if (pull !in next) magnet.respring(before, null, current, settle, quick, roundness) }
            current.forEach { magnet.respring(last[it.pull], it, current, settle, quick, roundness) }
            last = next
            val goal = combine(current, nearestShare * detachAt)
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
 * Sets the springs that carry the magnet on for a pull that has changed phase, or has come to bear
 * on the entry or stopped: [before] and [after] are what the pull gave the entry before the change
 * and after it, null where it gave nothing, and [bearing] is what every pull bearing on the entry
 * after the change gives it. A loose entry's neighbours settle on [settle], and so do they once it
 * is let go, from the speed it was let go at; rejoining, they catch up on [quick]; and the corners
 * change on [roundness] whenever the pull's phase does. A pull that comes to bear on the entry part
 * of the way through, or stops bearing on it while it still leans or rounds with it, moves it on a
 * spring too rather than in one frame.
 *
 * The entry has one lean for all the pulls on it, so while another finger still pulls it, the
 * entry keeps up with that finger: what would settle it catches up on [quick] instead, which keeps
 * pace with a finger as a rejoining entry does, and a rejoin keeps the speed the other pulls give
 * it rather than starting from rest.
 */
private fun Magnet.respring(
    before: Contribution?,
    after: Contribution?,
    bearing: List<Contribution>,
    settle: AnimationSpec<Float>,
    quick: AnimationSpec<Float>,
    roundness: AnimationSpec<Float>,
) {
    if (before != null && after != null && before.phase == after.phase && before.dismissing == after.dismissing) return
    val phase = after?.phase
    val rejoined = before?.phase == PullPhase.Detached && phase == PullPhase.Pulling
    val release = after?.pull?.releaseVelocity ?: 0f
    val changed = after?.pull ?: before?.pull
    val others = bearing.filter { it.pull !== changed }
    val followed = others.any { it.phase == PullPhase.Pulling }
    val back = if (followed) quick else settle
    val fromRest = if (others.isEmpty()) 0f else null
    for ((follower, share) in listOf(lean to (after?.share ?: 0f), backdrop to (after?.backdropShare ?: 0f))) {
        when (phase) {
            PullPhase.Detached -> follower.animateWith(back)
            // Its lean already moves with every pull on it, this one included, so with other pulls
            // still on it the lean keeps the speed it has rather than taking only this finger's.
            PullPhase.Released -> follower.animateWith(back, if (others.isEmpty()) release * share else null)
            PullPhase.Pulling -> when {
                rejoined -> follower.animateWith(quick, fromRest)
                before == null && after.pose.leans -> follower.animateWith(back)
            }
            null -> if (before?.pose?.leans == true) follower.animateWith(back)
        }
    }
    val rounds = when (phase) {
        PullPhase.Pulling -> rejoined || (before == null && after.pose.rounds)
        null -> before?.pose?.rounds == true
        else -> true
    }
    if (rounds) corners.forEach { it.animateWith(roundness) }
}

/**
 * The pose of an entry the pulls of [contributions] bear on together. Their leans add, so pulls the
 * same way add up and opposite ones cancel, up to [reach] either way, and each corner rounds as far
 * as the pull that rounds it most, since a corner rounded twice is no rounder. What a swipe shows
 * behind the entry moves only with the entry's own pull, never with several, so it takes no limit.
 */
private fun combine(contributions: List<Contribution>, reach: Float): Pose {
    var lean = 0f
    var backdrop = 0f
    var topStart = 0f
    var topEnd = 0f
    var bottomStart = 0f
    var bottomEnd = 0f
    for (contribution in contributions) {
        val pose = contribution.pose
        lean += pose.lean
        backdrop += pose.backdrop
        topStart = max(topStart, pose.topStart)
        topEnd = max(topEnd, pose.topEnd)
        bottomStart = max(bottomStart, pose.bottomStart)
        bottomEnd = max(bottomEnd, pose.bottomEnd)
    }
    return Pose(lean.coerceIn(-reach, reach), backdrop, topStart, topEnd, bottomStart, bottomEnd)
}

/**
 * The magnet of the row at [index] of [group]: only the rows of the pulled row's group within three
 * of it take part, as notifications in one section do.
 */
@Composable
internal fun rememberRowMagnet(list: MagneticList, key: Any, group: Any, index: Int, detachAt: Float): Magnet {
    val place by rememberUpdatedState(Triple(key, group, index))
    return rememberMagnet(list, key, detachAt, leanShare(1)) { pull -> rowRelation(pull, place) }
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
    // What the swipe shows behind the pulled row leans as the rows next to it do, as behind a tile.
    val backdrop = if (distance == 0) BACKDROP_SHARE else 0f
    return Relation(share, share, ROUNDNESS_MULTIPLIERS[distance + 3], corners, loose, backdrop)
}

/**
 * How strongly the neighbours of a pulled entry round, from the nearest to those three steps away,
 * as the rows around a pulled row do.
 */
internal val NEIGHBOUR_ROUNDNESS = floatArrayOf(ROUNDNESS_MULTIPLIERS[2], ROUNDNESS_MULTIPLIERS[1], ROUNDNESS_MULTIPLIERS[0])

private fun contribution(pull: Pull, relation: Relation, detachAt: Float): Contribution {
    val phase = pull.phase
    val share = if ((pull.finger >= 0f) != pull.rtl) relation.towardEnd else relation.towardStart
    fun corners(mask: Int, value: Float, travel: Float) = Contribution(
        pull,
        phase,
        pull.dismissing,
        share,
        relation.backdrop,
        Pose(
            share * travel,
            relation.backdrop * travel,
            if (mask and Corners.TOP_START != 0) value else 0f,
            if (mask and Corners.TOP_END != 0) value else 0f,
            if (mask and Corners.BOTTOM_START != 0) value else 0f,
            if (mask and Corners.BOTTOM_END != 0) value else 0f,
        ),
    )
    return when (phase) {
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
    val shift = SpringFollower(Animatable(0f))
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
    internal var rtl by mutableStateOf(false)
    internal var release: Job? = null
    internal val tracker = VelocityTracker1D(isDataDifferential = true)
    internal var state: DraggableState? = null
    internal var onStart: suspend () -> Unit = {}
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
    magnet: Magnet,
    flyTo: (side: Float) -> Float,
    onDelete: (HistoryEntry) -> Unit,
    onFavorite: (HistoryEntry, Boolean) -> Unit,
    leavesOnFavorite: Boolean = false,
    kept: EntrySwipe? = null,
): EntrySwipe {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val swipeHaptics = remember(list) { list.haptics(context, haptics) }
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
        onDispose {
            swipe.pull?.let { list.pulls.remove(it) }
            magnet.lean.held = false
        }
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
        // A row deleted above one still held moves it up a place, and its neighbours with it.
        swipe.pull?.index = index
        swipe.rtl = rtl
        swipe.onStart = {
            // An entry caught while it moves is swiped on from where it is.
            swipe.release?.cancel()
            list.scope.launch { swipe.backdrop.snapTo(1f) }
            swipe.acting = false
            shift.held = false
            shift.value.updateBounds(null, null)
            swipe.tracker.resetTracking()
            // Taken up from where it shows, so it stays under the finger: what it leans with the
            // pulls of other fingers becomes its own shift at once, and its lean is held at rest
            // until it is let go, since a pulled entry follows only its own finger.
            magnet.lean.held = true
            val leaning = magnet.lean.value.value
            val moving = shift.value.velocity
            val from = shift.value.value + leaning
            // The shift first, which may wait for a release still running to stop, then the lean
            // straight after it, so no frame shows the one moved without the other.
            if (leaning != 0f) shift.value.snapTo(from)
            magnet.lean.snapTo(0f)
            if (from != 0f) shift.animateWith(quick, moving) else shift.stopAnimating()
            // An attached entry shows a share of the finger's travel and a loose one all of it, so
            // the finger's travel that shows the entry where it is depends on which it is taken as:
            // attached while it shows less than an attached entry can, loose beyond that.
            val loose = abs(from) >= PULLED_SHARE * detachAt
            val swiped = Pull(entry.text, group, place, swipe.rtl).apply {
                finger = if (loose) from else from / PULLED_SHARE
                if (loose) phase = PullPhase.Detached
            }
            // The pull of a release caught before it settled gives way to the new one at once, so
            // the entry never pulls its neighbours twice.
            swipe.pull?.let { list.pulls.remove(it) }
            swipe.pull = swiped
            list.pulls.add(swiped)
        }

        swipe.onReturn = {
            swipe.release?.cancel()
            swipe.returning = true
            swipe.release = list.scope.launch {
                try {
                    launch {
                        swipe.backdrop.snapTo(0f)
                        swipe.backdrop.animateTo(1f, fade)
                    }
                    shift.held = true
                    shift.value.updateBounds(null, null)
                    shift.value.animateTo(0f, settle)
                    swipe.acting = false
                    shift.held = false
                } finally {
                    swipe.returning = false
                }
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
                            // Gone, the entry lets go of its neighbours as it acts, so they settle back
                            // at once rather than once what the swipe showed behind it has faded, and
                            // the rows the action moves are not taken for its neighbours meanwhile.
                            list.pulls.remove(swiped)
                            if ((side > 0f) != swipe.rtl) {
                                favorite(current, !current.favorite)
                                if (!leaves) {
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
                        list.pulls.remove(swiped)
                        if (swipe.pull === swiped) {
                            swipe.pull = null
                            // Let go, it leans with the other fingers again, from rest.
                            magnet.lean.held = false
                        }
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
