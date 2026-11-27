// ModalBottomSheet, SheetState and rememberBottomSheetState are experimental Material 3 APIs.
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How a [ModalSheet] closes. Closed from its content, the sheet slides away as when dismissed rather
 * than vanishing, and its closing follows with the reason it was closed for, as it does after a
 * dismissal. Covered by another app, the activity stops and draws no frames, so the slide would only
 * end on return; once stopped, the closing follows at once instead. It follows only once, whichever
 * of the slide, the stop and the sheet's own dismissal comes first.
 */
@Stable
internal class SheetCloser<T : Any>(
    val state: SheetState,
    private val scope: CoroutineScope,
    private val onClosed: State<(T) -> Unit>,
) {
    /** Why the sheet is sliding away, while it does. */
    private var closing: T? = null
    private var finished = false

    /** Slides the sheet away, for [reason]. */
    fun close(reason: T) {
        if (closing != null || finished) return
        closing = reason
        // A drag that catches the sheet as it goes keeps it open, and the next close slides it again.
        scope.launch { state.hide() }.invokeOnCompletion {
            if (state.isVisible) closing = null else finish(reason)
        }
    }

    /** The sheet was dismissed, for [reason] unless it was already sliding away for another. */
    fun dismissed(reason: T) = finish(closing ?: reason)

    /** The activity stopped, which ends a slide at once. */
    fun stopped() {
        closing?.let(::finish)
    }

    private fun finish(reason: T) {
        if (finished) return
        finished = true
        onClosed.value(reason)
    }
}

/**
 * The closing of a sheet that opens fully or not at all, with [onClosed] following once it has
 * closed, with the reason it was closed for.
 */
@Composable
internal fun <T : Any> rememberSheetCloser(onClosed: (T) -> Unit): SheetCloser<T> {
    val state = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val closed = rememberUpdatedState(onClosed)
    val closer = remember(state, scope) { SheetCloser(state, scope, closed) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { closer.stopped() }
    return closer
}

/**
 * A modal bottom sheet that stops below the status bar, closing through [closer]; a dismissal
 * closes it for [dismissal]. Reaching the top of the window, Material's sheet pads its content by as
 * much of the status bar as it still covers, so a sheet about as tall as the window changes height
 * while it is dragged. Every change moves the place the sheet rests at and snaps it there, which a
 * drag on the handle holds off but a drag that starts on scrolling content does not, so the sheet
 * would shake under the finger instead of following it. Padded outside, the sheet keeps one height
 * and its content needs no padding at the top.
 */
@Composable
internal fun <T : Any> ModalSheet(
    closer: SheetCloser<T>,
    dismissal: T,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { closer.dismissed(dismissal) },
        sheetState = closer.state,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        content = content,
    )
}
