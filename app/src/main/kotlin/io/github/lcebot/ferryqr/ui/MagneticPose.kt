/*
 * The magnetic swipe of Android 16's notifications, after SystemUI's
 * MagneticNotificationRowManagerImpl, NotificationTargetsHelper and SwipeHelper.
 * Copyright (C) 2025 The Android Open Source Project. Licensed under the Apache License, Version 2.0:
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.lcebot.ferryqr.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/*
 * The pose maths of the magnetic swipe: how far the entries around a pulled one lean and round for
 * each pull, and for all of them together. It reads pulls through [PullView] alone and holds no
 * state of its own, so it runs, and is tested, apart from Compose.
 */

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

/** How strongly the tiles next to a pulled tile round, as the rows next to a pulled row do. */
internal val NEIGHBOUR_ROUNDNESS = ROUNDNESS_MULTIPLIERS[2]

internal enum class PullPhase { Pulling, Detached, Released }

/**
 * A row being swiped, as the rows around it see it: its [key] and [group], its [index] in the
 * group, the [finger]'s travel in pixels, right positive, and [rtl], whether right is toward the
 * start. Once let go, [dismissing] says whether it acts and [releaseVelocity] how fast it was let go.
 */
internal interface PullView {
    val key: Any
    val group: Any
    val index: Int
    val rtl: Boolean
    val finger: Float
    val phase: PullPhase
    val dismissing: Boolean
    val releaseVelocity: Float
}

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
internal data class Pose(
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
internal data class Contribution(
    val pull: PullView,
    val phase: PullPhase,
    val dismissing: Boolean,
    val share: Float,
    val backdropShare: Float,
    val pose: Pose,
)

/** What [pull] does to an entry that stands to it as [relation] says, with [detachAt] the travel at which a pull comes loose. */
internal fun contribution(pull: PullView, relation: Relation, detachAt: Float): Contribution {
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
 * The pose of an entry the pulls of [contributions] bear on together. Their leans add, so pulls the
 * same way add up and opposite ones cancel, up to [reach] either way, and each corner rounds as far
 * as the pull that rounds it most, since a corner rounded twice is no rounder. What a swipe shows
 * behind the entry moves only with the entry's own pull, never with several, so it takes no limit.
 */
internal fun combine(contributions: List<Contribution>, reach: Float): Pose {
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
 * How the springs that carry an entry's pose on change as one pull bearing on it changes: whether
 * its lean and what is shown behind it take a new spring, [quick] or else the one that settles,
 * and from what speed each starts, null keeping the speed it has, and whether its corners take the
 * spring they round on.
 */
internal data class Respring(
    val leans: Boolean,
    val quick: Boolean,
    val leanVelocity: Float?,
    val backdropVelocity: Float?,
    val rounds: Boolean,
)

/**
 * How the springs change for a pull that has changed phase, or has come to bear on the entry or
 * stopped, or null where it has done none of that: [before] and [after] are what the pull gave the
 * entry before the change and after it, null where it gave nothing, and [bearing] is what every
 * pull bearing on the entry after the change gives it. A loose entry's neighbours settle, and so do
 * they once it is let go, from the speed it was let go at; rejoining, they catch up quickly; and the
 * corners change whenever the pull's phase does. A pull that comes to bear on the entry part of the
 * way through, or stops bearing on it while it still leans or rounds with it, moves it on a spring
 * too rather than in one frame.
 *
 * The entry has one lean for all the pulls on it, so while another finger still pulls it, the entry
 * keeps up with that finger: what would settle it catches up quickly instead, which keeps pace with
 * a finger as a rejoining entry does, and a rejoin keeps the speed the other pulls give it rather
 * than starting from rest.
 */
internal fun respring(before: Contribution?, after: Contribution?, bearing: List<Contribution>): Respring? {
    if (before != null && after != null && before.phase == after.phase && before.dismissing == after.dismissing) return null
    val phase = after?.phase
    val rejoined = before?.phase == PullPhase.Detached && phase == PullPhase.Pulling
    val changed = after?.pull ?: before?.pull
    val others = bearing.filter { it.pull !== changed }
    val alone = others.isEmpty()
    val release = after?.pull?.releaseVelocity ?: 0f
    // Its lean already moves with every pull on it, this one included, so with other pulls still on
    // it the lean keeps the speed it has rather than taking only this finger's.
    fun from(share: Float): Float? = when {
        phase == PullPhase.Released -> if (alone) release * share else null
        rejoined -> if (alone) 0f else null
        else -> null
    }
    val leans = when (phase) {
        PullPhase.Detached, PullPhase.Released -> true
        PullPhase.Pulling -> rejoined || (before == null && after?.pose?.leans == true)
        null -> before?.pose?.leans == true
    }
    val rounds = when (phase) {
        PullPhase.Pulling -> rejoined || (before == null && after?.pose?.rounds == true)
        null -> before?.pose?.rounds == true
        else -> true
    }
    return Respring(
        leans = leans,
        quick = rejoined || others.any { it.phase == PullPhase.Pulling },
        leanVelocity = from(after?.share ?: 0f),
        backdropVelocity = from(after?.backdropShare ?: 0f),
        rounds = rounds,
    )
}

/** The share of the finger's travel a neighbour [distance] rows away leans by; the pulled row moves on its own. */
internal fun leanShare(distance: Int): Float =
    if (distance == 0) 0f else TRANSLATION_MULTIPLIERS.getOrElse(distance + 2) { 0f }

/**
 * How the row with key, group and index [place] stands to [pull]: only the rows of the pulled row's
 * group within three of it take part, as notifications in one section do.
 */
internal fun rowRelation(pull: PullView, place: Triple<Any, Any, Int>): Relation? {
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
    // What the swipe shows behind the pulled row leans with the pull, clearly more than the rows next
    // to it, by BACKDROP_SHARE.
    val backdrop = if (distance == 0) BACKDROP_SHARE else 0f
    return Relation(share, share, ROUNDNESS_MULTIPLIERS[distance + 3], corners, loose, backdrop)
}
