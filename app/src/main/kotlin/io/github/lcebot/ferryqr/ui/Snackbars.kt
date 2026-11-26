package io.github.lcebot.ferryqr.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

/**
 * Shows [message] in place of the snackbar on screen. The host queues snackbars and shows each until
 * it times out, so without this a message would wait seconds behind an older one it makes stale.
 * One with an action stays until the action is taken or it is closed, which [withDismissAction]
 * offers a button for.
 */
internal suspend fun SnackbarHostState.showLatest(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(message, actionLabel, withDismissAction, duration)
}
