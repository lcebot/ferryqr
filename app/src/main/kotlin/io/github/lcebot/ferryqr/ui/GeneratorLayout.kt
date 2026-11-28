@file:OptIn(ExperimentalLayoutApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

private const val MAX_FIELD_LINES = 4

/** The parts of the create page, measured one after another since the field's lines depend on the rest. */
private enum class GeneratorSlot { CHIPS, TOOLBAR, PROBE, FIELD, CODE }

/**
 * Places the create page's format [chips], its [code] area, the [toolbar] and the text [field], each
 * [gap] apart. Upright, the code sits between the chips and the toolbar; [sideBySide], it takes one
 * half of the width and the controls the other. [field] is shown with the number of lines it is
 * given, and [fieldProbe] is the field one line tall, measured but never shown. [onSnackbarLift]
 * learns how far above the bottom of the layout the toolbar and the field end, which the snackbar
 * has to stay clear of.
 */
@Composable
internal fun GeneratorLayout(
    sideBySide: Boolean,
    gap: Dp,
    chips: @Composable () -> Unit,
    code: @Composable () -> Unit,
    toolbar: @Composable () -> Unit,
    field: @Composable (lines: Int) -> Unit,
    fieldProbe: @Composable () -> Unit,
    onSnackbarLift: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One content for each line count, made once per composition, so measuring again with the
    // same count does not compose the field again.
    val fieldWith: List<@Composable () -> Unit> = List(MAX_FIELD_LINES) { index -> @Composable { field(index + 1) } }
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val navigationBars = WindowInsets.navigationBars
    val lineHeight = MaterialTheme.typography.bodyLarge.lineHeight

    // Everything fits on one screen: the text field gives up lines first, then the code shrinks.
    // The line count follows where the keyboard is heading rather than each frame of its
    // animation, so the field changes size once, as the keyboard starts to move, and the
    // page settles in one motion with it. The keyboard also closes when a menu or the share
    // sheet takes focus, and the field then grows while the keyboard leaves, rather than
    // the code first filling the space and giving it back afterwards.
    SubcomposeLayout(modifier) { constraints ->
        val gapPx = gap.roundToPx()
        val navigation = navigationBars.getBottom(this)
        val keyboardNow = (ime.getBottom(this) - navigation).coerceAtLeast(0)
        val keyboardTarget = (imeTarget.getBottom(this) - navigation).coerceAtLeast(0)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val controlsWidth = if (sideBySide) (width - gapPx) / 2 else width
        val controls = Constraints(maxWidth = controlsWidth)
        val chipsRow = subcompose(GeneratorSlot.CHIPS, chips).map { it.measure(controls) }
        val toolbarRow = subcompose(GeneratorSlot.TOOLBAR, toolbar).map { it.measure(controls) }
        val oneLine = subcompose(GeneratorSlot.PROBE, fieldProbe).maxOfOrNull { it.measure(controls).height } ?: 0
        val chipsHeight = chipsRow.maxOfOrNull { it.height } ?: 0
        val toolbarHeight = toolbarRow.maxOfOrNull { it.height } ?: 0
        // Upright, the code's area keeps a square as wide as the page before the field takes more lines.
        val roomForField = height - keyboardTarget - chipsHeight - toolbarHeight - gapPx * 2 -
            if (sideBySide) 0 else gapPx + width
        val perLine = lineHeight.toPx().roundToInt()
        val lines = (MAX_FIELD_LINES downTo 1).firstOrNull { oneLine + perLine * (it - 1) <= roomForField } ?: 1
        val fieldRow = subcompose(GeneratorSlot.FIELD, fieldWith[lines - 1]).map { it.measure(controls) }
        val fieldHeight = fieldRow.maxOfOrNull { it.height } ?: 0
        // The page moves up with the keyboard frame by frame, as padding for it would.
        val available = (height - keyboardNow).coerceAtLeast(0)
        val codeWidth = if (sideBySide) controlsWidth else width
        val codeHeight = if (sideBySide) {
            available
        } else {
            (available - chipsHeight - toolbarHeight - fieldHeight - gapPx * 3).coerceAtLeast(0)
        }
        val codeBox = subcompose(GeneratorSlot.CODE, code).map { it.measure(Constraints.fixed(codeWidth, codeHeight)) }
        val controlsX = if (sideBySide) codeWidth + gapPx else 0
        val codeY = if (sideBySide) 0 else chipsHeight + gapPx
        val toolbarY = if (sideBySide) chipsHeight + gapPx else codeY + codeHeight + gapPx
        val fieldY = toolbarY + toolbarHeight + gapPx
        onSnackbarLift(if (sideBySide) (available - fieldY - fieldHeight).coerceAtLeast(0) else available - toolbarY)
        layout(width, height) {
            chipsRow.forEach { it.placeRelative(controlsX, 0) }
            codeBox.forEach { it.placeRelative(0, codeY) }
            toolbarRow.forEach { it.placeRelative(controlsX, toolbarY) }
            fieldRow.forEach { it.placeRelative(controlsX, fieldY) }
        }
    }
}
