package io.github.lcebot.ferryqr.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.HistoryViewModel
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.UndoSession
import io.github.lcebot.ferryqr.history.HistoryEntry

/** What the history page tells in its snackbar: a message, or the offer to undo deletions. */
@Stable
internal class HistoryMessages(private val vm: HistoryViewModel) {
    /** A message for the snackbar; each is its own, so the same message shown twice shows again. */
    private class Note(@StringRes val text: Int)

    private var note by mutableStateOf<Note?>(null)

    /** The message waiting to show, which the snackbar is shown again for whenever it changes. */
    val waiting: Any? get() = note

    fun show(@StringRes message: Int) {
        note = Note(message)
    }

    /**
     * Deletes [entries], and the offer to undo them takes the place of a message still to show.
     * Returns the id of the undo session they join.
     */
    fun delete(entries: List<HistoryEntry>): Long {
        note = null
        return vm.delete(entries)
    }

    /**
     * Shows the message waiting, or else the offer to undo [undo], until it is gone. Undoing calls
     * [onUndo]; an offer closed otherwise lets the deletions go.
     */
    suspend fun present(snackbar: SnackbarHostState, context: Context, undo: UndoSession?, onUndo: () -> Unit) {
        val shown = note
        if (shown != null) {
            snackbar.showLatest(context.getString(shown.text))
            note = null
            return
        }
        val session = undo ?: return
        val count = session.entries.size
        val result = snackbar.showLatest(
            message = if (count == 1) {
                context.getString(R.string.history_deleted)
            } else {
                context.resources.getQuantityString(R.plurals.history_deleted_count, count, count)
            },
            actionLabel = context.getString(R.string.undo),
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo() else vm.deletesDone()
    }
}

/**
 * The messages of the history page, in [snackbar]. Only one snackbar shows at a time. The offer to
 * undo follows the view model's session, so each deletion replaces it with one counting every entry
 * since the chance to undo last passed, and a page composed again offers it again. A message takes
 * its place while it shows, and the offer comes back once the message has gone. The offer stays
 * until it is acted on or closed, as a snackbar with an action does; a newer offer or message
 * cancels the one showing. Undoing hands [list] the entries it brings back, in the order they were
 * deleted, to fly back, and a session whose deletions the view model lets go lets [list] drop the
 * swipes it kept for them.
 */
@Composable
internal fun rememberHistoryMessages(vm: HistoryViewModel, snackbar: SnackbarHostState, list: MagneticList): HistoryMessages {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val dropped by vm.dropped.collectAsStateWithLifecycle()
    val messages = remember(vm) { HistoryMessages(vm) }
    LaunchedEffect(messages, messages.waiting, undo, list) {
        messages.present(snackbar, context, undo) {
            haptics.tap()
            list.departures.undone(vm.undoDeletes().map { it.text })
        }
    }
    LaunchedEffect(list, dropped) { list.departures.sessionEnded(dropped) }
    return messages
}
