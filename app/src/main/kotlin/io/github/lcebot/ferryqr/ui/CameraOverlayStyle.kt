@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    /**
     * A code's marker, the size of a medium icon button: the scanner's found marker and the picker's
     * button are one size, so a found code keeps its marker as the frame freezes.
     */
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
internal class CenterCrop(frame: IntSize, view: Size) {
    private val scale = max(view.width / frame.width, view.height / frame.height)
    private val dx = (view.width - frame.width * scale) / 2f
    private val dy = (view.height - frame.height * scale) / 2f

    fun toView(x: Float, y: Float): Offset = Offset(x * scale + dx, y * scale + dy)

    fun toView(left: Float, top: Float, right: Float, bottom: Float): Rect = Rect(toView(left, top), toView(right, bottom))
}
