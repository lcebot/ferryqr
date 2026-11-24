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
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.pow
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

/** A loose row let go flies off at least this fast. */
internal val MIN_DISMISS_VELOCITY = 2000.dp

/** The longest a row takes to fly off, and how long it takes when let go without speed. */
internal const val MAX_ESCAPE_MS = 400
internal const val DEFAULT_ESCAPE_MS = 200

/** The share of the finger's travel the pulled row follows while still attached. */
internal const val PULLED_SHARE = 0.5f

/** The shares the rows two and one away follow, on either side of [PULLED_SHARE]. */
private val TRANSLATION_MULTIPLIERS = floatArrayOf(0.04f, 0.12f, PULLED_SHARE, 0.12f, 0.04f)

/**
 * How strongly each row, from three above the pulled one to three below, rounds as the pull
 * grows. The rows three away are only rounded, on the side facing it, and do not move.
 */
private val ROUNDNESS_MULTIPLIERS = floatArrayOf(0.5f, 0.7f, 0.9f, 1f, 0.9f, 0.7f, 0.5f)
private const val MAX_PRE_DETACH_ROUNDNESS = 0.8f

/** Springs of the pulled row coming loose and rejoining, of neighbours settling, and of a row let go in place. */
internal val DetachSpring = spring<Float>(dampingRatio = 0.95f, stiffness = 800f)
internal val AttachSpring = spring<Float>(dampingRatio = 0.95f, stiffness = 850f)
private val NeighbourSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 550f)
internal val SnapBackSpring = spring<Float>(dampingRatio = 0.52f, stiffness = 550f)

internal enum class PullPhase { Pulling, Detached, Released }

/** A row being swiped, as the rows around it see it. [finger] is the finger's travel in pixels, right positive. */
@Stable
internal class Pull(val key: Any, val group: Any, index: Int) {
    var index by mutableIntStateOf(index)
    var finger by mutableFloatStateOf(0f)
    var phase by mutableStateOf(PullPhase.Pulling)
    var dismissing by mutableStateOf(false)
    var releaseVelocity = 0f
}

/** The one row of a list being swiped, if any. */
@Stable
internal class MagneticList {
    var pull by mutableStateOf<Pull?>(null)
}

/**
 * A value set directly while nothing animates it, and retargeted while an animation carries it, as
 * a notification moves while a magnetic spring runs.
 */
internal class Follower {
    val value = Animatable(0f)
    private var spec: AnimationSpec<Float>? = null
    private var kick: Float? = null

    /** The next targets are reached through [spec], from [velocity] when given or else the current one. */
    fun animateWith(spec: AnimationSpec<Float>, velocity: Float? = null) {
        this.spec = spec
        kick = velocity
    }

    /** Targets after this are set directly, once whatever animates the value has stopped. */
    fun stopAnimating() {
        spec = null
        kick = null
    }

    suspend fun follow(target: Float) {
        val running = spec
        if (running == null) {
            value.snapTo(target)
            return
        }
        val velocity = kick ?: value.velocity
        kick = null
        value.animateTo(target, running, velocity)
        spec = null
    }
}

/** How a row moves and rounds for the pull of another row in its group, or of itself. */
@Stable
internal class RowMagnet {
    val lean = Follower()
    val top = Follower()
    val bottom = Follower()
}

private data class Mode(val pull: Pull?, val phase: PullPhase?, val dismissing: Boolean)

private data class Targets(val mode: Mode, val distance: Int, val lean: Float, val top: Float, val bottom: Float)

private val Unrelated = Targets(Mode(null, null, false), 0, 0f, 0f, 0f)

/**
 * Leans the row at [index] of [group] with the row [list] has pulled, and rounds its corners, as
 * the notifications around a swiped one do. [detachAt] is [DETACH_AT] in pixels.
 */
@Composable
internal fun rememberRowMagnet(list: MagneticList, key: Any, group: Any, index: Int, detachAt: Float): RowMagnet {
    val magnet = remember { RowMagnet() }
    val roundness by rememberUpdatedState(MaterialTheme.motionScheme.defaultEffectsSpec<Float>())
    val place by rememberUpdatedState(Triple(key, group, index))
    LaunchedEffect(list, magnet, detachAt) {
        var mode = Unrelated.mode
        snapshotFlow { targets(list.pull, place, detachAt) }.collectLatest { goal ->
            val next = goal.mode
            if (next != mode) {
                val rejoined = next.phase == PullPhase.Pulling && mode.phase == PullPhase.Detached && next.pull === mode.pull
                val share = leanShare(goal.distance)
                when {
                    next.phase == PullPhase.Detached -> magnet.lean.animateWith(NeighbourSpring, 0f)
                    next.phase == PullPhase.Released -> magnet.lean.animateWith(NeighbourSpring, (next.pull?.releaseVelocity ?: 0f) * share)
                    rejoined -> magnet.lean.animateWith(AttachSpring, 0f)
                    next.pull == null -> magnet.lean.animateWith(NeighbourSpring)
                }
                if (next.phase != PullPhase.Pulling || rejoined) {
                    magnet.top.animateWith(roundness)
                    magnet.bottom.animateWith(roundness)
                }
                mode = next
            }
            coroutineScope {
                launch { magnet.lean.follow(goal.lean) }
                launch { magnet.top.follow(goal.top) }
                launch { magnet.bottom.follow(goal.bottom) }
            }
        }
    }
    return magnet
}

/** The share of the finger's travel a neighbour [distance] rows away leans by; the pulled row moves on its own. */
private fun leanShare(distance: Int): Float =
    if (distance == 0) 0f else TRANSLATION_MULTIPLIERS.getOrElse(distance + 2) { 0f }

/** Only the rows of the pulled row's group within three of it take part, as notifications in one section do. */
private fun targets(pull: Pull?, place: Triple<Any, Any, Int>, detachAt: Float): Targets {
    val (key, group, index) = place
    if (pull == null || pull.group != group) return Unrelated
    val distance = if (pull.key == key) 0 else index - pull.index
    if (abs(distance) > 3) return Unrelated
    val mode = Mode(pull, pull.phase, pull.dismissing)
    return when (pull.phase) {
        PullPhase.Pulling -> {
            val lean = leanShare(distance) * pull.finger
            val pulled = (abs(PULLED_SHARE * pull.finger) / detachAt).coerceAtMost(MAX_PRE_DETACH_ROUNDNESS)
            val round = ROUNDNESS_MULTIPLIERS[distance + 3] * pulled
            when (distance) {
                -3 -> Targets(mode, distance, lean, 0f, round)
                3 -> Targets(mode, distance, lean, round, 0f)
                else -> Targets(mode, distance, lean, round, round)
            }
        }
        // A row flying off keeps the corners it came loose with until it is gone.
        PullPhase.Detached, PullPhase.Released -> if (pull.phase == PullPhase.Released && !pull.dismissing) {
            Targets(mode, distance, 0f, 0f, 0f)
        } else {
            when (distance) {
                -1 -> Targets(mode, distance, 0f, 0f, 1f)
                0 -> Targets(mode, distance, 0f, 1f, 1f)
                1 -> Targets(mode, distance, 0f, 1f, 0f)
                else -> Targets(mode, distance, 0f, 0f, 0f)
            }
        }
    }
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

    fun threshold(active: Boolean) {
        if (clicks) {
            play(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, CLICK_SCALE).compose())
        } else {
            fallback.threshold(active)
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
