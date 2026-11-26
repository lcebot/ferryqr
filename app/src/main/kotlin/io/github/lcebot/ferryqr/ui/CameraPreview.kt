package io.github.lcebot.ferryqr.ui

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
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

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
 */
private fun sessionReporting(autoZoom: AutoZoom, preview: Preview, analysis: ImageAnalysis): SessionConfig =
    SessionConfig.Builder(preview, analysis)
        .camera2Interop { repeatingCaptureCallback = autoZoom.captureCallback }
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

    LaunchedEffect(owner, session) {
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
        val preview = Preview.Builder().setResolutionSelector(selector).build()
        preview.setSurfaceProvider { request = it }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(executor, analyzer)
        val config = sessionReporting(autoZoom, preview, analysis)
        try {
            provider.unbindAll()
            val bound = try {
                provider.bindToLifecycle(owner, provider.preferredBackCamera(), config)
            } catch (e: Exception) {
                onUnavailable()
                return@LaunchedEffect
            }
            camera = bound
            autoZoom.camera = bound
            awaitCancellation()
        } finally {
            autoZoom.camera = null
            analysis.clearAnalyzer()
            provider.unbind(config)
            camera = null
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
    val held by autoZoom.tapFocus.collectAsState()
    Box(modifier) {
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
        FocusBox(tap, held)
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
 * lets go of that focus, whatever the reason.
 */
@Composable
private fun FocusBox(tap: FocusTap?, held: Boolean) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val appear = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val disappear = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // A fixed colour keeps one light tone in both themes, which the camera image behind needs.
    val color = MaterialTheme.colorScheme.primaryFixed
    LaunchedEffect(tap) {
        if (tap == null) return@LaunchedEffect
        alpha.snapTo(0f)
        scale.snapTo(FOCUS_BOX_ENTER_SCALE)
        launch { scale.animateTo(1f, spatial) }
        alpha.animateTo(1f, appear)
    }
    LaunchedEffect(held) {
        if (!held) alpha.animateTo(0f, disappear)
    }
    val at = tap?.at ?: return
    Canvas(
        Modifier
            .offset {
                val half = FOCUS_BOX_SIZE.toPx() / 2
                IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt())
            }
            .size(FOCUS_BOX_SIZE)
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            },
    ) {
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
