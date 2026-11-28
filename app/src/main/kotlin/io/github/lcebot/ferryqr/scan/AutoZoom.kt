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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

/**
 * How much of the view's pattern of brighter and darker cells the view at the tap must still
 * explain, as the square of their correlation, for the tapped focus to hold. Focusing blurs the view
 * and metering on the tap brightens or darkens it as a whole, and neither moves the pattern;
 * pointing the camera elsewhere or something crossing the view does. Neither AOSP nor CameraX has a
 * figure for this; half is where the view at the tap stops explaining most of what is seen.
 */
private const val SAME_SCENE = 0.5

/** A view whose cells spread less than this around their mean, out of 255, is too even to have a pattern. */
private const val EVEN_VIEW = 3.0

/** The view is recorded as the average brightness of this many cells along each side. */
private const val SCENE_GRID = 8

/** Each cell's average is taken from this many samples along each side, enough that blur leaves it alone. */
private const val CELL_SAMPLES = 16

/**
 * Points the camera at codes located but not yet read: zooms toward small ones, and focuses and
 * meters on one, since blur and glare on the code, not its position, decide most failed reads.
 * On a logical multi-camera the zoom ratio also drives the switch to the telephoto lens. A focus
 * the user taps comes first, for as long as [tapFocus] holds.
 *
 * [observe], [focusOn] and [watchScene] run on the analysis thread, and [releaseTapFocus] runs there,
 * on the camera's thread and on the main thread; the other methods run on the main thread.
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

    /**
     * Held while a tap is made or let go, so a thread letting go of the tap it followed cannot
     * cancel a fresh one made meanwhile.
     */
    private val tapLock = Any()

    /** When the focus on the tapped point finished, successful or not; 0 until then. */
    @Volatile
    private var focusDoneAt = 0L

    /** Set once both focus and exposure on the tapped point have settled, from when the camera's scene changes count. */
    @Volatile
    private var settled = false

    /** The view in the first frame after the tap; null until then. */
    @Volatile
    private var sceneAtTap: IntArray? = null

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
        val at = tappedAt
        if (tapped.value) {
            if (now - at < TAP_GUARD_MS) return
            // The code waiting to be read takes the focus over from the tap, unless a fresh tap has
            // taken its place meanwhile.
            if (!letGo(at, cancel = false)) return
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
        synchronized(tapLock) {
            tappedAt = at
            focusDoneAt = 0L
            settled = false
            sceneAtTap = null
            tapped.value = true
        }
        val result = cam.cameraControl.startFocusAndMetering(action)
        // A later tap starts its own wait, so only the latest focus counts.
        result.addListener({ if (tappedAt == at) focusDoneAt = SystemClock.elapsedRealtime() }) { it.run() }
        return true
    }

    /**
     * Lets go of the focus tapped at [at], by default the tap held now, so the camera focuses by
     * itself again. What follows a tap on another thread passes the tap it followed, so a fresh tap
     * made meanwhile stays.
     */
    fun releaseTapFocus(at: Long = tappedAt) {
        letGo(at, cancel = true)
    }

    /**
     * Lets go of the tap made at [at] unless a later one has taken its place, and with [cancel] the
     * camera's focus on it too; returns whether it let go.
     */
    private fun letGo(at: Long, cancel: Boolean): Boolean {
        synchronized(tapLock) {
            if (!tapped.value || tappedAt != at) return false
            tapped.value = false
            if (cancel) camera?.cameraControl?.cancelFocusAndMetering()
            return true
        }
    }

    /**
     * Once the tapped focus has finished, waits for the exposure metered there to settle, and from
     * then on also lets the focus go when the camera reports a scene change, as its continuous focus
     * would start a new scan on one. Before that the camera takes the exposure changing for a scene
     * change, so only the view itself is watched.
     */
    private fun onCaptureResult(result: CaptureResult) {
        val at = tappedAt
        if (!tapped.value || focusDoneAt == 0L) return
        if (!settled) {
            val exposure = result.get(CaptureResult.CONTROL_AE_STATE)
            if (exposure == null || exposure in SETTLED_EXPOSURE) settled = true
            return
        }
        if (result.get(CaptureResult.CONTROL_AF_SCENE_CHANGE) == CaptureResult.CONTROL_AF_SCENE_CHANGE_DETECTED) releaseTapFocus(at)
    }

    /**
     * Follows the view while a tapped focus is held, on the analysis thread. Lets the focus go once
     * the camera has let go of it by itself, and at once, even mid-focus, when the view changes in a
     * way focusing and metering do not change it, so the camera focuses by itself again rather than
     * on what is no longer there.
     */
    fun watchScene(image: ImageProxy) {
        val at = tappedAt
        if (!tapped.value) return
        if (SystemClock.elapsedRealtime() - at >= TAP_HOLD_MS) {
            // The camera has let go of the focus by itself by now.
            letGo(at, cancel = false)
            return
        }
        val scene = sceneOf(image)
        val before = sceneAtTap
        if (before == null) {
            sceneAtTap = scene
        } else if (!sameScene(before, scene)) {
            releaseTapFocus(at)
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
        sceneAtTap = null
    }
}

/** The average brightness of each cell of a [SCENE_GRID] grid over the luma plane, from a few samples per cell. */
private fun sceneOf(image: ImageProxy): IntArray {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val crop = image.cropRect
    val cellWidth = crop.width() / SCENE_GRID
    val cellHeight = crop.height() / SCENE_GRID
    val samples = CELL_SAMPLES
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

/**
 * Whether two views show the same scene: their cells keep the same pattern of brighter and darker,
 * however much brighter or darker one is overall. Two views too even to have a pattern are the same,
 * and an even view and a patterned one are not.
 */
private fun sameScene(a: IntArray, b: IntArray): Boolean {
    val meanA = a.average()
    val meanB = b.average()
    var covariance = 0.0
    var spreadA = 0.0
    var spreadB = 0.0
    for (i in a.indices) {
        val da = a[i] - meanA
        val db = b[i] - meanB
        covariance += da * db
        spreadA += da * da
        spreadB += db * db
    }
    val evenA = sqrt(spreadA / a.size) < EVEN_VIEW
    val evenB = sqrt(spreadB / b.size) < EVEN_VIEW
    if (evenA || evenB) return evenA == evenB
    return covariance > 0 && covariance * covariance >= SAME_SCENE * spreadA * spreadB
}

private fun Float.coerceIn(camera: Camera): Float {
    val state = camera.cameraInfo.zoomState.value ?: return this
    return coerceIn(state.minZoomRatio, state.maxZoomRatio)
}
