package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.util.Size
import androidx.camera.camera2.interop.camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.TorchState
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import io.github.lcebot.ferryqr.scan.AutoZoom
import io.github.lcebot.ferryqr.scan.FocusController
import io.github.lcebot.ferryqr.scan.LiveAnalyzer
import io.github.lcebot.ferryqr.scan.LiveReport
import io.github.lcebot.ferryqr.scan.WeChatEngine
import io.github.lcebot.ferryqr.scan.ZxingEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 1080p keeps small and distant codes readable while zxing-cpp stays fast on the luma plane. */
private val TARGET_SIZE = Size(1920, 1080)

/**
 * Prefers a logical multi-camera, whose zoom ratio moves across the physical lenses, then the
 * camera closest to the main lens, then the widest zoom range.
 */
private fun ProcessCameraProvider.preferredBackCamera(): CameraSelector {
    val best = availableCameraInfos
        .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        .maxWithOrNull(
            compareBy<CameraInfo> { it.isLogicalMultiCameraSupported }
                .thenBy { -abs(ln(it.intrinsicZoomRatio)) }
                .thenBy { info -> info.zoomState.value?.let { it.maxZoomRatio / it.minZoomRatio } ?: 1f }
        )
    return best?.cameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
}

/**
 * The preview and analysis as one session. Its one repeating capture callback hands each completed
 * capture, on the camera's thread, to [focus], which reads the camera's own exposure state and scene
 * change detection from it for a tapped focus, and to [captures], which tells when the picture
 * reaches the viewfinder.
 */
private fun sessionOf(preview: Preview, analysis: ImageAnalysis, focus: FocusController, captures: Captures): SessionConfig =
    SessionConfig.Builder(preview, analysis)
        .camera2Interop {
            repeatingCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    focus.onCaptureResult(result)
                    captures.onCapture()
                }
            }
        }
        .build()

/**
 * The bound session's completed captures, which tell when its picture reaches the viewfinder.
 * [onCapture] is called on the camera's thread.
 */
private class Captures {
    @Volatile
    private var next = CompletableDeferred<Unit>()

    fun onCapture() {
        next.complete(Unit)
    }

    /** Waits for a capture since the session was bound, or since the last [forget]. */
    suspend fun await() = next.await()

    /** Counts only the captures that follow, as for a camera that closed and opens again. */
    fun forget() {
        next = CompletableDeferred()
    }
}

/** Where the bound camera is, as the viewfinder over it shows. */
internal sealed interface CameraStatus {
    /**
     * Bound, being bound anew by a reset, or opening again after the scanner stopped, with its
     * picture not in view yet.
     */
    data object Connecting : CameraStatus

    /** Its picture is in view. */
    data object Live : CameraStatus

    /** Closed while the scanner is stopped, as when a page opens over it. */
    data object Stopped : CameraStatus

    /**
     * It cannot run: CameraX was out of reach, or the camera did not bind, did not open or failed
     * while it ran, which [wasLive] tells. Each failure is a value of its own, so one that follows
     * another is heard all the same.
     */
    class Failed(val wasLive: Boolean) : CameraStatus
}

/**
 * The bound camera's torch. [on] follows the camera's own torch state rather than the last request,
 * since the camera turns the torch off by itself when it closes, as it does whenever the scanner
 * leaves the screen.
 */
@Stable
class Torch {
    /** Whether the bound camera has a flash unit to light. */
    var available by mutableStateOf(false)
        internal set

    var on by mutableStateOf(false)
        internal set

    internal var camera: Camera? = null

    fun set(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }
}

/**
 * A reset asked of the camera, each one its own. [stillWanted] tells whether the camera on its way
 * out showed its picture, which the viewfinder then freezes on while the next one binds.
 */
internal class CameraReset(val stillWanted: Boolean)

/**
 * The scanner's camera, which the scanner owns across the screens made for it: the analysis thread
 * and [analyzer], which reads the frames there, autoZoom and [focus], which point the camera at
 * the codes it finds, the [torch], and the back camera bound with a preview and the analysis as one
 * session, which a reset binds anew. A screen binds the session for its own lifecycle and keeps the
 * surface the preview draws on; [status] tells it and the scanner where the camera is.
 *
 * The session bound last stays bound across a reset until the viewfinder hides its picture, so the
 * still a reset freezes on comes from it; it is let go as the next one binds, or by [release].
 */
@Stable
internal class ScannerCamera(zxing: ZxingEngine, wechat: WeChatEngine, report: (LiveReport) -> Unit) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val autoZoom = AutoZoom()
    val focus = FocusController()
    val analyzer = LiveAnalyzer(zxing, wechat, autoZoom, focus, report)
    private val stills = StillTaker(analyzer)
    val torch = Torch()

    /** The bound camera's zoom ratio. */
    var zoom by mutableFloatStateOf(1f)
        private set

    /**
     * The camera the viewfinder's gestures control. A reset lets go of it at once, while its session
     * stays bound for the still, since the camera on its way out does not count.
     */
    private var camera: Camera? = null

    private val _status = MutableStateFlow<CameraStatus>(CameraStatus.Connecting)

    /** Where the camera bound last is, which alone tells the viewfinder, the page and the scanner what it does. */
    val status: StateFlow<CameraStatus> = _status.asStateFlow()

    private val _resets = MutableStateFlow<CameraReset?>(null)

    /** The reset asked for last, for which the screen binds the camera anew; null before the first. */
    val resets: StateFlow<CameraReset?> = _resets.asStateFlow()

    /**
     * Whether the failure of the camera bound last has been reported, which it is once, until the
     * camera opens again or another binds. A camera that keeps failing while the scanner stops and
     * starts again is still one failure.
     */
    private var reported = false

    private val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(TARGET_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
        )
        .build()
    private lateinit var provider: ProcessCameraProvider
    private var config: SessionConfig? = null
    private var analyzing: ImageAnalysis? = null
    private var captures = Captures()
    private var stopObserving: (() -> Unit)? = null

    /**
     * The lifecycle the session bound last is bound for, so a screen that goes after the next one
     * has bound lets go of nothing but its own.
     */
    private var boundOwner: LifecycleOwner? = null

    /**
     * Counts the cameras let go of, so the picture of one a reset let go of while it was still
     * connecting never turns the status [CameraStatus.Live] for the camera bound next.
     */
    private var retired = 0

    /**
     * Gets hold of CameraX, which fails, besides on a device without a back camera, when the camera
     * service or another app holds the camera in a way it cannot recover from; either way there is
     * no preview, the status turns [CameraStatus.Failed], and this returns false.
     */
    suspend fun connect(context: Context): Boolean {
        provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail()
            return false
        }
        return true
    }

    /** Reports a failure, unless the one under way has been reported already. */
    private fun fail() {
        if (reported) return
        reported = true
        _status.value = CameraStatus.Failed(wasLive = _status.value == CameraStatus.Live)
    }

    /**
     * Binds a new session in place of the one bound last, for [owner]'s lifecycle and turned as the
     * display of [context] is, and returns whether the camera bound; one that cannot bind turns the
     * status [CameraStatus.Failed] at once. [onSurface] hears of each surface the preview asks to
     * draw on.
     */
    fun bind(owner: LifecycleOwner, context: Context, onSurface: (SurfaceRequest) -> Unit): Boolean {
        release()
        val preview = Preview.Builder().setResolutionSelector(selector).build()
        preview.setSurfaceProvider { onSurface(it) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(executor, stills)
        val captures = Captures()
        val config = sessionOf(preview, analysis, focus, captures)
        provider.unbindAll()
        val started = try {
            provider.bindToLifecycle(owner, provider.preferredBackCamera(), config)
        } catch (e: Exception) {
            analysis.clearAnalyzer()
            // The camera let go of above had the flash unit the torch button stands for, and no
            // camera is bound now.
            torch.available = false
            fail()
            return false
        }
        this.config = config
        boundOwner = owner
        this.captures = captures
        analyzing = analysis
        camera = started
        autoZoom.camera = started
        focus.camera = started
        val stopTurning = followRotation(context, preview, analysis)
        val stopWatching = observe(started, owner)
        stopObserving = {
            stopTurning()
            stopWatching()
        }
        return true
    }

    private fun observe(camera: Camera, owner: LifecycleOwner): () -> Unit {
        val info = camera.cameraInfo
        val zoomObserver = Observer<ZoomState> { zoom = it.zoomRatio }
        val torchObserver = Observer<Int> { torch.on = it == TorchState.ON }
        // A camera that binds can still fail to open, as when another app holds it or a policy
        // disables it, and CameraX reports that here rather than from the binding. The camera
        // counts as running again once it opens: one that failed while its picture showed shows it
        // again, and one that failed before waits for its first frame. A passing error that CameraX
        // retries at once is not worth a message.
        val stateObserver = Observer<CameraState> { state ->
            val error = state.error
            if (error != null && error.code != CameraState.ERROR_OTHER_RECOVERABLE_ERROR) {
                fail()
            } else if (state.type == CameraState.Type.OPEN) {
                reported = false
                val failed = _status.value as? CameraStatus.Failed
                if (failed != null) _status.value = if (failed.wasLive) CameraStatus.Live else CameraStatus.Connecting
            }
        }
        info.zoomState.observe(owner, zoomObserver)
        info.torchState.observe(owner, torchObserver)
        info.cameraState.observe(owner, stateObserver)
        torch.camera = camera
        torch.available = info.hasFlashUnit()
        return {
            info.zoomState.removeObserver(zoomObserver)
            info.torchState.removeObserver(torchObserver)
            info.cameraState.removeObserver(stateObserver)
            torch.camera = null
            torch.on = false
        }
    }

    /**
     * Follows the bound camera's picture into [status] until cancelled, as when the next session
     * binds: [CameraStatus.Live] as the picture reaches the viewfinder, and [CameraStatus.Stopped] as
     * the camera closes when [owner] stops, as when a page opens over the scanner. The camera opens
     * black as the scanner starts again, so it is [CameraStatus.Connecting] until its first frame after.
     */
    suspend fun follow(owner: LifecycleOwner) {
        val captures = captures
        val following = retired
        var stopped = false
        owner.lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .distinctUntilChanged()
            .collectLatest { running ->
                // A camera a reset has let go of says nothing of the camera bound next.
                if (retired != following) return@collectLatest
                if (!running) {
                    stopped = true
                    _status.value = CameraStatus.Stopped
                    return@collectLatest
                }
                if (stopped) {
                    // Captures from before the camera closed say nothing of the picture it opens with.
                    captures.forget()
                    _status.value = CameraStatus.Connecting
                }
                captures.await()
                if (retired == following) _status.value = CameraStatus.Live
            }
    }

    /**
     * The next frame in the camera's own orientation, for the still a reset freezes on, or null when
     * the camera sends none in time or the frame cannot be converted.
     */
    suspend fun takeShot(): Shot? = stills.take()

    /**
     * Zooms the bound camera by [factor], as a pinch asks, which hands the zoom to the user until
     * the next scan. Returns whether the zoom passed a whole ratio, where logical cameras tend to
     * switch lenses.
     */
    fun pinch(factor: Float): Boolean {
        val bound = camera ?: return false
        val state = bound.cameraInfo.zoomState.value ?: return false
        autoZoom.onUserZoom()
        val next = (state.zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        bound.cameraControl.setZoomRatio(next)
        return floor(next) != floor(state.zoomRatio)
    }

    /**
     * Points the camera as a new scan starts: back at the base zoom, which with [force] it returns to
     * even from a zoom the user set, and focusing by itself again.
     */
    fun startScan(force: Boolean) {
        if (force) autoZoom.forceReset() else autoZoom.reset()
        focus.reset()
    }

    /**
     * Binds the camera anew. The camera on its way out is let go of at once, all but its session,
     * which stays bound for the still over it: its controls and observers go, a failure of its own
     * neither tells the page again nor stands for the camera bound next, and the status is
     * [CameraStatus.Connecting] from here until the next camera shows its picture.
     */
    fun requestReset() {
        val stillWanted = _status.value == CameraStatus.Live
        retire()
        _resets.value = CameraReset(stillWanted)
    }

    /** Unbinds the session bound last, when it was bound for [owner]. */
    fun release(owner: LifecycleOwner) {
        if (owner === boundOwner) release()
    }

    private fun release() {
        boundOwner = null
        val bound = config
        if (bound != null) {
            autoZoom.camera = null
            focus.camera = null
            analyzing?.clearAnalyzer()
            provider.unbind(bound)
            config = null
            analyzing = null
        }
        // A preview started again reads the status from scratch, not what the last session reached.
        retire()
    }

    private fun retire() {
        stopObserving?.invoke()
        stopObserving = null
        camera = null
        retired++
        reported = false
        _status.value = CameraStatus.Connecting
    }

    /** Lets the analysis thread go once the frame it reads is done, as the scanner goes. */
    fun close() {
        // Queued behind the frame being analysed, so the buffer goes only once nothing reads it.
        executor.execute { analyzer.release() }
        executor.shutdown()
    }
}

/**
 * Keeps [preview] and [analysis] turned as the display [context] shows on is turned, until the
 * function returned is called. A quarter turn makes the activity again, which binds anew, but a half
 * turn, as from landscape to reverse landscape, changes no configuration, and would otherwise leave
 * the frames upside down against the screen and every mark on a code mirrored through the centre.
 * The display is followed as CameraX's rotation guide does.
 */
private fun followRotation(context: Context, preview: Preview, analysis: ImageAnalysis): () -> Unit {
    val displays = context.getSystemService(DisplayManager::class.java) ?: return {}
    // A context of no display has no turn to follow.
    val shownOn = try {
        context.display?.displayId
    } catch (e: UnsupportedOperationException) {
        null
    } ?: return {}
    fun turn() {
        val rotation = displays.getDisplay(shownOn)?.rotation ?: return
        preview.targetRotation = rotation
        analysis.targetRotation = rotation
    }
    val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == shownOn) turn()
        }
    }
    turn()
    // Without a handler, the listener runs on the looper of the main thread, which binds the session.
    displays.registerDisplayListener(listener, null)
    return { displays.unregisterDisplayListener(listener) }
}
