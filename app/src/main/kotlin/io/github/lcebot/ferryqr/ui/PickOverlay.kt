@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.isLocatedMiniCode
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Medium icon buttons, the size of the scanner's found markers, so a found code keeps its marker as
 * the frame freezes.
 */
private val MARKER_SIZE = IconButtonDefaults.mediumContainerSize()
private const val STAGGER_MS = 70L

/**
 * Freezes the frame that contained several codes and lets the user tap the one they meant. Only
 * while [active] does it take the back gesture.
 */
@Composable
fun PickOverlay(
    frame: Bitmap,
    detections: List<Detection>,
    alreadyShown: Set<String>,
    active: Boolean,
    onPick: (Detection) -> Unit,
    onClose: () -> Unit,
) {
    // How far a back gesture has shrunk the overlay, and apart from it how far it has dimmed it, so
    // a cancelled gesture springs back to full size while the dimming lifts without bouncing. Both
    // move on the slow springs, since the overlay covers the whole window.
    val shrink = remember { Animatable(0f) }
    val dim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    // Fading out after a pick, it no longer answers the back gesture, which would cancel the pick.
    PredictiveBackHandler(enabled = active) { progress ->
        try {
            progress.collect {
                shrink.snapTo(it.progress)
                dim.snapTo(it.progress)
            }
            onClose()
        } catch (e: CancellationException) {
            scope.launch {
                launch { dim.animateTo(0f, effects) }
                shrink.animateTo(0f, spatial)
            }
            throw e
        }
    }
    val haptics = rememberHaptics()
    val image = remember(frame) { frame.asImageBitmap() }
    // The markers sit on the codes in the frame's own pixels, which run from the left edge whatever
    // the layout direction, so they start from the top left corner and move by absolute offsets.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = 1f - dim.value * 0.6f
                scaleX = 1f - shrink.value * 0.08f
                scaleY = 1f - shrink.value * 0.08f
            }
            .background(Color.Black),
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        val viewWidth = constraints.maxWidth.toFloat()
        val viewHeight = constraints.maxHeight.toFloat()
        // Same math as ContentScale.Crop with centered alignment.
        val scale = max(viewWidth / frame.width, viewHeight / frame.height)
        val dx = (viewWidth - frame.width * scale) / 2f
        val dy = (viewHeight - frame.height * scale) / 2f
        val half = with(LocalDensity.current) { MARKER_SIZE.width.toPx() } / 2f
        val popSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()

        Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        val fresh = detections.filter { it.text !in alreadyShown }
        detections.forEach { detection ->
            val center = detection.center
            val x = (center.x * scale + dx).coerceIn(half, viewWidth - half)
            val y = (center.y * scale + dy).coerceIn(half, viewHeight - half)
            // A code that already had a marker while the camera ran keeps it without popping again;
            // codes that arrived with the frozen frame pop in one after another.
            val wasShown = detection.text in alreadyShown
            val pop = remember(detection) { MutableTransitionState(wasShown) }
            LaunchedEffect(detection) {
                if (wasShown) return@LaunchedEffect
                delay(fresh.indexOf(detection) * STAGGER_MS)
                pop.targetState = true
            }
            AnimatedVisibility(
                visibleState = pop,
                enter = scaleIn(popSpec),
                modifier = Modifier.absoluteOffset { IntOffset((x - half).roundToInt(), (y - half).roundToInt()) },
            ) {
                // A fixed colour keeps one light tone in both themes, which the frozen camera image
                // behind needs.
                FilledIconButton(
                    onClick = { haptics.tap(); onPick(detection) },
                    shapes = IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.mediumPressedShape),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryFixed,
                        contentColor = MaterialTheme.colorScheme.onPrimaryFixed,
                    ),
                    modifier = Modifier.size(MARKER_SIZE),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_forward),
                        contentDescription = if (detection.isLocatedMiniCode) stringResource(R.string.mini_headline) else detection.text,
                        modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                    )
                }
            }
        }

        Text(
            stringResource(R.string.pick_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 24.dp, start = 24.dp, end = 24.dp),
        )
        FilledTonalIconButton(
            onClick = { haptics.tap(); onClose() },
            shapes = IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.mediumPressedShape),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
                .size(MARKER_SIZE),
        ) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.pick_close),
                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
            )
        }
    }
}
