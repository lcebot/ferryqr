package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.VectorizedAnimationSpec
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * Frames further apart than this mean the following was paused in between, as while the page is
 * hidden, rather than that a frame or two was dropped. Material's springs run on time, so a dropped
 * frame is stepped over in full, which keeps the motion at its pace; a pause is not, since stepping
 * over all of it would land the value on its target at once.
 */
private const val PAUSE_NANOS = 100_000_000L

/** How long a frame is taken to last until two frames in a row have shown how long: a frame at 60 Hz. */
private const val FIRST_FRAME_NANOS = 16_666_667L

/**
 * How much longer than the frame length kept a gap may be and still be taken for a frame. A longer
 * gap is a hitch, which is stepped over in full but says nothing about how long frames last, so
 * the next first step does not cover the hitch again.
 */
private const val FRAME_GAP_FACTOR = 2

/**
 * The time each frame steps a motion by, for motions stepped one frame at a time. A frame steps the
 * time since the frame before it, and the first one, with no frame before it, a frame's length, so
 * the motion moves on in its first frame as in every later one. A gap longer than [PAUSE_NANOS] was
 * a pause, which steps only a frame's length as well.
 */
internal class FrameSteps {
    private var lastFrame = 0L
    private var frameNanos = FIRST_FRAME_NANOS

    /** Whether the frame last stepped came after a pause, across which the motion's speed does not hold. */
    var resumed = false
        private set

    /** The next frame steps a frame's length, as the first does. */
    fun restart() {
        lastFrame = 0L
    }

    /** How long the frame at [now] steps, in nanoseconds, or 0 for a frame already stepped. */
    fun elapsed(now: Long): Long {
        val gap = now - lastFrame
        resumed = false
        val elapsed = when {
            lastFrame == 0L -> frameNanos
            gap <= 0L -> return 0L
            gap > PAUSE_NANOS -> {
                resumed = true
                frameNanos
            }
            else -> gap.also { if (it <= FRAME_GAP_FACTOR * frameNanos) frameNanos = it }
        }
        lastFrame = now
        return elapsed
    }
}

/**
 * A value set directly while nothing animates it, and retargeted while a spring carries it, as a
 * notification moves while a magnetic spring runs, the way SpringAnimation.animateToFinalPosition
 * moves the end of a running spring. The swipe, the tiles and the scanner's brackets and markers all
 * follow their targets through it, so they move with one feel.
 *
 * The spring is stepped one frame at a time from wherever the value has got to, with the velocity it
 * has, rather than started anew on each target: an animation started anew on every frame of a drag
 * would never leave its first frame, which only records when it began. Each step covers the time
 * [FrameSteps] gives it, so a follow moves the value on in its first frame as in every later one.
 *
 * The value is an [Animatable], so an animation of its own, such as a fling, can take it over while
 * the follower is [held]. A follower that has no place yet is not [placed], and [moveTo] puts it in
 * its first place at once and springs it from there to every later one.
 */
internal class SpringFollower<T, V : AnimationVector>(val value: Animatable<T, V>) {
    private val converter = value.typeConverter

    // AnimationVector offers no public way to make a vector of its own size, and a fresh animation
    // state starts at rest with one of the value's.
    private val rest: V = AnimationState(converter, value.value).velocityVector

    private var source: AnimationSpec<T>? = null
    private var spring: VectorizedAnimationSpec<V>? = null
    private var velocity: V = rest
    private val frames = FrameSteps()

    /**
     * While set, [follow] leaves the value alone, for another animation to move it: a step of a
     * running spring would otherwise cancel that animation.
     */
    var held = false

    /**
     * Whether the value has been put in a place, by [snapTo] or [moveTo], since it was made or
     * [unplace] last took its place away. Read while laying out or drawing, it tells a value that
     * stands somewhere from one that only holds what it was made with.
     */
    var placed by mutableStateOf(false)
        private set

    /**
     * The next targets are reached through [spec], from [velocity] when given or else the velocity
     * the value has. A given velocity comes from elsewhere, as from the finger or a fling, and holds
     * for the value where it stands, so the next step starts afresh from it.
     */
    fun animateWith(spec: AnimationSpec<T>, velocity: T? = null) {
        if (spec != source) {
            source = spec
            spring = spec.vectorize(converter)
        }
        if (velocity != null) {
            this.velocity = converter.convertToVector(velocity)
            frames.restart()
        }
    }

    /** Targets after this are set directly. */
    fun stopAnimating() {
        source = null
        spring = null
        velocity = rest
        frames.restart()
    }

    /** Puts the value at [target] at once, at rest, with the targets after it set directly. */
    suspend fun snapTo(target: T) {
        stopAnimating()
        value.snapTo(target)
        placed = true
    }

    /** Takes the value's place away, so the next [moveTo] puts it in its place at once. */
    fun unplace() {
        stopAnimating()
        placed = false
    }

    /**
     * Puts the value at [target] at once when it has no place yet, and else moves it there through
     * [spec], as [follow] does.
     */
    suspend fun moveTo(target: T, spec: AnimationSpec<T>) {
        if (placed) follow(target, spec) else snapTo(target)
    }

    /**
     * Moves to [target] directly while nothing animates the value, else frame by frame until it
     * arrives, when the animation ends and later targets are set directly again.
     */
    suspend fun follow(target: T) {
        while (!held) {
            if (spring == null) {
                value.snapTo(target)
                return
            }
            val now = withFrameNanos { it }
            // Another animation may have taken the value over while the frame was awaited, or the
            // spring stopped, which leaves the target to be set directly.
            if (held) return
            val running = spring ?: continue
            val elapsed = frames.elapsed(now)
            if (elapsed == 0L) continue
            if (frames.resumed) velocity = rest
            val from = converter.convertToVector(value.value)
            val to = converter.convertToVector(target)
            if (running.getDurationNanos(from, to, velocity) <= elapsed) {
                stopAnimating()
                value.snapTo(target)
                return
            }
            val next = converter.convertFromVector(running.getValueFromNanos(elapsed, from, to, velocity))
            // The spec hands back vectors it fills again on its next call, so the velocity is kept as a copy.
            val speed = running.getVelocityFromNanos(elapsed, from, to, velocity)
            velocity = converter.convertToVector(converter.convertFromVector(speed))
            value.snapTo(next)
        }
    }

    /**
     * Moves to [target] through [spec] whether or not an animation already carries the value, for a
     * value that springs to every place it is given rather than following a finger directly.
     */
    suspend fun follow(target: T, spec: AnimationSpec<T>) {
        if (spring == null && value.value == target) return
        animateWith(spec)
        follow(target)
    }
}
