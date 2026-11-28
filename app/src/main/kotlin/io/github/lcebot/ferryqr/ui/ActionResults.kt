package io.github.lcebot.ferryqr.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import kotlinx.coroutines.CoroutineScope
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
 * what shows a message in the sheet and what closes it. Actions are followed by [ActionResults].
 * Once another app took over, or when closed from its content, the sheet closes as a [SheetCloser]
 * closes it, and [onClosed] follows with why it closed.
 */
@Composable
internal fun CardSheet(
    onClosed: (CloseReason) -> Unit,
    content: @Composable (onAction: (CardAction) -> Unit, onMessage: (Int) -> Unit, onClose: () -> Unit) -> Unit,
) {
    val activity = LocalActivity.current ?: return
    val closer = rememberSheetCloser(onClosed)
    val snackbar = remember { SnackbarHostState() }
    val results = rememberActionResultHandler(snackbar) { closer.close(CloseReason.HANDOFF) }
    // A second tap before the page an action opened covers the card runs nothing, so no page opens
    // twice. Copying, or an action nothing takes, leaves the card in front, so those can run again at once.
    val once = rememberOpenOnce()
    val onAction: (CardAction) -> Unit = remember(activity, results, once) {
        { action ->
            once {
                val result = results.run(activity, action)
                if (result == null || result == ActionResult.Copied || result == ActionResult.Unavailable) once.resumed()
            }
        }
    }
    val onMessage: (Int) -> Unit = remember(results) { { message -> results.message(message) } }
    ModalSheet(closer, dismissal = CloseReason.DISMISSED) {
        Box(Modifier.fillMaxWidth()) {
            content(onAction, onMessage) { closer.close(CloseReason.DISMISSED) }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * What follows an action handed content to, on a result card and on the create page alike: a haptic
 * for how it went, and a message where the system gives none, shown in place of the last one.
 */
@Stable
internal class ActionResults(
    private val context: Context,
    private val haptics: Haptics,
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val onHandoff: () -> Unit,
) {
    /** Shows [text] in the snackbar, in place of any message showing. */
    fun message(@StringRes text: Int) = show(context.getString(text))

    private fun show(text: String) {
        scope.launch { snackbar.showLatest(text) }
    }

    /**
     * Runs [action] in [activity] and follows how it went, which it returns. A blocked action runs
     * nothing and says why, and returns null.
     */
    fun run(activity: Activity, action: CardAction): ActionResult? {
        action.blocked?.let { reason ->
            haptics.reject()
            show(reason)
            return null
        }
        return action.run(activity).also(::follow)
    }

    private fun follow(result: ActionResult) {
        when (result) {
            ActionResult.Handoff -> {
                haptics.tap()
                onHandoff()
            }
            ActionResult.Copied -> {
                haptics.confirm()
                // Android 13 and later confirm clipboard writes with their own overlay.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) message(R.string.copied)
            }
            ActionResult.Unavailable -> {
                haptics.reject()
                message(R.string.no_app)
            }
            ActionResult.Done -> haptics.tap()
        }
    }
}

/**
 * The [ActionResults] of a screen whose messages show in [snackbar]; [onHandoff] follows once another
 * app took over, as a card's sheet then closes.
 */
@Composable
internal fun rememberActionResultHandler(snackbar: SnackbarHostState, onHandoff: () -> Unit = {}): ActionResults {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val handoff by rememberUpdatedState(onHandoff)
    return remember(context, haptics, snackbar, scope) { ActionResults(context, haptics, snackbar, scope) { handoff() } }
}
