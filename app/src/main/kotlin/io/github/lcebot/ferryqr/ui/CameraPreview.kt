@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.lcebot.ferryqr.scan.AutoZoom
import java.util.concurrent.Executor
import kotlin.math.floor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The dimming over the frozen picture while a reset binds the camera again, at the opacity Material
 * gives a scrim. Frozen pictures are dimmed with the scheme's scrim role, which is black in both
 * themes and so darkens the camera's picture the same way whatever the theme.
 */
internal const val RESET_SCRIM_ALPHA = 0.32f

/**
 * The dimming over the frame the picker freezes, stronger than a reset's, since the picker's
 * markers must stand out over whatever the frame shows, while a reset's picture only waits for the
 * live one to take its place.
 */
internal const val PICKER_SCRIM_ALPHA = 0.45f

/**
 * How far the frozen picture blurs while a reset binds the camera again. At this radius the picture
 * reads as clearly out of focus, so it is not taken for the live one, while its larger shapes stay
 * where the eye left them, and the live picture takes their place without a jump.
 */
private val STILL_BLUR = 20.dp

/**
 * The camera's picture over the whole of the bounds [modifier] gives it. [contentPadding] is the
 * part the page's bars cover, so the indicator a reset shows sits in the middle of what stays in view.
 * [onSettled] hears whenever the bound camera's picture shows and whenever the camera fails to show
 * one, so what waits for the camera a reset binds never waits on a camera that cannot run.
 */
@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    executor: Executor,
    autoZoom: AutoZoom,
    session: Int,
    torch: Torch,
    onZoom: (Float) -> Unit,
    onUnavailable: () -> Unit,
    onSettled: () -> Unit,
    scanning: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val cameraSession = remember { CameraSession(analyzer, executor, autoZoom, torch) }
    val veil = rememberViewfinderVeil()
    val transformer = remember { MutableCoordinateTransformer() }
    val haptics = rememberHaptics()
    val scrim = MaterialTheme.colorScheme.scrim
    // The veil spans the whole viewfinder and moves on the scheme's slow springs.
    val slowEffects = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val currentSession by rememberUpdatedState(session)
    // The session lives as long as the lifecycle owner, across compositions that pass new callbacks.
    val zoomChanged by rememberUpdatedState(onZoom)
    val unavailable by rememberUpdatedState(onUnavailable)
    val settled by rememberUpdatedState(onSettled)

    LaunchedEffect(owner) {
        if (!cameraSession.connect(context)) {
            unavailable()
            settled()
            return@LaunchedEffect
        }
        try {
            var first = true
            snapshotFlow { currentSession }.collectLatest {
                if (!first) {
                    veil.restart()
                    val still = if (veil.holding) null else cameraSession.takeStill(transformer)
                    cameraSession.retire()
                    veil.freeze(still)
                }
                first = false
                if (!cameraSession.bind(owner, { zoomChanged(it) }, { unavailable() })) {
                    settled()
                    veil.fail()
                    return@collectLatest
                }
                coroutineScope {
                    // A camera that binds can still fail to open. Until its picture shows, that ends
                    // the reset as a failed binding does.
                    val opening = launch {
                        cameraSession.failed.first { it }
                        settled()
                        veil.fail()
                    }
                    cameraSession.pictures(owner).collectLatest { shown ->
                        if (shown) {
                            opening.cancel()
                            settled()
                            veil.reveal()
                        } else {
                            veil.close()
                        }
                    }
                }
            }
        } finally {
            cameraSession.release()
            veil.dropStill()
        }
    }

    // Once the scan has an outcome, the tapped point does not matter.
    LaunchedEffect(scanning) {
        if (!scanning) autoZoom.releaseTapFocus()
    }

    var tap by remember { mutableStateOf<FocusTap?>(null) }
    // A tap's place is in the view's own pixels, which run from the left edge whatever the layout
    // direction, so the focus box starts from the top left corner and moves by an absolute offset.
    Box(modifier, contentAlignment = AbsoluteAlignment.TopLeft) {
        cameraSession.request?.let { surface ->
            CameraXViewfinder(
                surfaceRequest = surface,
                coordinateTransformer = transformer,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val c = cameraSession.camera ?: return@detectTransformGestures
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
        }
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawRect(Color.Black, alpha = veil.cover)
                    drawContent()
                    drawRect(scrim, alpha = RESET_SCRIM_ALPHA * veil.dim)
                }
                // The still has a layer of its own, so the blur takes only the picture, and the
                // still fades as one with the copy that softens it where layers cannot blur. The
                // still overflows the viewfinder as the cropped picture does, and is cut the same way.
                .graphicsLayer {
                    alpha = veil.frozen
                    clip = true
                    // The blur Modifier.blur applies, set here so the growing radius only redraws
                    // the layer rather than composing the viewfinder again each frame. Clamped at
                    // the edges, the picture keeps its own colours there rather than fading into
                    // what lies around the layer.
                    val radius = STILL_BLUR.toPx() * veil.blur
                    renderEffect = if (LAYERS_BLUR && veil.still != null && radius > 0f) {
                        BlurEffect(radius, radius, TileMode.Clamp)
                    } else {
                        null
                    }
                }
                .drawBehind {
                    veil.still?.let { shown ->
                        withTransform({ transform(shown.toViewfinder) }) {
                            drawImage(shown.image, dstSize = shown.resolution)
                            shown.soft?.let { drawImage(it, dstSize = shown.resolution, alpha = veil.blur) }
                        }
                    }
                },
        )
        FocusBox(tap, autoZoom.tapFocus)
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            // It leaves with the frozen picture and the dimming, whether the picture comes back or
            // the camera fails, and so fades on their spec.
            PageLoadingIndicator(veil.restarting, exit = fadeOut(slowEffects))
        }
    }
}
