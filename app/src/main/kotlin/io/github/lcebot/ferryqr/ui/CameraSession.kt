package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
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
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import io.github.lcebot.ferryqr.scan.AutoZoom
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

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
 * The preview and analysis as one session, whose capture results also go to [autoZoom], which
 * reads the camera's own exposure state and scene change detection from them for a tapped focus.
 * [onCapture] hears of each completed capture, on the camera's thread.
 */
private fun sessionReporting(
    autoZoom: AutoZoom,
    preview: Preview,
    analysis: ImageAnalysis,
    onCapture: () -> Unit,
): SessionConfig =
    SessionConfig.Builder(preview, analysis)
        .camera2Interop {
            repeatingCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    autoZoom.captureCallback.onCaptureCompleted(session, request, result)
                    onCapture()
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
 * The back camera bound with a preview and [analyzer] as one session, which a reset binds anew.
 * The session bound last stays bound across a reset until the viewfinder hides its picture, so the
 * still a reset freezes on comes from it; it is let go as the next one binds, or by [release].
 */
@Stable
internal class CameraSession(
    analyzer: ImageAnalysis.Analyzer,
    private val executor: Executor,
    private val autoZoom: AutoZoom,
    private val torch: Torch,
) {
    /** The surface the preview draws on, which stays in place until the next session's arrives. */
    var request by mutableStateOf<SurfaceRequest?>(null)
        private set

    /**
     * The camera the viewfinder's gestures control. A reset lets go of it at once, while its session
     * stays bound for the still, since the camera on its way out does not count.
     */
    var camera by mutableStateOf<Camera?>(null)
        private set

    private val _failed = MutableStateFlow(false)

    /** Whether the camera bound last cannot run, which the page has been told. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(TARGET_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
        )
        .build()
    private val stills = StillTaker(analyzer)
    private lateinit var provider: ProcessCameraProvider
    private var config: SessionConfig? = null
    private var analyzing: ImageAnalysis? = null
    private var captures = Captures()
    private var stopObserving: (() -> Unit)? = null

    // Whether the bound camera's picture is in view, so a reset has a picture to freeze.
    private var live = false

    /**
     * Gets hold of CameraX, which fails, besides on a device without a back camera, when the camera
     * service or another app holds the camera in a way it cannot recover from; either way there is
     * no preview, and this returns false.
     */
    suspend fun connect(context: Context): Boolean {
        provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        return true
    }

    /**
     * Binds a new session in place of the one bound last, for [owner]'s lifecycle, and returns
     * whether the camera bound. A camera that cannot bind tells [onUnavailable] at once, and one that
     * binds tells it of a failure to open as [failed] turns true, and [onZoom] of its zoom ratio.
     */
    fun bind(owner: LifecycleOwner, onZoom: (Float) -> Unit, onUnavailable: () -> Unit): Boolean {
        release()
        live = false
        _failed.value = false
        val preview = Preview.Builder().setResolutionSelector(selector).build()
        preview.setSurfaceProvider { request = it }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(executor, stills)
        val captures = Captures()
        val config = sessionReporting(autoZoom, preview, analysis, captures::onCapture)
        provider.unbindAll()
        val started = try {
            provider.bindToLifecycle(owner, provider.preferredBackCamera(), config)
        } catch (e: Exception) {
            analysis.clearAnalyzer()
            _failed.value = true
            onUnavailable()
            return false
        }
        this.config = config
        this.captures = captures
        analyzing = analysis
        camera = started
        autoZoom.camera = started
        stopObserving = observe(started, owner, onZoom, onUnavailable)
        return true
    }

    private fun observe(
        camera: Camera,
        owner: LifecycleOwner,
        onZoom: (Float) -> Unit,
        onUnavailable: () -> Unit,
    ): () -> Unit {
        val info = camera.cameraInfo
        val zoomObserver = Observer<ZoomState> { onZoom(it.zoomRatio) }
        val torchObserver = Observer<Int> { torch.on = it == TorchState.ON }
        // A camera that binds can still fail to open, as when another app holds it or a policy
        // disables it, and CameraX reports that here rather than from the binding. The page hears
        // of each failure once, and the camera counts as running again once it opens. A passing
        // error that CameraX retries at once is not worth a message.
        val stateObserver = Observer<CameraState> { state ->
            val error = state.error
            if (error != null && error.code != CameraState.ERROR_OTHER_RECOVERABLE_ERROR) {
                if (!_failed.value) {
                    _failed.value = true
                    onUnavailable()
                }
            } else if (state.type == CameraState.Type.OPEN) {
                _failed.value = false
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
     * True as the bound camera's picture reaches the viewfinder, and false as the camera closes when
     * [owner] stops, as when a page opens over the scanner. The camera opens black as the scanner
     * starts again, and true follows on its first frame after.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pictures(owner: LifecycleOwner): Flow<Boolean> {
        val captures = captures
        var stopped = false
        return owner.lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .distinctUntilChanged()
            .transformLatest { running ->
                if (running) {
                    // Captures from before the camera closed say nothing of the picture it opens with.
                    if (stopped) captures.forget()
                    captures.await()
                } else {
                    stopped = true
                }
                live = running
                emit(running)
            }
    }

    /**
     * A still of the picture in view, placed by [transformer] as the viewfinder places the picture,
     * or null when there is none to take, as before the camera's first frame or once it failed.
     */
    suspend fun takeStill(transformer: MutableCoordinateTransformer): Still? {
        val shown = request
        if (!live || _failed.value || shown == null) return null
        val shot = stills.take() ?: return null
        val toViewfinder = Matrix().apply {
            setFrom(transformer.transformMatrix)
            invert()
        }
        return Still(shot, IntSize(shown.resolution.width, shown.resolution.height), toViewfinder)
    }

    /**
     * Lets go of the camera on its way out of a reset while it stays bound for the still over it. Its
     * controls and observers go at once, and a failure of its own neither tells the page again nor
     * stands for the camera bound next.
     */
    fun retire() {
        live = false
        forgetCamera()
        _failed.value = false
    }

    /** Unbinds the session bound last. */
    fun release() {
        val bound = config ?: return
        autoZoom.camera = null
        analyzing?.clearAnalyzer()
        provider.unbind(bound)
        config = null
        analyzing = null
        forgetCamera()
    }

    private fun forgetCamera() {
        stopObserving?.invoke()
        stopObserving = null
        camera = null
    }
}
