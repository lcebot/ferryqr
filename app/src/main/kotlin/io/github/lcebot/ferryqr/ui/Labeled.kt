@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.lcebot.ferryqr.ui

import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Icon-only controls name themselves in a tooltip on long press, as well as to screen readers. */
@Composable
fun Labeled(@StringRes label: Int, content: @Composable () -> Unit) = Labeled(stringResource(label), content)

@Composable
fun Labeled(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}
