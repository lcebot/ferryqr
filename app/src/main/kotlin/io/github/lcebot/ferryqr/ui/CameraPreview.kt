package io.github.lcebot.ferryqr.ui

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import androidx.camera.camera2.interop.camera2Interop
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.TorchState
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.lcebot.ferryqr.scan.AutoZoom
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

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

@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    executor: Executor,
    autoZoom: AutoZoom,
    session: Int,
    torch: Torch,
    onZoom: (Float) -> Unit,
    onUnavailable: () -> Unit,
    scanning: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val haptics = rememberHaptics()
    // Until the camera bound last delivers its first frame the viewfinder is black, and then the
    // picture would appear at once. A black cover fades off it instead of the viewfinder fading
    // itself, which works however the viewfinder draws its surface. It starts opaque, as the
    // viewfinder starts black.
    val cover = remember { Animatable(1f) }
    val coverSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val currentSession by rememberUpdatedState(session)

    LaunchedEffect(owner) {
        // Besides a device without a back camera, CameraX fails here when the camera service or
        // another app holds the camera in a way it cannot recover from; either way there is no preview.
        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onUnavailable()
            return@LaunchedEffect
        }
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(TARGET_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        // The session bound last stays bound across a new session until the cover hides it, and
        // is let go then, or as the effect ends.
        var bound: SessionConfig? = null
        var analyzing: ImageAnalysis? = null
        fun release() {
            val config = bound ?: return
            autoZoom.camera = null
            analyzing?.clearAnalyzer()
            provider.unbind(config)
            bound = null
            analyzing = null
            camera = null
        }
        try {
            snapshotFlow { currentSession }.collectLatest {
                // A camera bound again, as after a reset, goes dark before the old one lets go, so
                // the picture never cuts to black or freezes in view. The first binding starts dark.
                cover.animateTo(1f, coverSpec)
                release()
                val preview = Preview.Builder().setResolutionSelector(selector).build()
                preview.setSurfaceProvider { request = it }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor, analyzer)
                val firstCapture = CompletableDeferred<Unit>()
                val config = sessionReporting(autoZoom, preview, analysis) { firstCapture.complete(Unit) }
                provider.unbindAll()
                val started = try {
                    provider.bindToLifecycle(owner, provider.preferredBackCamera(), config)
                } catch (e: Exception) {
                    analysis.clearAnalyzer()
                    onUnavailable()
                    return@collectLatest
                }
                bound = config
                analyzing = analysis
                camera = started
                autoZoom.camera = started
                firstCapture.await()
                cover.animateTo(0f, coverSpec)
            }
        } finally {
            release()
        }
    }

    DisposableEffect(camera, owner) {
        val bound = camera ?: return@DisposableEffect onDispose { }
        val zoomObserver = Observer<ZoomState> { onZoom(it.zoomRatio) }
        val torchObserver = Observer<Int> { torch.on = it == TorchState.ON }
        bound.cameraInfo.zoomState.observe(owner, zoomObserver)
        bound.cameraInfo.torchState.observe(owner, torchObserver)
        torch.camera = bound
        torch.available = bound.cameraInfo.hasFlashUnit()
        onDispose {
            bound.cameraInfo.zoomState.removeObserver(zoomObserver)
            bound.cameraInfo.torchState.removeObserver(torchObserver)
            torch.camera = null
            torch.on = false
        }
    }

    // Once the scan has an outcome, the tapped point no longer matters.
    LaunchedEffect(scanning) {
        if (!scanning) autoZoom.releaseTapFocus()
    }

    val surface = request ?: return
    val transformer = remember { MutableCoordinateTransformer() }
    var tap by remember { mutableStateOf<FocusTap?>(null) }
    // A tap's place is in the view's own pixels, which run from the left edge whatever the layout
    // direction, so the focus box starts from the top left corner and moves by an absolute offset.
    Box(modifier, contentAlignment = AbsoluteAlignment.TopLeft) {
        CameraXViewfinder(
            surfaceRequest = surface,
            coordinateTransformer = transformer,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val c = camera ?: return@detectTransformGestures
                        val state = c.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        autoZoom.onUserZoom()
                        val next = (state.zoomRatio * zoom).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                        // A light detent at every whole zoom ratio, where logical cameras tend to switch lenses.
                        if (floor(next) != floor(state.zoomRatio)) haptics.step()
                        c.cameraControl.setZoomRatio(next)
                    }
                }
                .pointerInput(surface, scanning) {
                    detectTapGestures { at ->
                        if (!scanning) return@detectTapGestures
                        val onSurface = with(transformer) { at.transform() }
                        val point = SurfaceOrientedMeteringPointFactory(
                            surface.resolution.width.toFloat(),
                            surface.resolution.height.toFloat(),
                        ).createPoint(onSurface.x, onSurface.y)
                        if (autoZoom.focusAt(point)) {
                            haptics.tap()
                            tap = FocusTap(at, (tap?.id ?: 0) + 1)
                        }
                    }
                },
        )
        Spacer(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = cover.value) })
        FocusBox(tap, autoZoom.tapFocus)
    }
}

/** Where the user tapped to focus; [id] tells one tap from the next on the same spot. */
private data class FocusTap(val at: Offset, val id: Int)

/** The focus box's side, within the 48dp to 96dp the AOSP camera app draws its focus ring at. */
private val FOCUS_BOX_SIZE = 72.dp

/** How much larger the box starts before it closes in on the tapped point. */
private const val FOCUS_BOX_ENTER_SCALE = 1.4f

private val FOCUS_BOX_STROKE = 2.dp
private val FOCUS_BOX_CORNER = 4.dp

/** The ticks pointing in from the middle of each side, as in the iOS camera's focus square. */
private val FOCUS_BOX_TICK = 6.dp

/**
 * The square camera apps draw where a tap set the focus, with a short tick pointing in from the
 * middle of each side. It closes in on the point as it fades in, and fades out once the camera
 * lets go of that focus, whatever the reason. A new tap replaces it at once.
 */
@Composable
private fun FocusBox(tap: FocusTap?, held: StateFlow<Boolean>) {
    tap ?: return
    key(tap) {
        // Starts hidden, so the box closes in on the point as it appears.
        val shown = remember { MutableTransitionState(false) }
        LaunchedEffect(Unit) {
            shown.targetState = true
            // Waits on the focus itself rather than on what the page last saw of it, so a focus let
            // go before the page saw it held still fades the box.
            held.first { !it }
            shown.targetState = false
        }
        // A fixed colour keeps one light tone in both themes, which the camera image behind needs.
        val color = MaterialTheme.colorScheme.primaryFixed
        AnimatedVisibility(
            visibleState = shown,
            enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec(), initialScale = FOCUS_BOX_ENTER_SCALE) +
                fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            modifier = Modifier.absoluteOffset {
                val half = FOCUS_BOX_SIZE.toPx() / 2
                IntOffset((tap.at.x - half).roundToInt(), (tap.at.y - half).roundToInt())
            },
        ) {
            Canvas(Modifier.size(FOCUS_BOX_SIZE)) {
                val stroke = FOCUS_BOX_STROKE.toPx()
                // The stroke is centred on the outline, so the outline sits half a stroke in from the edge.
                val inset = stroke / 2
                drawRoundRect(
                    color,
                    topLeft = Offset(inset, inset),
                    size = size.copy(width = size.width - stroke, height = size.height - stroke),
                    cornerRadius = CornerRadius(FOCUS_BOX_CORNER.toPx()),
                    style = Stroke(stroke),
                )
                val tick = FOCUS_BOX_TICK.toPx()
                val mid = center
                drawLine(color, Offset(mid.x, inset), Offset(mid.x, inset + tick), stroke)
                drawLine(color, Offset(mid.x, size.height - inset), Offset(mid.x, size.height - inset - tick), stroke)
                drawLine(color, Offset(inset, mid.y), Offset(inset + tick, mid.y), stroke)
                drawLine(color, Offset(size.width - inset, mid.y), Offset(size.width - inset - tick, mid.y), stroke)
            }
        }
    }
}
