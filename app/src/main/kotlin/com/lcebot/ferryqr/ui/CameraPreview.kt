package com.lcebot.ferryqr.ui

import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lcebot.ferryqr.scan.AutoZoom
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.awaitCancellation

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

@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    executor: Executor,
    autoZoom: AutoZoom,
    session: Int,
    torch: Boolean,
    onFlashUnit: (Boolean) -> Unit,
    onZoom: (Float) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(owner, session) {
        val provider = ProcessCameraProvider.awaitInstance(context)
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
        try {
            provider.unbindAll()
            val bound = try {
                provider.bindToLifecycle(owner, provider.preferredBackCamera(), preview, analysis)
            } catch (e: IllegalArgumentException) {
                onUnavailable()
                return@LaunchedEffect
            }
            camera = bound
            autoZoom.camera = bound
            onFlashUnit(bound.cameraInfo.hasFlashUnit())
            awaitCancellation()
        } finally {
            autoZoom.camera = null
            analysis.clearAnalyzer()
            provider.unbind(preview, analysis)
            camera = null
        }
    }

    LaunchedEffect(camera, torch) {
        camera?.cameraControl?.enableTorch(torch)
    }

    DisposableEffect(camera, owner) {
        val zoomState = camera?.cameraInfo?.zoomState ?: return@DisposableEffect onDispose { }
        val observer = Observer<ZoomState> { onZoom(it.zoomRatio) }
        zoomState.observe(owner, observer)
        onDispose { zoomState.removeObserver(observer) }
    }

    val surface = request ?: return
    CameraXViewfinder(
        surfaceRequest = surface,
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
                val c = camera ?: return@detectTransformGestures
                val state = c.cameraInfo.zoomState.value ?: return@detectTransformGestures
                autoZoom.onUserZoom()
                c.cameraControl.setZoomRatio((state.zoomRatio * zoom).coerceIn(state.minZoomRatio, state.maxZoomRatio))
            }
        },
    )
}
