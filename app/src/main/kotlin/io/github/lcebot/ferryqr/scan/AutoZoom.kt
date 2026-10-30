package io.github.lcebot.ferryqr.scan

import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.Camera
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

/**
 * Zooms toward small QR codes that zxing-cpp located but could not decode. On a logical
 * multi-camera the zoom ratio also drives the switch to the telephoto lens.
 *
 * [observe] runs on the analysis thread; the other methods run on the main thread.
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

    private fun highZoomTimedOut(cam: Camera, now: Long): Boolean {
        val ratio = cam.cameraInfo.zoomState.value?.zoomRatio ?: return false
        if (ratio < HIGH_ZOOM) {
            highZoomSince = 0L
            return false
        }
        if (highZoomSince == 0L) highZoomSince = now
        return now - highZoomSince > HIGH_ZOOM_TIMEOUT_MS
    }

    /** A pinch hands zoom control to the user until the next scan starts. */
    fun onUserZoom() {
        userOverride = true
    }

    /** Returns to the base zoom when a new scan starts, unless the user set the zoom themselves. */
    fun reset() {
        val cam = camera
        if (cam != null && autoZoomed && !userOverride) {
            cam.cameraControl.setZoomRatio(BASE_ZOOM.coerceIn(cam))
        }
        clearState()
    }

    /** Returns to the base zoom no matter who set the current one. */
    fun forceReset() {
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
    }
}

private fun Float.coerceIn(camera: Camera): Float {
    val state = camera.cameraInfo.zoomState.value ?: return this
    return coerceIn(state.minZoomRatio, state.maxZoomRatio)
}
