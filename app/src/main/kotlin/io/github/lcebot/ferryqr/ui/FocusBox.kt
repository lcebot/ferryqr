@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Where the user tapped to focus; [id] tells one tap from the next on the same spot. */
internal data class FocusTap(val at: Offset, val id: Int)

/** The focus box's side, within the 48dp to 96dp the AOSP camera app draws its focus ring at. */
private val FOCUS_BOX_SIZE = 72.dp

/** How much larger the box starts before it closes in on the tapped point. */
private const val FOCUS_BOX_ENTER_SCALE = 1.4f

private val FOCUS_BOX_STROKE = 2.dp
private val FOCUS_BOX_CORNER = 4.dp

/** The ticks pointing in from the middle of each side, as in the iOS camera's focus square. */
private val FOCUS_BOX_TICK = 6.dp

/**
 * The square camera apps draw where a tap set the focus, with a short tick pointing in from the
 * middle of each side. It closes in on the point as it fades in, and fades out once the camera
 * lets go of that focus, whatever the reason. A new tap replaces it at once.
 */
@Composable
internal fun FocusBox(tap: FocusTap?, held: StateFlow<Boolean>) {
    tap ?: return
    key(tap) {
        // Starts hidden, so the box closes in on the point as it appears.
        val shown = remember { MutableTransitionState(false) }
        LaunchedEffect(Unit) {
            shown.targetState = true
            // Waits on the focus itself rather than on what the page last saw of it, so a focus let
            // go before the page saw it held still fades the box.
            held.first { !it }
            shown.targetState = false
        }
        // A fixed colour keeps one light tone in both themes, which the camera image behind needs.
        val color = MaterialTheme.colorScheme.primaryFixed
        AnimatedVisibility(
            visibleState = shown,
            enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec(), initialScale = FOCUS_BOX_ENTER_SCALE) +
                fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            modifier = Modifier.absoluteOffset {
                val half = FOCUS_BOX_SIZE.toPx() / 2
                IntOffset((tap.at.x - half).roundToInt(), (tap.at.y - half).roundToInt())
            },
        ) {
            Canvas(Modifier.size(FOCUS_BOX_SIZE)) {
                val stroke = FOCUS_BOX_STROKE.toPx()
                // The stroke is centred on the outline, so the outline sits half a stroke in from the edge.
                val inset = stroke / 2
                drawRoundRect(
                    color,
                    topLeft = Offset(inset, inset),
                    size = size.copy(width = size.width - stroke, height = size.height - stroke),
                    cornerRadius = CornerRadius(FOCUS_BOX_CORNER.toPx()),
                    style = Stroke(stroke),
                )
                val tick = FOCUS_BOX_TICK.toPx()
                val mid = center
                drawLine(color, Offset(mid.x, inset), Offset(mid.x, inset + tick), stroke)
                drawLine(color, Offset(mid.x, size.height - inset), Offset(mid.x, size.height - inset - tick), stroke)
                drawLine(color, Offset(inset, mid.y), Offset(inset + tick, mid.y), stroke)
                drawLine(color, Offset(size.width - inset, mid.y), Offset(size.width - inset - tick, mid.y), stroke)
            }
        }
    }
}
