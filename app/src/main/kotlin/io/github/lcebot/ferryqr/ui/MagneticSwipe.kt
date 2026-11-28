/*
 * The magnetic swipe of Android 16's notifications, after SystemUI's
 * MagneticNotificationRowManagerImpl, NotificationTargetsHelper and SwipeHelper.
 * Copyright (C) 2025 The Android Open Source Project. Licensed under the Apache License, Version 2.0:
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.lcebot.ferryqr.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A row being swiped, as the rows around it see it, its finger and phase held as state for them to follow. */
@Stable
internal class Pull(override val key: Any, override val group: Any, index: Int, override val rtl: Boolean = false) : PullView {
    override var index by mutableIntStateOf(index)
    override var finger by mutableFloatStateOf(0f)
    override var phase by mutableStateOf(PullPhase.Pulling)
    override var dismissing by mutableStateOf(false)
    override var releaseVelocity = 0f
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

    /** The entries swiped away and deleted, while undoing can bring them back. */
    val departures = SwipeDepartures(scope)

    private var haptics: SwipeHaptics? = null

    /**
     * The vibrations of every swipe in the list, made for the first and shared by the rest, since
     * finding the vibrator and asking what it can play goes to the system on the main thread.
     */
    fun haptics(context: Context, fallback: Haptics): SwipeHaptics =
        haptics ?: SwipeHaptics(context, fallback).also { haptics = it }
}

/**
 * How an entry moves and rounds for the pulls of other entries in its group, or for its own: [lean]
 * is how far it moves with the fingers, [backdrop] how far what a swipe shows behind it moves, and
 * each corner's value from 0 to 1 how far it rounds toward the large corner. Only the corners of
 * [Corners] in [rounds] are followed; a corner left out rounds with the other corner of its top or
 * bottom edge, as a row's corners do in pairs, so a row follows two corners rather than four.
 */
@Stable
internal class Magnet(rounds: Int = Corners.ALL) {
    val lean = SpringFollower(Animatable(0f))
    val backdrop = SpringFollower(Animatable(0f))
    val topStart = cornerFollower(rounds, Corners.TOP_START)
    val topEnd = cornerFollower(rounds, Corners.TOP_END)
    val bottomStart = cornerFollower(rounds, Corners.BOTTOM_START)
    val bottomEnd = cornerFollower(rounds, Corners.BOTTOM_END)
    val corners = listOfNotNull(topStart, topEnd, bottomStart, bottomEnd)
    private val all = listOf(lean, backdrop) + corners

    /** Steps all of the values together, so the pulls bearing on the entry only send them on. */
    val loop = FollowerLoop { all }
}

/** A follower for [corner] when [rounds] holds it, else null. */
private fun cornerFollower(rounds: Int, corner: Int): SpringFollower<Float, AnimationVector1D>? =
    if (rounds and corner != 0) SpringFollower(Animatable(0f)) else null

/** Sends each of the magnet's values toward where [pose] has it, for its loop to step it there. */
private suspend fun Magnet.aim(pose: Pose) {
    loop.aim(lean, pose.lean)
    loop.aim(backdrop, pose.backdrop)
    topStart?.let { loop.aim(it, pose.topStart) }
    topEnd?.let { loop.aim(it, pose.topEnd) }
    bottomStart?.let { loop.aim(it, pose.bottomStart) }
    bottomEnd?.let { loop.aim(it, pose.bottomEnd) }
}

/**
 * Sets the springs that carry the magnet on as [change] says: the lean and what is shown behind
 * the entry on [quick] or [settle], and the corners on [roundness].
 */
private fun Magnet.springOn(
    change: Respring,
    settle: AnimationSpec<Float>,
    quick: AnimationSpec<Float>,
    roundness: AnimationSpec<Float>,
) {
    if (change.leans) {
        val spec = if (change.quick) quick else settle
        lean.animateWith(spec, change.leanVelocity)
        backdrop.animateWith(spec, change.backdropVelocity)
    }
    if (change.rounds) corners.forEach { it.animateWith(roundness) }
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

/**
 * Leans the entry with the entries [list] has pulled, and rounds its corners, as the notifications
 * around a swiped one do. [relate] says how the entry stands to a pull, or null where it takes no
 * part. [key] is the entry's own, since an entry pulled itself follows only its own finger and none
 * of the pulls around it. [detachAt] is [DETACH_AT] in pixels, and [nearestShare] the share of the
 * finger's travel the entries next to a pulled one lean by, which is as far as several pulls
 * together lean an entry, so that pulls the same way do not carry it further than one pull does.
 * [rounds] holds the corners the entry follows, as [Magnet] takes them.
 */
@Composable
internal fun rememberMagnet(
    list: MagneticList,
    key: Any,
    detachAt: Float,
    nearestShare: Float,
    rounds: Int = Corners.ALL,
    relate: (Pull) -> Relation?,
): Magnet {
    val magnet = remember { Magnet(rounds) }
    // Neighbours settle back with the theme's motion, quickly when rejoining, and the corners change
    // on the fast spatial motion the list item morphs its own shape on.
    val roundness by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Float>())
    val settle by rememberUpdatedState(MaterialTheme.motionScheme.defaultSpatialSpec<Float>())
    val quick by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Float>())
    val relation by rememberUpdatedState(relate)
    val own by rememberUpdatedState(key)
    LaunchedEffect(list, magnet, detachAt, nearestShare) {
        launch { magnet.loop.run() }
        var last: Map<PullView, Contribution> = emptyMap()
        snapshotFlow {
            val bearing = list.pulls.mapNotNull { pull -> relation(pull)?.let { contribution(pull, it, detachAt) } }
            bearing.lastOrNull { it.pull.key == own }?.let { listOf(it) } ?: bearing
        }.collect { current ->
            // Each pull that changed phase, or came to bear on the entry or stopped, sets the
            // springs as it would alone; the one follower of each value then follows all of them
            // together, so a pull starting, coming loose, rejoining or ending moves the entry on
            // from where it is.
            val next = current.associateBy { it.pull }
            last.forEach { (pull, before) ->
                if (pull !in next) respring(before, null, current)?.let { magnet.springOn(it, settle, quick, roundness) }
            }
            current.forEach { after ->
                respring(last[after.pull], after, current)?.let { magnet.springOn(it, settle, quick, roundness) }
            }
            last = next
            magnet.aim(combine(current, nearestShare * detachAt))
        }
    }
    return magnet
}

/**
 * The magnet of the row at [index] of [group], as [rowRelation] relates it to each pull. A row
 * rounds its corners in pairs along its top and bottom edges, so it follows only the corners at its
 * start.
 */
@Composable
internal fun rememberRowMagnet(list: MagneticList, key: Any, group: Any, index: Int, detachAt: Float): Magnet {
    val place by rememberUpdatedState(Triple(key, group, index))
    return rememberMagnet(list, key, detachAt, leanShare(1), Corners.START) { pull -> rowRelation(pull, place) }
}
