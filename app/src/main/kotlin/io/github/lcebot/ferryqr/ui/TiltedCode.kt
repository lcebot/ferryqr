// No opt-in: MaterialTheme.motionScheme is a stable API.
package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
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
 * makes it largest in the available area. The angle follows the area as it changes size, so a
 * growing area turns the code smoothly, and an area wide enough keeps it level. Exports and saved
 * images keep the code upright.
 */
@Composable
fun TiltedCode(code: Bitmap, modifier: Modifier) {
    val image = remember(code) { code.asImageBitmap() }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val margin = with(density) { TILTED_MARGIN.toPx() }
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val ratio = code.width.toFloat() / code.height

        val target = bestTilt(width, height, ratio, margin)
        val angle by animateFloatAsState(target, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "code-tilt")
        val codeHeight = tiltFit(width, height, ratio, margin, angle)
        val cardWidth = with(density) { (codeHeight * ratio + 2 * margin).toDp() }
        val cardHeight = with(density) { (codeHeight + 2 * margin).toDp() }

        Box(
            Modifier
                // Unrotated, the card may be wider than the area; only its rotated footprint has to fit.
                .requiredSize(cardWidth, cardHeight)
                .graphicsLayer { rotationZ = -angle }
                .background(Color.White, RoundedCornerShape(12.dp))
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
