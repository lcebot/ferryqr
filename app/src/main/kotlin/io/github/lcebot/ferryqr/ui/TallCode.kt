package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A long code's height ratio is rounded to steps this fine, so a slightly different area does not draw it again. */
private const val RATIO_STEPS = 20

/** How much taller than wide a long code drawn [height] by [width] is, rounded to a step. */
internal fun tallRatio(height: Dp, width: Dp): Float = ((height / width) * RATIO_STEPS).roundToInt() / RATIO_STEPS.toFloat()

/**
 * [code] drawn again by [draw] for a height [ratio] times its width, off the main thread, since a
 * long code drawn as tall as a screen takes a while. While a new ratio is drawn, the drawing for
 * the last one stays; it is null before the first drawing of [code] is ready, or without [draw].
 */
@Composable
internal fun rememberTallCode(code: Bitmap, ratio: Float, draw: ((Float) -> Bitmap?)?): Bitmap? {
    // A new code drops the old drawing at once, which would show the old content until replaced.
    var tall by remember(code) { mutableStateOf<Bitmap?>(null) }
    val drawing by rememberUpdatedState(draw)
    LaunchedEffect(code, ratio, draw != null) {
        val by = drawing ?: return@LaunchedEffect
        tall = withContext(Dispatchers.Default) { by(ratio) }
    }
    return tall.takeIf { draw != null }
}

/**
 * A long code filling its area exactly, drawn again at the area's own shape so its bars or rows
 * grow rather than a scaled picture; until that drawing is ready, [code] is scaled to fill.
 */
@Composable
internal fun FilledTallCode(code: Bitmap, draw: ((Float) -> Bitmap?)?, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val tall = rememberTallCode(code, tallRatio(maxHeight, maxWidth), draw) ?: code
        PlainCode(tall, Modifier.fillMaxSize(), stretch = true)
    }
}
