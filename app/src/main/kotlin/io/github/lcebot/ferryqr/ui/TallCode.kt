package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.github.lcebot.ferryqr.generate.CodeDrawing
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A long code's height ratio is rounded to steps this fine, so a slightly different area does not draw it again. */
private const val RATIO_STEPS = 20

/** How much taller than wide a long code drawn [height] by [width] is, rounded to a step. */
internal fun tallRatio(height: Dp, width: Dp): Float = ((height / width) * RATIO_STEPS).roundToInt() / RATIO_STEPS.toFloat()

/**
 * [drawing] drawn again for a height [ratio] times its width while [wanted], off the main thread,
 * since a long code drawn as tall as a screen takes a while. It is kept by the content drawn rather
 * than by the drawing's instance, so a card built again keeps it, and kept while not [wanted], so it
 * shows at once when wanted again. While a new ratio is drawn, the drawing for the last one stays;
 * it is null before the first drawing of the content is ready, without [drawing], or while not [wanted].
 */
@Composable
internal fun rememberTallCode(drawing: CodeDrawing?, ratio: Float, wanted: Boolean = true): Bitmap? {
    // Other content drops the old drawing at once, which would show the old content until replaced.
    var tall by remember(drawing?.text, drawing?.type) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(drawing?.text, drawing?.type, ratio, wanted) {
        if (drawing == null || !wanted) return@LaunchedEffect
        tall = drawing.drawnTall(ratio) ?: withContext(Dispatchers.Default) { drawing.tall(ratio) }
    }
    return tall.takeIf { drawing != null && wanted }
}

/**
 * A long code filling its area exactly, drawn again at the area's own shape so its bars or rows
 * grow rather than a scaled picture; until that drawing is ready, [code] is scaled to fill.
 */
@Composable
internal fun FilledTallCode(code: Bitmap, drawing: CodeDrawing?, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val tall = rememberTallCode(drawing, tallRatio(maxHeight, maxWidth)) ?: code
        PlainCode(tall, Modifier.fillMaxSize(), stretch = true)
    }
}
