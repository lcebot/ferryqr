// ModalBottomSheet and SheetState are experimental Material 3 APIs.
@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A modal bottom sheet that stops below the status bar. Reaching the top of the window, Material's
 * sheet pads its content by as much of the status bar as it still covers, so a sheet about as tall
 * as the window changes height while it is dragged. Every change moves the place the sheet rests at
 * and snaps it there, which a drag on the handle holds off but a drag that starts on scrolling
 * content does not, so the sheet would shake under the finger instead of following it. Padded
 * outside, the sheet keeps one height and its content needs no padding at the top.
 */
@Composable
fun ModalSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        content = content,
    )
}
