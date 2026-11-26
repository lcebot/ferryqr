package io.github.lcebot.ferryqr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lcebot.ferryqr.history.HistoryEntry
import io.github.lcebot.ferryqr.history.HistoryStore
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The history split the way its page shows it: favorites in their order, then the other entries. */
class HistoryLists(val all: List<HistoryEntry>) {
    val favorites: List<HistoryEntry> = HistoryStore.favoritesOf(all)
    val recent: List<HistoryEntry> = all.filterNot { it.favorite }
    val favoritesByKey: Map<String, HistoryEntry> = favorites.associateBy { it.text }

    /** The other entries by the day they were scanned on, newest first, in the time zone of the phone. */
    val days: Map<LocalDate, List<HistoryEntry>> = ZoneId.systemDefault().let { zone ->
        recent.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
    }
}

/** Entries deleted one after another, which one undo brings back together; [id] tells sessions apart. */
data class UndoSession(val id: Long, val entries: List<HistoryEntry>) {
    val texts: Set<String> = entries.mapTo(HashSet()) { it.text }
}

/** A dialog the history page shows over its list, kept across a configuration change. */
sealed interface HistoryDialog {
    data object Clear : HistoryDialog

    /** Suggests tiles, which history does once, when enough favorites would fill fewer rows as tiles. */
    data object SuggestTiles : HistoryDialog

    /** Names an entry as it becomes a favorite. */
    data class Name(val entry: HistoryEntry) : HistoryDialog

    data class Rename(val entry: HistoryEntry) : HistoryDialog
}

/**
 * What the history page shows and does: the entries, the favorites folded or not, the dialog open
 * and the card shown, and the entries deleted while their deletion can be undone.
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = HistoryStore.get(app)
    val settings = AppSettings.of(app)

    /** The entries, or null while the history is still being read. */
    val lists: StateFlow<HistoryLists?> = store.entries
        .map { entries -> entries?.let(::HistoryLists) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.entries.value?.let(::HistoryLists))

    private val _dialog = MutableStateFlow<HistoryDialog?>(null)
    val dialog: StateFlow<HistoryDialog?> = _dialog.asStateFlow()

    /** The entry whose card is open. */
    private val _showing = MutableStateFlow<HistoryEntry?>(null)
    val showing: StateFlow<HistoryEntry?> = _showing.asStateFlow()

    private var sessions = 0L
    private val _undo = MutableStateFlow<UndoSession?>(null)

    /**
     * The deletions one undo can still bring back, or null. It outlives the page's composition, so
     * a page composed again offers the undo again. A tile or row swiped away waits out of sight
     * where it flew to until it ends, so undoing flies it back into its place.
     */
    val undo: StateFlow<UndoSession?> = _undo.asStateFlow()

    /** Whether a history file that could not be read in full waits to be exported or let go. */
    val damaged: StateFlow<Boolean> = store.damaged

    /** Writes that file to [out]; false when that fails. Kept until [forgetDamaged]. */
    fun exportDamaged(out: OutputStream): Boolean = store.exportDamaged(out)

    fun forgetDamaged() = store.forgetDamaged()

    fun show(dialog: HistoryDialog?) {
        _dialog.value = dialog
    }

    fun open(entry: HistoryEntry?) {
        _showing.value = entry
    }

    /** Opens the entry with [text], from a favorite's app shortcut, once the history is read. */
    suspend fun open(text: String) {
        store.find(text)?.let(::open)
    }

    /** Deletes [entry], which joins the deletions the current undo would bring back. */
    fun delete(entry: HistoryEntry) {
        store.delete(entry)
        val session = _undo.value
        _undo.value = session?.copy(entries = session.entries + entry) ?: UndoSession(++sessions, listOf(entry))
    }

    /** Brings back every entry deleted since the chance to undo last passed. */
    fun undoDeletes() {
        val session = _undo.value ?: return
        store.restore(session.entries)
        _undo.value = null
    }

    /** The chance to undo the deletions so far has passed. */
    fun deletesDone() {
        _undo.value = null
    }

    /**
     * Makes [entry] a favorite or stops it being one; making it one asks for its name first where
     * [HistoryStore.asksName] says so. False when it asks, as nothing has changed yet.
     */
    fun setFavorite(entry: HistoryEntry, favorite: Boolean): Boolean {
        if (favorite && store.asksName(entry.text, entry.format, entry.label)) {
            _dialog.value = HistoryDialog.Name(entry)
            return false
        }
        store.setFavorite(entry.text, entry.format, favorite)
        return true
    }

    fun name(entry: HistoryEntry, name: String) {
        store.setFavorite(entry.text, entry.format, true, name)
        _dialog.value = null
    }

    fun rename(entry: HistoryEntry, name: String) {
        store.rename(entry.text, name)
        _dialog.value = null
    }

    fun setShortcut(entry: HistoryEntry, shortcut: Boolean) = store.setShortcut(entry.text, shortcut)

    fun moveToTop(entry: HistoryEntry) = store.moveFavoriteToTop(entry.text)

    /** Moves the favorite [entry] [by] places, later for a positive count, from wherever it is now. */
    fun move(entry: HistoryEntry, by: Int) = store.moveFavorite(entry.text, by)

    fun setOrder(texts: List<String>) = store.setFavoriteOrder(texts)

    /** Clears the history, which also ends the chance to undo earlier deletions. */
    fun clear() {
        store.clear()
        deletesDone()
        _dialog.value = null
    }

    fun setFolded(folded: Boolean) {
        settings.favoritesFolded.value = folded
    }

    /** Suggests tiles, once. */
    fun suggestTiles() {
        if (settings.tilesSuggested.value) return
        settings.tilesSuggested.value = true
        _dialog.value = HistoryDialog.SuggestTiles
    }

    fun useTiles() {
        settings.favoriteTiles.value = true
        _dialog.value = null
    }
}
