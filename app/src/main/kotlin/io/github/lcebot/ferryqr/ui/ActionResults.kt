package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.ActionResult

/**
 * What follows an action on a result card, the same on every screen that shows one: a haptic for
 * how it went, a message where the system gives none, and [onHandoff] once another app took over.
 * [message] shows a message the way the screen shows them, as a snackbar or, where there is no
 * snackbar host, as a toast.
 */
@Composable
internal fun rememberActionResultHandler(onHandoff: () -> Unit, message: (Int) -> Unit): (ActionResult) -> Unit {
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

/** Shows [text] as a short toast, for sheets and dialogs that have no snackbar host. */
internal fun Context.toast(@StringRes text: Int) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
