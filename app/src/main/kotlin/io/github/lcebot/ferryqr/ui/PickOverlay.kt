@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.scan.Detection
import kotlin.math.max
import kotlin.math.roundToInt

private val MARKER_SIZE = 56.dp
private const val STAGGER_MS = 70L

/** Freezes the frame that contained several codes and lets the user tap the one they meant. */
@Composable
fun PickOverlay(
    frame: Bitmap,
    detections: List<Detection>,
    alreadyShown: Set<String>,
    onPick: (Detection) -> Unit,
    onClose: () -> Unit,
) {
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { progress ->
        try {
            progress.collect { backProgress = it.progress }
            onClose()
        } catch (e: CancellationException) {
            backProgress = 0f
        }
    }
    val haptics = rememberHaptics()
    val image = remember(frame) { frame.asImageBitmap() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = 1f - backProgress * 0.6f
                scaleX = 1f - backProgress * 0.08f
                scaleY = 1f - backProgress * 0.08f
            }
            .background(Color.Black)
    ) {
        val viewWidth = constraints.maxWidth.toFloat()
        val viewHeight = constraints.maxHeight.toFloat()
        // Same math as ContentScale.Crop with centered alignment.
        val scale = max(viewWidth / frame.width, viewHeight / frame.height)
        val dx = (viewWidth - frame.width * scale) / 2f
        val dy = (viewHeight - frame.height * scale) / 2f
        val half = with(LocalDensity.current) { MARKER_SIZE.toPx() } / 2f
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
            val pop = remember(detection) { Animatable(if (wasShown) 1f else 0f) }
            LaunchedEffect(detection) {
                if (wasShown) return@LaunchedEffect
                delay(fresh.indexOf(detection) * STAGGER_MS)
                pop.animateTo(1f, popSpec)
            }
            FilledIconButton(
                onClick = { haptics.tap(); onPick(detection) },
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier
                    .offset { IntOffset((x - half).roundToInt(), (y - half).roundToInt()) }
                    .size(MARKER_SIZE)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    },
            ) {
                Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = detection.text)
            }
        }

        Text(
            stringResource(R.string.pick_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 24.dp),
        )
        FilledTonalIconButton(
            onClick = { haptics.tap(); onClose() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
                .size(MARKER_SIZE),
        ) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.pick_close))
        }
    }
}
