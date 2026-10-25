@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.lcebot.ferryqr.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import com.lcebot.ferryqr.Phase
import com.lcebot.ferryqr.R
import com.lcebot.ferryqr.scan.Detection
import com.lcebot.ferryqr.scan.Sighting
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val MARKER_PADDING = 14.dp
private val MIN_MARKER = 72.dp
private val BADGE_SIZE = 72.dp
private val LOADING_SIZE = 48.dp

/** zxing-cpp often misses a code for a frame or two; the marker rides through such gaps. */
private const val HIDE_DELAY_MS = 250L

/**
 * Feedback drawn where the code actually is, since codes are read anywhere in the frame: corner
 * brackets follow a located code, a loading indicator sits on it while the WeChat detector works,
 * and a short confirmation plays on it after a successful read.
 */
@Composable
fun ScanOverlay(
    sighting: Sighting?,
    confirming: Phase.Confirming?,
    onConfirmed: (Detection) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val viewWidth = constraints.maxWidth.toFloat()
        val viewHeight = constraints.maxHeight.toFloat()
        val padding = with(density) { MARKER_PADDING.toPx() }
        val minSide = with(density) { MIN_MARKER.toPx() }

        val target: Rect? = when {
            confirming != null -> confirming.detection.bounds.let { b ->
                if (b.isEmpty) Rect(Offset(viewWidth / 2, viewHeight / 2), 0f)
                else toView(confirming.frameWidth, confirming.frameHeight, b.left, b.top, b.right, b.bottom, viewWidth, viewHeight)
            }
            sighting != null -> sighting.bounds.let { b ->
                toView(
                    sighting.frameWidth, sighting.frameHeight,
                    b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                    viewWidth, viewHeight,
                )
            }
            else -> null
        }?.squarePadded(padding, minSide)

        val rect = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
        val moveSpec = MaterialTheme.motionScheme.fastSpatialSpec<Rect>()
        LaunchedEffect(target) {
            val next = target ?: return@LaunchedEffect
            if (rect.value == Rect.Zero) rect.snapTo(next) else rect.animateTo(next, moveSpec)
        }

        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(target != null) {
            if (target != null) {
                visible = true
            } else {
                delay(HIDE_DELAY_MS)
                visible = false
                // The next code then appears in place instead of sliding in from the last one.
                delay(HIDE_DELAY_MS)
                rect.snapTo(Rect.Zero)
            }
        }

        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "marker-alpha",
        )
        val color by animateColorAsState(
            targetValue = if (confirming != null) MaterialTheme.colorScheme.primary else Color.White,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "marker-color",
        )

        Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            val r = rect.value
            if (r == Rect.Zero) return@Canvas
            val arm = min(r.width, r.height) * 0.3f
            val corner = min(arm * 0.45f, 10.dp.toPx())
            val brackets = Path().apply {
                moveTo(r.left, r.top + arm); lineTo(r.left, r.top + corner)
                quadraticTo(r.left, r.top, r.left + corner, r.top); lineTo(r.left + arm, r.top)
                moveTo(r.right - arm, r.top); lineTo(r.right - corner, r.top)
                quadraticTo(r.right, r.top, r.right, r.top + corner); lineTo(r.right, r.top + arm)
                moveTo(r.right, r.bottom - arm); lineTo(r.right, r.bottom - corner)
                quadraticTo(r.right, r.bottom, r.right - corner, r.bottom); lineTo(r.right - arm, r.bottom)
                moveTo(r.left + arm, r.bottom); lineTo(r.left + corner, r.bottom)
                quadraticTo(r.left, r.bottom, r.left, r.bottom - corner); lineTo(r.left, r.bottom - arm)
            }
            drawPath(
                brackets, color,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        if (confirming == null && sighting?.deep == true) {
            LoadingIndicator(
                Modifier
                    .centeredOn(LOADING_SIZE) { rect.value.center }
                    .size(LOADING_SIZE)
            )
        }

        if (confirming != null) {
            key(confirming) {
                SuccessBadge(center = { rect.value.center }, onDone = { onConfirmed(confirming.detection) })
            }
        }
    }
}

/** A burst that pops onto the code and settles into a circle with a check mark. */
@Composable
private fun SuccessBadge(center: () -> Offset, onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val done by rememberUpdatedState(onDone)
    val pop = remember { Animatable(0f) }
    val popSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val morph = remember { Morph(MaterialShapes.SoftBurst, MaterialShapes.Circle) }
    val fill = MaterialTheme.colorScheme.primary
    val tint = MaterialTheme.colorScheme.onPrimary
    val path = remember { android.graphics.Path() }
    val matrix = remember { android.graphics.Matrix() }

    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        pop.animateTo(1f, popSpec)
        delay(140)
        done()
    }

    Box(
        Modifier
            .centeredOn(BADGE_SIZE, center)
            .size(BADGE_SIZE)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                rotationZ = (1f - pop.value) * -45f
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            path.rewind()
            morph.toPath(pop.value.coerceIn(0f, 1f), path)
            matrix.setScale(size.width, size.height)
            path.transform(matrix)
            drawPath(path.asComposePath(), fill)
        }
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.align(Alignment.Center).size(36.dp),
        )
    }
}

/** Positions a [size] box so its center follows [center], read at layout time to avoid recomposing. */
private fun Modifier.centeredOn(size: Dp, center: () -> Offset): Modifier = offset {
    val c = center()
    val half = size.roundToPx() / 2
    IntOffset(c.x.roundToInt() - half, c.y.roundToInt() - half)
}

/** Maps upright frame pixels onto the preview, which shows the frame center-cropped. */
private fun toView(
    frameWidth: Int, frameHeight: Int,
    left: Float, top: Float, right: Float, bottom: Float,
    viewWidth: Float, viewHeight: Float,
): Rect {
    val scale = max(viewWidth / frameWidth, viewHeight / frameHeight)
    val dx = (viewWidth - frameWidth * scale) / 2f
    val dy = (viewHeight - frameHeight * scale) / 2f
    return Rect(left * scale + dx, top * scale + dy, right * scale + dx, bottom * scale + dy)
}

private fun Rect.squarePadded(padding: Float, minSide: Float): Rect {
    val side = max(max(width, height) + 2 * padding, minSide)
    return Rect(center, side / 2)
}
