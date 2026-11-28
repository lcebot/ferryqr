@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
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
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
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
 * The picture of [camera] over the whole of the bounds [modifier] gives it, bound for this screen's
 * lifecycle. [contentPadding] is the part the page's bars cover, so the indicator a reset shows sits
 * in the middle of what stays in view. Taps focus only while [scanning].
 */
@Composable
internal fun CameraPreview(
    camera: ScannerCamera,
    scanning: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // The surface the preview draws on, which stays in place until the next session's arrives.
    var surface by remember { mutableStateOf<SurfaceRequest?>(null) }
    val veil = rememberViewfinderVeil()
    val transformer = remember { MutableCoordinateTransformer() }
    val haptics = rememberHaptics()
    val scrim = MaterialTheme.colorScheme.scrim
    // The veil spans the whole viewfinder and moves on the scheme's slow springs.
    val slowEffects = MaterialTheme.motionScheme.slowEffectsSpec<Float>()

    LaunchedEffect(owner) {
        try {
            coroutineScope {
                // Each status makes one call on the veil, and a new status takes over from what the
                // last one still animates.
                launch {
                    camera.status.collectLatest { status ->
                        when (status) {
                            CameraStatus.Connecting -> Unit
                            CameraStatus.Live -> veil.reveal()
                            CameraStatus.Stopped -> veil.close()
                            // A camera that fails while its picture shows leaves the picture as it
                            // is, since CameraX may open the camera again by itself; only a camera
                            // that has not shown its picture yet ends on the error.
                            is CameraStatus.Failed -> if (!status.wasLive) veil.fail()
                        }
                    }
                }
                // A reset asked before this screen was made has been bound for already.
                var first = true
                camera.resets.collectLatest { reset ->
                    if (!first && reset != null) {
                        veil.restart()
                        val shown = surface
                        val still = if (veil.holding || !reset.stillWanted || shown == null) {
                            null
                        } else {
                            camera.takeShot()?.let { Still(it, shown, transformer) }
                        }
                        veil.freeze(still)
                    }
                    first = false
                    // Connecting costs nothing once CameraX is held, and asking on every reset lets
                    // one asked after CameraX could not be reached try it again.
                    if (camera.connect(context) && camera.bind(owner, context) { surface = it }) camera.follow(owner)
                }
            }
        } finally {
            camera.release(owner)
            veil.dropStill()
        }
    }

    // Once the scan has an outcome, the tapped point does not matter.
    LaunchedEffect(scanning) {
        if (!scanning) camera.focus.releaseTapFocus()
    }

    var tap by remember { mutableStateOf<FocusTap?>(null) }
    // A tap's place is in the view's own pixels, which run from the left edge whatever the layout
    // direction, so the focus box starts from the top left corner and moves by an absolute offset.
    Box(modifier, contentAlignment = AbsoluteAlignment.TopLeft) {
        surface?.let { shown ->
            CameraXViewfinder(
                surfaceRequest = shown,
                coordinateTransformer = transformer,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            // A light detent at every whole zoom ratio, where logical cameras tend to switch lenses.
                            if (camera.pinch(zoom)) haptics.step()
                        }
                    }
                    .pointerInput(shown, scanning) {
                        detectTapGestures { at ->
                            if (!scanning) return@detectTapGestures
                            val onSurface = with(transformer) { at.transform() }
                            val point = SurfaceOrientedMeteringPointFactory(
                                shown.resolution.width.toFloat(),
                                shown.resolution.height.toFloat(),
                            ).createPoint(onSurface.x, onSurface.y)
                            if (camera.focus.focusAt(point)) {
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
        FocusBox(tap, camera.focus.tapFocus)
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            // It leaves with the frozen picture and the dimming, whether the picture comes back or
            // the camera fails, and so fades on their spec.
            PageLoadingIndicator(veil.restarting, exit = fadeOut(slowEffects))
        }
    }
}
