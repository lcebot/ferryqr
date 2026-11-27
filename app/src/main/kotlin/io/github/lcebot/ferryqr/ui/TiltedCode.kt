// No opt-in: MaterialTheme.motionScheme is a stable API.
package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** White margin around a tilted code, matching the quiet zone readers expect. */
private val TILTED_MARGIN = 8.dp
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
 * is on every frame. Exports and saved images keep the code upright.
 */
@Composable
fun TiltedCode(code: Bitmap, area: DpSize, modifier: Modifier) {
    val image = remember(code) { code.asImageBitmap() }
    val density = LocalDensity.current
    val margin = with(density) { TILTED_MARGIN.toPx() }
    val ratio = code.width.toFloat() / code.height
    // Worked out once for the area the code settles in rather than for every size on the way there.
    val target = remember(area, ratio, margin) {
        with(density) { bestTilt(area.width.roundToPx().toFloat(), area.height.roundToPx().toFloat(), ratio, margin) }
    }
    val angle = animateFloatAsState(target, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "code-tilt")
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                // The angle and the area are read while laying out and drawing, so a turn or a resize
                // on the way lays the card out again without composing it again. Unrotated, the card
                // may be wider than the area; only its rotated footprint has to fit.
                .layout { measurable, constraints ->
                    val width = constraints.maxWidth.toFloat()
                    val height = constraints.maxHeight.toFloat()
                    val codeHeight = tiltFit(width, height, ratio, margin, angle.value)
                    val cardWidth = (codeHeight * ratio + 2 * margin).roundToInt()
                    val cardHeight = (codeHeight + 2 * margin).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(cardWidth, cardHeight))
                    val shownWidth = constraints.constrainWidth(cardWidth)
                    val shownHeight = constraints.constrainHeight(cardHeight)
                    layout(shownWidth, shownHeight) {
                        placeable.place((shownWidth - cardWidth) / 2, (shownHeight - cardHeight) / 2)
                    }
                }
                .graphicsLayer { rotationZ = -angle.value }
                .background(Color.White, MaterialTheme.shapes.medium)
                .padding(TILTED_MARGIN)
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
