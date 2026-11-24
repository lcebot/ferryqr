package io.github.lcebot.ferryqr.scan

import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A suspected code whose longer side is below this share of the frame's shorter side is small. */
private const val SMALL_FILL = 0.2f

/** Share of the frame's shorter side the code should cover after zooming. */
private const val TARGET_FILL = 0.4f

/** Largest zoom change in one step, so a misread box cannot fling the view. */
private const val MAX_STEP = 2.5f

/** Digital zoom beyond this point loses more detail than it gains. */
private const val MAX_AUTO_ZOOM = 6f

/** Smaller changes are not worth the refocus they cause. */
private const val MIN_GAIN = 1.2f

/** How long a small code must stay in view before the first step. */
private const val SETTLE_MS = 250L

/** Pause after each step so the lens switch and autofocus can finish. */
private const val COOLDOWN_MS = 800L

/** Without any QR-like symbol for this long, an automatic zoom is undone. */
private const val RESET_AFTER_MS = 3000L

/** From this ratio on, an automatic zoom has to produce a result quickly or it is abandoned. */
private const val HIGH_ZOOM = 3f

/** How long an automatic zoom may stay at or above [HIGH_ZOOM] without a successful scan. */
private const val HIGH_ZOOM_TIMEOUT_MS = 4000L

/** Keeps the code this far inside the frame edge after a center zoom. */
private const val EDGE_MARGIN = 0.85f

private const val BASE_ZOOM = 1f

/** A code waiting to be read gets focus and exposure at most this often, so the lens can settle. */
private const val FOCUS_INTERVAL_MS = 1500L

/** How long the camera holds that focus and exposure before returning to its own. */
private const val FOCUS_HOLD_S = 3L

/**
 * How long a focus the user tapped stays theirs before a code waiting to be read may take it over:
 * as long as the AOSP camera app holds a touch focus before returning to continuous focus.
 */
private const val TAP_GUARD_MS = 3000L

/**
 * How long the camera holds a tapped focus at most before returning to its own, CameraX's default.
 * A new scene usually lets it go sooner.
 */
private const val TAP_HOLD_MS = 5000L

/** The exposure states in which metering on the tapped point has settled. */
private val SETTLED_EXPOSURE = setOf(
    CaptureResult.CONTROL_AE_STATE_CONVERGED,
    CaptureResult.CONTROL_AE_STATE_LOCKED,
    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
)

/** Without any capture result for this long after the focus finished, the exposure counts as settled. */
private const val SILENT_SETTLE_MS = 500L

/**
 * For a camera that does not report scene changes itself: the average change of brightness across
 * the view, out of 255 and with an overall shift of exposure left out, from which the view counts as
 * a new scene that the tapped focus no longer fits. Neither AOSP nor CameraX has a figure for this;
 * it is the camera's own detection that the other cameras use.
 */
private const val SCENE_CHANGE = 24

/** The view is recorded as the average brightness of this many cells along each side. */
private const val SCENE_GRID = 8

/**
 * Points the camera at codes located but not yet read: zooms toward small ones, and focuses and
 * meters on one, since blur and glare on the code, not its position, decide most failed reads.
 * On a logical multi-camera the zoom ratio also drives the switch to the telephoto lens. A focus
 * the user taps comes first, for as long as [tapFocus] holds.
 *
 * [observe], [focusOn] and [watchScene] run on the analysis thread; the other methods run on the main
 * thread.
 */
class AutoZoom {

    @Volatile
    var camera: Camera? = null
        set(value) {
            field = value
            clearState()
        }

    @Volatile
    private var autoZoomed = false

    @Volatile
    private var userOverride = false

    @Volatile
    private var smallSince = 0L

    @Volatile
    private var lastSeenAt = 0L

    /** Set when the camera did not follow the previous request, so the WeChat detector is not held back. */
    @Volatile
    private var stalled = false

    /** Set when a high zoom timed out; automatic zoom stays off until the next scan. */
    @Volatile
    private var gaveUp = false

    @Volatile
    private var highZoomSince = 0L

    @Volatile
    private var lastStepAt = 0L

    @Volatile
    private var lastRequested = 0f

    @Volatile
    private var lastFocusAt = 0L

    private val tapped = MutableStateFlow(false)

    /**
     * Whether the camera holds a focus the user tapped. It lets go once the hold runs out, the view
     * changes to a new scene, a code waiting to be read takes over after the guard, or the scan ends.
     */
    val tapFocus: StateFlow<Boolean> = tapped

    @Volatile
    private var tappedAt = 0L

    /** When the focus on the tapped point finished, successful or not; 0 until then. */
    @Volatile
    private var focusDoneAt = 0L

    /** Set once both focus and exposure on the tapped point have settled, from when the scene is watched. */
    @Volatile
    private var settled = false

    @Volatile
    private var lastResultAt = 0L

    /** Set once the camera has reported whether it saw a scene change, which it then judges itself. */
    @Volatile
    private var reportsSceneChange = false

    /** Set when the camera saw a scene change before the tapped focus settled, which lets it go once it has. */
    @Volatile
    private var changedWhileFocusing = false

    /**
     * The view as it was when tapped, and once [sceneRetaken], as it was when the focus settled; null
     * until the first frame after the tap.
     */
    @Volatile
    private var sceneAtTap: IntArray? = null

    @Volatile
    private var sceneRetaken = false

    /**
     * Reads each capture result for the exposure state and the camera's own scene change detection.
     * The preview binds it, and it runs on the camera's thread.
     */
    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            onCaptureResult(result)
        }
    }

    /**
     * Returns true while zooming in is still expected to help, so the caller can postpone the far
     * more expensive WeChat detector until the zoom has run its course.
     */
    fun observe(suspects: List<Rect>, frameWidth: Int, frameHeight: Int): Boolean {
        val cam = camera ?: return false
        val now = SystemClock.elapsedRealtime()

        if (autoZoomed && !userOverride && highZoomTimedOut(cam, now)) {
            cam.cameraControl.setZoomRatio(BASE_ZOOM.coerceIn(cam))
            autoZoomed = false
            gaveUp = true
            lastRequested = 0f
            return false
        }

        if (suspects.isEmpty()) {
            smallSince = 0L
            if (autoZoomed && !userOverride && now - lastSeenAt > RESET_AFTER_MS) {
                cam.cameraControl.setZoomRatio(BASE_ZOOM.coerceIn(cam))
                autoZoomed = false
                lastRequested = 0f
                highZoomSince = 0L
            }
            return false
        }
        lastSeenAt = now
        if (userOverride || stalled || gaveUp) return false

        val box = Rect(suspects.first()).apply { for (i in 1 until suspects.size) union(suspects[i]) }
        val fill = max(box.width(), box.height()) / min(frameWidth, frameHeight).toFloat()
        if (fill <= 0f || fill >= SMALL_FILL) {
            smallSince = 0L
            return false
        }

        val state = cam.cameraInfo.zoomState.value ?: return false
        val current = state.zoomRatio
        val ceiling = min(state.maxZoomRatio, MAX_AUTO_ZOOM)

        // A center zoom scales distances from the center, so an off-center code limits the gain.
        val halfWidth = frameWidth / 2f
        val halfHeight = frameHeight / 2f
        val reachX = max(abs(box.left - halfWidth), abs(box.right - halfWidth))
        val reachY = max(abs(box.top - halfHeight), abs(box.bottom - halfHeight))
        var factor = min(TARGET_FILL / fill, MAX_STEP)
        if (reachX > 0f) factor = min(factor, EDGE_MARGIN * halfWidth / reachX)
        if (reachY > 0f) factor = min(factor, EDGE_MARGIN * halfHeight / reachY)
        val target = min(current * factor, ceiling)
        if (target / current < MIN_GAIN) {
            smallSince = 0L
            return false
        }

        if (smallSince == 0L) smallSince = now
        if (now - smallSince < SETTLE_MS || now - lastStepAt < COOLDOWN_MS) return true
        if (lastRequested > 0f && current < lastRequested * 0.9f) {
            stalled = true
            return false
        }

        cam.cameraControl.setZoomRatio(target)
        autoZoomed = true
        lastStepAt = now
        lastRequested = target
        smallSince = 0L
        return true
    }

    /**
     * Focuses and meters on [region] of the upright frame. The metering point factory expects the
     * sensor-oriented buffer, so the region's centre is turned back by [rotationDegrees].
     */
    fun focusOn(region: Rect, frameWidth: Int, frameHeight: Int, rotationDegrees: Int) {
        val cam = camera ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFocusAt < FOCUS_INTERVAL_MS) return
        if (tapped.value) {
            if (now - tappedAt < TAP_GUARD_MS) return
            // The code waiting to be read takes the focus over from the tap.
            tapped.value = false
        }
        lastFocusAt = now
        val u = region.exactCenterX() / frameWidth
        val v = region.exactCenterY() / frameHeight
        val (x, y) = when ((rotationDegrees % 360 + 360) % 360) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }
        val size = max(region.width().toFloat() / frameWidth, region.height().toFloat() / frameHeight).coerceIn(0.05f, 0.5f)
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), size)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(FOCUS_HOLD_S, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun highZoomTimedOut(cam: Camera, now: Long): Boolean {
        val ratio = cam.cameraInfo.zoomState.value?.zoomRatio ?: return false
        if (ratio < HIGH_ZOOM) {
            highZoomSince = 0L
            return false
        }
        if (highZoomSince == 0L) highZoomSince = now
        return now - highZoomSince > HIGH_ZOOM_TIMEOUT_MS
    }

    /**
     * Focuses and meters on [point] of the viewfinder, where the user tapped. Returns false when
     * the camera cannot focus there, such as a fixed-focus lens.
     */
    fun focusAt(point: MeteringPoint): Boolean {
        val cam = camera ?: return false
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(TAP_HOLD_MS, TimeUnit.MILLISECONDS)
            .build()
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) return false
        val at = SystemClock.elapsedRealtime()
        tappedAt = at
        focusDoneAt = 0L
        settled = false
        changedWhileFocusing = false
        sceneAtTap = null
        sceneRetaken = false
        tapped.value = true
        val result = cam.cameraControl.startFocusAndMetering(action)
        // A later tap starts its own wait, so only the latest focus counts.
        result.addListener({ if (tappedAt == at) focusDoneAt = SystemClock.elapsedRealtime() }) { it.run() }
        return true
    }

    /** Lets go of a tapped focus, so the camera focuses by itself again. */
    fun releaseTapFocus() {
        if (!tapped.value) return
        tapped.value = false
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    /**
     * Once the tapped focus has finished, waits for the exposure metered there to settle, and from
     * then on lets the focus go when the camera reports a scene change, as its continuous focus
     * would start a new scan on one. A change reported while the focus still runs is kept until it
     * settles, so a view that moved on mid-focus does not hold a focus that fits it no longer.
     */
    private fun onCaptureResult(result: CaptureResult) {
        val change = result.get(CaptureResult.CONTROL_AF_SCENE_CHANGE)
        if (change != null) reportsSceneChange = true
        lastResultAt = SystemClock.elapsedRealtime()
        if (!tapped.value) return
        val detected = change == CaptureResult.CONTROL_AF_SCENE_CHANGE_DETECTED
        if (!settled) {
            if (detected) changedWhileFocusing = true
            if (focusDoneAt == 0L) return
            val exposure = result.get(CaptureResult.CONTROL_AE_STATE)
            if (exposure == null || exposure in SETTLED_EXPOSURE) onSettled()
            return
        }
        if (detected) releaseTapFocus()
    }

    private fun onSettled() {
        settled = true
        if (changedWhileFocusing) releaseTapFocus()
    }

    /**
     * Follows the view while a tapped focus is held, on the analysis thread. Lets the focus go once
     * the camera has let go of it by itself, and, for a camera that does not report scene changes,
     * once the view has become a new scene. The view at the tap is compared when the focus settles,
     * which catches a view that moved on mid-focus, and from then on the view at that point, after
     * the exposure metered on the tap has changed it.
     */
    fun watchScene(image: ImageProxy) {
        if (!tapped.value) return
        val now = SystemClock.elapsedRealtime()
        if (now - tappedAt >= TAP_HOLD_MS) {
            tapped.value = false
            return
        }
        val focusDone = focusDoneAt
        if (!settled && focusDone != 0L && now - maxOf(focusDone, lastResultAt) > SILENT_SETTLE_MS) {
            // The camera reports nothing, so the finished focus has to do.
            onSettled()
        }
        if (reportsSceneChange) return
        val scene = sceneOf(image)
        val before = sceneAtTap
        when {
            before == null -> sceneAtTap = scene
            !settled -> Unit
            sceneChange(before, scene) > SCENE_CHANGE -> releaseTapFocus()
            !sceneRetaken -> {
                sceneAtTap = scene
                sceneRetaken = true
            }
        }
    }

    /** A pinch hands zoom control to the user until the next scan starts. */
    fun onUserZoom() {
        userOverride = true
    }

    /** Returns to the base zoom when a new scan starts, unless the user set the zoom themselves. */
    fun reset() {
        releaseTapFocus()
        val cam = camera
        if (cam != null && autoZoomed && !userOverride) {
            cam.cameraControl.setZoomRatio(BASE_ZOOM.coerceIn(cam))
        }
        clearState()
    }

    /** Returns to the base zoom no matter who set the current one. */
    fun forceReset() {
        releaseTapFocus()
        camera?.let { it.cameraControl.setZoomRatio(BASE_ZOOM.coerceIn(it)) }
        clearState()
    }

    private fun clearState() {
        autoZoomed = false
        userOverride = false
        stalled = false
        gaveUp = false
        smallSince = 0L
        lastSeenAt = 0L
        highZoomSince = 0L
        lastStepAt = 0L
        lastRequested = 0f
        lastFocusAt = 0L
        tapped.value = false
        focusDoneAt = 0L
        settled = false
        changedWhileFocusing = false
        sceneAtTap = null
        sceneRetaken = false
    }
}

/** The average brightness of each cell of a [SCENE_GRID] grid over the luma plane, from a few samples per cell. */
private fun sceneOf(image: ImageProxy): IntArray {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val crop = image.cropRect
    val cellWidth = crop.width() / SCENE_GRID
    val cellHeight = crop.height() / SCENE_GRID
    val samples = 4
    return IntArray(SCENE_GRID * SCENE_GRID) { cell ->
        val left = crop.left + (cell % SCENE_GRID) * cellWidth
        val top = crop.top + (cell / SCENE_GRID) * cellHeight
        var sum = 0
        for (j in 0 until samples) {
            val y = top + (2 * j + 1) * cellHeight / (2 * samples)
            for (i in 0 until samples) {
                val x = left + (2 * i + 1) * cellWidth / (2 * samples)
                sum += buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
            }
        }
        sum / (samples * samples)
    }
}

/** How far two views differ on average per cell, with the shift of their overall brightness left out. */
private fun sceneChange(a: IntArray, b: IntArray): Int {
    val shift = (b.sum() - a.sum()) / a.size
    return a.indices.sumOf { abs(b[it] - a[it] - shift) } / a.size
}

private fun Float.coerceIn(camera: Camera): Float {
    val state = camera.cameraInfo.zoomState.value ?: return this
    return coerceIn(state.minZoomRatio, state.maxZoomRatio)
}
