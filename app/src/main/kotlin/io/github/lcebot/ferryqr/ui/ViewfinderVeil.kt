package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * What the viewfinder draws over the camera's picture while the picture is not live. Every part
 * spans the whole viewfinder, and so moves on [spec], the scheme's slow effects spring.
 *
 * Until the camera delivers its first frame, and again once it opens after the scanner stopped, the
 * viewfinder is black, and then the picture would appear at once. A black cover fades off it instead
 * of the viewfinder fading itself, which works however the viewfinder draws its surface. It starts
 * opaque, as the viewfinder starts black.
 *
 * While a reset binds the camera again, the viewfinder holds a still of the picture it showed, which
 * blurs under a dimming scrim that tells the picture is not live. The blur grows from none, so the
 * still first matches the live picture it covers exactly.
 */
@Stable
internal class ViewfinderVeil(private val scope: CoroutineScope, private val spec: AnimationSpec<Float>) {
    private val covering = Animatable(1f)
    private val showingStill = Animatable(0f)
    private val blurring = Animatable(0f)
    private val dimming = Animatable(0f)

    /** The black cover's opacity. */
    val cover: Float get() = covering.value

    /** The still's opacity. */
    val frozen: Float get() = showingStill.value

    /** How far the still has blurred, from none at 0 to the full blur at 1. */
    val blur: Float get() = blurring.value

    /** How far the dimming scrim has come in, from none at 0 to its full opacity at 1. */
    val dim: Float get() = dimming.value

    var still by mutableStateOf<Still?>(null)
        private set

    /** Whether a reset is under way, which the indicator shows. */
    var restarting by mutableStateOf(false)
        private set

    /**
     * Whether the still in place waits for a camera being bound again, rather than leaving. The
     * still of a reset whose camera has not shown its picture yet stays, so the frozen picture never
     * jumps to a later one, and a reset meanwhile takes no other.
     */
    var holding = false
        private set

    /**
     * The reset answers at once: the dimming and the indicator come while the still is taken, as the
     * next frame may wait behind a closer look, and the still shows the picture they are already over.
     */
    fun restart() {
        restarting = true
        scope.launch { dimming.animateTo(1f, spec) }
    }

    /**
     * Holds [taken] over the picture, unless it is null, and returns once the dimming is in, when the
     * camera can be let go, as the still has long been drawn over the picture it replaces. Without a
     * still the dimming lies over black, and a still an earlier reset left on its way out fades into
     * the black.
     */
    suspend fun freeze(taken: Still?) {
        if (taken != null) {
            dropStill()
            still = taken
            showingStill.snapTo(1f)
            blurring.snapTo(0f)
            holding = true
            // The blur deepens while the camera is bound again rather than holding that back.
            scope.launch { blurring.animateTo(1f, spec) }
        }
        coroutineScope {
            launch { dimming.animateTo(1f, spec) }
            if (!holding) {
                launch { covering.animateTo(1f, spec) }
                launch { showingStill.animateTo(0f, spec) }
            }
        }
        if (!holding) dropStill()
    }

    /**
     * On the picture's first frame the cover, the blurred still and the dimming leave together, and
     * the indicator with them.
     */
    suspend fun reveal() {
        holding = false
        restarting = false
        coroutineScope {
            launch { covering.animateTo(0f, spec) }
            launch { dimming.animateTo(0f, spec) }
            showingStill.animateTo(0f, spec)
        }
        dropStill()
    }

    /**
     * A camera that cannot run takes the blurred still and the dimming away with the indicator, as
     * its first frame would, but onto black, on which the page tells of the failure.
     */
    suspend fun fail() {
        holding = false
        restarting = false
        // Without a still the cover is already up, and under one it goes up unseen.
        covering.snapTo(1f)
        coroutineScope {
            launch { dimming.animateTo(0f, spec) }
            showingStill.animateTo(0f, spec)
        }
        dropStill()
    }

    /**
     * The camera closes as the scanner stops, and opens black as it starts again, so the cover goes
     * up meanwhile and leaves on the first frame after, as at the first launch.
     */
    suspend fun close() {
        covering.snapTo(1f)
    }

    /** The still is a full camera frame, so it goes as soon as it has left the screen. */
    fun dropStill() {
        val shown = still ?: return
        still = null
        shown.shot.release()
    }
}

@Composable
internal fun rememberViewfinderVeil(): ViewfinderVeil {
    val scope = rememberCoroutineScope()
    val spec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    return remember { ViewfinderVeil(scope, spec) }
}
