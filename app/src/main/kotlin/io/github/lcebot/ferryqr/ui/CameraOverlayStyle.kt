@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

/**
 * How what is drawn over the camera's picture looks, live or frozen. The picture may be light or
 * dark whatever the theme, so the marks on it take the fixed accent roles, which keep one light tone
 * in both themes, where the plain roles would turn dark with a dark theme. Text and the brackets of a
 * code being read are drawn straight on the picture in white, and pills behind text are a
 * translucent black, so either reads on a light picture as on a dark one.
 */
internal object CameraOverlayStyle {
    /** A code's marker, the size of a medium icon button, as [CodeMarker] draws it. */
    val markerSize: DpSize = IconButtonDefaults.mediumContainerSize()

    /** The fill of the markers and the success badge, and the colour of the focus box. */
    val accent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryFixed

    /** Icons on [accent]. */
    val onAccent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimaryFixed

    /** The brackets on a confirmed code, dimmer than the badge they hold, which sets the two apart. */
    val confirmedBrackets: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryFixedDim

    /** Text and the brackets of a code being read, drawn straight on the picture. */
    val onPicture: Color = Color.White

    /** The pills behind the hint and the zoom. */
    val pill: Color = Color.Black.copy(alpha = 0.5f)
}

/**
 * How a picture of [frame] pixels shows in a view of [view] pixels that it fills, cut evenly on both
 * sides where it overflows: the viewfinder shows the camera's picture so, and ContentScale.Crop with
 * centred alignment a frozen frame. Maps the picture's pixels onto the view's.
 */
internal class CenterCrop(frame: IntSize, private val view: Size) {
    private val scale = max(view.width / frame.width, view.height / frame.height)
    private val dx = (view.width - frame.width * scale) / 2f
    private val dy = (view.height - frame.height * scale) / 2f

    fun toView(x: Float, y: Float): Offset = Offset(x * scale + dx, y * scale + dy)

    /**
     * [toView], kept [margin] in from the view's edges, so a mark that reaches [margin] from its
     * centre stays whole in view over a code near a cropped edge. A code's marker is placed so live
     * and frozen alike, so it keeps its place as the frame freezes.
     */
    fun toViewWithin(x: Float, y: Float, margin: Float): Offset {
        val at = toView(x, y)
        return Offset(
            at.x.coerceIn(margin, max(margin, view.width - margin)),
            at.y.coerceIn(margin, max(margin, view.height - margin)),
        )
    }

    fun toView(left: Float, top: Float, right: Float, bottom: Float): Rect = Rect(toView(left, top), toView(right, bottom))
}

/**
 * The marker on a found code, one look for the scanner and the picker, so a found code keeps its
 * marker as the frame freezes. With [onClick] it is the button that picks its code, and only while
 * [enabled]; one disabled keeps its colours, so the copy of a picker fading out after a pick fades
 * as it was rather than greying first.
 */
@Composable
internal fun CodeMarker(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val shapes = IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.mediumPressedShape)
    val glyph = @Composable {
        Icon(
            painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
        )
    }
    if (onClick == null) {
        Surface(
            shape = shapes.shape,
            color = CameraOverlayStyle.accent,
            contentColor = CameraOverlayStyle.onAccent,
            modifier = modifier.size(CameraOverlayStyle.markerSize),
        ) {
            Box(contentAlignment = Alignment.Center) { glyph() }
        }
    } else {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            shapes = shapes,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = CameraOverlayStyle.accent,
                contentColor = CameraOverlayStyle.onAccent,
                disabledContainerColor = CameraOverlayStyle.accent,
                disabledContentColor = CameraOverlayStyle.onAccent,
            ),
            modifier = modifier.size(CameraOverlayStyle.markerSize),
        ) {
            glyph()
        }
    }
}
