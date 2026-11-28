// No opt-in: MaterialTheme.motionScheme is a stable API.
package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MAX_TILT = 75
private const val TILT_GAIN = 1.02f

/**
 * The largest height a code [ratio] times as wide as it is tall can have when its card, [margin]
 * included, is turned by [degrees] inside [width] by [height]. Any unit, as long as all agree.
 */
private fun tiltFit(width: Float, height: Float, ratio: Float, margin: Float, degrees: Float): Float {
    val radians = Math.toRadians(degrees.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    val byWidth = (width - 2 * margin * (c + s)) / (ratio * c + s)
    val byHeight = (height - 2 * margin * (c + s)) / (ratio * s + c)
    return min(byWidth, byHeight).coerceAtLeast(0f)
}

/**
 * The angle at which such a code is largest, preferring a flatter angle unless tilting further makes
 * it clearly larger, so a wide area keeps the code level.
 */
private fun bestTilt(width: Float, height: Float, ratio: Float, margin: Float): Float {
    var target = 0f
    var best = tiltFit(width, height, ratio, margin, 0f)
    for (degrees in 1..MAX_TILT) {
        val size = tiltFit(width, height, ratio, margin, degrees.toFloat())
        if (size > best * TILT_GAIN) {
            best = size
            target = degrees.toFloat()
        }
    }
    return target
}

/**
 * A long, flat code, such as a barcode or a PDF417, on its own white card tilted to whatever angle
 * makes it largest in [area], the size its area settles at. A new area turns the code smoothly to
 * the angle for it, an area wide enough keeps it level, and on the way the card fits the area as it
 * is on every frame. Exports and saved images keep the code upright. [taps] go on the upright
 * rectangle the turned card spans, since a ripple cannot turn with the card's outline; they clip
 * their ripple to [shape], the shape of the card when level.
 */
@Composable
fun TiltedCode(code: Bitmap, area: DpSize, shape: Shape, modifier: Modifier, taps: Modifier = Modifier) {
    val image = remember(code) { code.asImageBitmap() }
    val density = LocalDensity.current
    val margin = with(density) { CODE_BORDER.toPx() }
    val ratio = code.width.toFloat() / code.height
    // Worked out once for the area the code settles in rather than for every size on the way there.
    val target = remember(area, ratio, margin) {
        with(density) { bestTilt(area.width.roundToPx().toFloat(), area.height.roundToPx().toFloat(), ratio, margin) }
    }
    val angle = animateFloatAsState(target, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "code-tilt")
    Layout(
        modifier = modifier,
        content = {
            Box(
                Modifier
                    .graphicsLayer { rotationZ = -angle.value }
                    .background(Color.White, shape)
                    .padding(CODE_BORDER)
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Over the card, so a ripple shows on top of it as on any other control.
            Spacer(taps)
        },
    ) { measurables, constraints ->
        // The angle and the area are read while laying out and drawing, so a turn or a resize on
        // the way lays the card out again without composing it again. Unturned, the card may be
        // wider than the area; only its turned footprint has to fit.
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val degrees = angle.value
        val codeHeight = tiltFit(width.toFloat(), height.toFloat(), ratio, margin, degrees)
        val cardWidth = codeHeight * ratio + 2 * margin
        val cardHeight = codeHeight + 2 * margin
        // The spring turning the card may run a little past level, where the span is the same as
        // for the angle on the other side.
        val radians = Math.toRadians(degrees.toDouble())
        val c = abs(cos(radians).toFloat())
        val s = abs(sin(radians).toFloat())
        val card = measurables[0].measure(Constraints.fixed(cardWidth.roundToInt(), cardHeight.roundToInt()))
        val span = measurables[1].measure(
            Constraints.fixed((cardWidth * c + cardHeight * s).roundToInt(), (cardWidth * s + cardHeight * c).roundToInt()),
        )
        layout(width, height) {
            card.place((width - card.width) / 2, (height - card.height) / 2)
            span.place((width - span.width) / 2, (height - span.height) / 2)
        }
    }
}
