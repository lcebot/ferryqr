package io.github.lcebot.ferryqr.ui

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.ActionResult
import io.github.lcebot.ferryqr.dispatch.CardAction
import kotlinx.coroutines.launch

/** Why a card's sheet closed: another app took over after one of its actions, or it was dismissed. */
enum class CloseReason { HANDOFF, DISMISSED }

/**
 * A [ModalSheet] holding a result card, with a snackbar host of its own along the bottom of the
 * sheet. The sheet is a window over the page, so a snackbar of the page would show under it. The
 * sheet keeps its content clear of the navigation bar, and the host with it, and the host shows one
 * message at a time, each in place of the last.
 *
 * [content] is given what runs one of the card's actions, once until the card is back in front,
 * what shows a message in the sheet and what closes it. Every sheet with a card follows an action
 * the same way: a haptic for how it went, and a message where the system gives none. Once another
 * app took over, or when closed from its content, the sheet closes as a [SheetCloser] closes it, and
 * [onClosed] follows with why it closed.
 */
@Composable
internal fun CardSheet(
    onClosed: (CloseReason) -> Unit,
    content: @Composable (onAction: (CardAction) -> Unit, onMessage: (Int) -> Unit, onClose: () -> Unit) -> Unit,
) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val closer = rememberSheetCloser(onClosed)
    val snackbar = remember { SnackbarHostState() }
    val onMessage: (Int) -> Unit = remember(snackbar, scope, context) {
        { message -> scope.launch { snackbar.showLatest(context.getString(message)) } }
    }
    val onResult = rememberActionResultHandler({ closer.close(CloseReason.HANDOFF) }, onMessage)
    // A second tap before the page an action opened covers the card runs nothing, so no page opens
    // twice. Copying, or an action nothing takes, leaves the card in front, so those can run again at once.
    val once = rememberOpenOnce()
    val onAction: (CardAction) -> Unit = remember(activity, onResult, once) {
        { action ->
            once {
                val result = action.run(activity)
                if (result == ActionResult.Copied || result == ActionResult.Unavailable) once.resumed()
                onResult(result)
            }
        }
    }
    ModalSheet(closer, dismissal = CloseReason.DISMISSED) {
        Box(Modifier.fillMaxWidth()) {
            content(onAction, onMessage) { closer.close(CloseReason.DISMISSED) }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** What follows an action on a result card, with [message] showing a message in the card's sheet. */
@Composable
private fun rememberActionResultHandler(onHandoff: () -> Unit, message: (Int) -> Unit): (ActionResult) -> Unit {
    val haptics = rememberHaptics()
    val handoff by rememberUpdatedState(onHandoff)
    val show by rememberUpdatedState(message)
    return remember(haptics) {
        { result ->
            when (result) {
                ActionResult.Handoff -> {
                    haptics.tap()
                    handoff()
                }
                ActionResult.Copied -> {
                    haptics.confirm()
                    // Android 13 and later confirm clipboard writes with their own overlay.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) show(R.string.copied)
                }
                ActionResult.Unavailable -> {
                    haptics.reject()
                    show(R.string.no_app)
                }
                ActionResult.Done -> haptics.tap()
            }
        }
    }
}
