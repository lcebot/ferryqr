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
import kotlinx.coroutines.channels.Channel

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
 * The follower keeps its target in a plain field: [aim] and the calls built on it only change where
 * the value goes, and [step] moves it a frame toward that. Values that move together, as an entry's
 * lean and corners, are stepped by one [FollowerLoop], so a target given on every move of a finger
 * starts nothing; [follow] steps a value on its own until it arrives.
 *
 * The value is an [Animatable], so an animation of its own, such as a fling, can take it over while
 * the follower is [held]. A follower that has no place yet is not [placed], and [place] puts it in
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

    /** Where the value goes, which a spring carrying it reaches frame by frame. */
    private var target: T = value.value

    /**
     * While set, the follower leaves the value alone, for another animation to move it: a step of a
     * running spring would otherwise cancel that animation. Targets given meanwhile are only kept.
     */
    var held = false

    /**
     * Whether the value has been put in a place, by [snapTo] or [place], since it was made or
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

    /** Targets after this are set directly, and the value stays where it is until the next one. */
    fun stopAnimating() {
        endSpring()
        target = value.value
    }

    private fun endSpring() {
        source = null
        spring = null
        velocity = rest
        frames.restart()
    }

    /** Puts the value at [target] at once, at rest, with the targets after it set directly. */
    suspend fun snapTo(target: T) {
        endSpring()
        this.target = target
        value.snapTo(target)
        placed = true
    }

    /** Takes the value's place away, so the next [place] puts it in its place at once. */
    fun unplace() {
        stopAnimating()
        placed = false
    }

    /**
     * Sends the value to [target]: at once while nothing animates it, and else through the frames
     * [step] steps, which the result says are needed.
     */
    suspend fun aim(target: T): Boolean {
        this.target = target
        if (held) return false
        if (spring == null) {
            value.snapTo(target)
            return false
        }
        return true
    }

    /**
     * Sends the value to [target] through [spec] whether or not an animation already carries it, for
     * a value that springs to every place it is given rather than following a finger directly.
     */
    suspend fun aim(target: T, spec: AnimationSpec<T>): Boolean {
        if (spring == null && value.value == target) {
            this.target = target
            return false
        }
        animateWith(spec)
        return aim(target)
    }

    /** Puts the value at [target] at once when it has no place yet, and else sends it there through [spec]. */
    suspend fun place(target: T, spec: AnimationSpec<T>): Boolean {
        if (placed) return aim(target, spec)
        snapTo(target)
        return false
    }

    /**
     * Moves the value on by the frame at [now] toward its target, on the spring that carries it, and
     * says whether it is still on its way. Once it arrives the spring ends, and later targets are set
     * directly again. A value held, or carried by no spring, is left where it is.
     */
    suspend fun step(now: Long): Boolean {
        if (held) return false
        val running = spring ?: return false
        val elapsed = frames.elapsed(now)
        if (elapsed == 0L) return true
        if (frames.resumed) velocity = rest
        val from = converter.convertToVector(value.value)
        val to = converter.convertToVector(target)
        if (running.getDurationNanos(from, to, velocity) <= elapsed) {
            endSpring()
            value.snapTo(target)
            return false
        }
        val next = converter.convertFromVector(running.getValueFromNanos(elapsed, from, to, velocity))
        // The spec hands back vectors it fills again on its next call, so the velocity is kept as a copy.
        val speed = running.getVelocityFromNanos(elapsed, from, to, velocity)
        velocity = converter.convertToVector(converter.convertFromVector(speed))
        value.snapTo(next)
        return true
    }

    /** Moves to [target] through [spec] as [aim] sends it there, frame by frame until it arrives. */
    suspend fun follow(target: T, spec: AnimationSpec<T>) {
        if (!aim(target, spec)) return
        while (step(withFrameNanos { it })) {
            // Each step has waited for its frame.
        }
    }
}

/**
 * Steps the followers [followers] gives in one frame loop, [run], for values that move together.
 * Sending one somewhere through the loop only changes where it goes, so a finger that moves on every
 * input event starts nothing new. The loop asks for frames only while a follower is on its way, and
 * otherwise waits for a target that needs them.
 */
internal class FollowerLoop(private val followers: () -> Iterable<SpringFollower<*, *>>) {
    private val needed = Channel<Unit>(Channel.CONFLATED)

    /** Sends [follower] to [target] as [SpringFollower.aim] does, for the loop to step it there. */
    suspend fun <T> aim(follower: SpringFollower<T, *>, target: T) = wake(follower.aim(target))

    /**
     * Puts [follower] in its first place, or sends it to [target] through [spec], as
     * [SpringFollower.place] does, for the loop to step it there.
     */
    suspend fun <T> moveTo(follower: SpringFollower<T, *>, target: T, spec: AnimationSpec<T>) =
        wake(follower.place(target, spec))

    private fun wake(moving: Boolean) {
        if (moving) needed.trySend(Unit)
    }

    /** Steps every follower once a frame while any of them is on its way, until cancelled. */
    suspend fun run() {
        while (true) {
            needed.receive()
            do {
                val now = withFrameNanos { it }
                var moving = false
                for (follower in followers()) {
                    if (follower.step(now)) moving = true
                }
            } while (moving)
        }
    }
}
