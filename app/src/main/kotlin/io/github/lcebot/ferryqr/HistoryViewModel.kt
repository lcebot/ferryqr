package io.github.lcebot.ferryqr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lcebot.ferryqr.history.HistoryEntry
import io.github.lcebot.ferryqr.history.HistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** The history split the way its page shows it: favorites in their order, then the other entries. */
class HistoryLists(val all: List<HistoryEntry>) {
    val favorites: List<HistoryEntry> = HistoryStore.favoritesOf(all)
    val recent: List<HistoryEntry> = all.filterNot { it.favorite }
    val favoritesByKey: Map<String, HistoryEntry> = favorites.associateBy { it.text }
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

    private val _pendingUndo = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Entries deleted while undoing can still bring them back. A tile swiped away waits out of sight
     * where it flew to until then, so undoing flies it back into its place.
     */
    val pendingUndo: StateFlow<Set<String>> = _pendingUndo.asStateFlow()

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

    fun delete(entry: HistoryEntry) {
        store.delete(entry)
        _pendingUndo.update { it + entry.text }
    }

    fun undoDelete(entry: HistoryEntry) {
        store.restore(entry)
        _pendingUndo.update { it - entry.text }
    }

    /** The chance to undo deleting [entry] has passed. */
    fun deleteDone(entry: HistoryEntry) {
        _pendingUndo.update { it - entry.text }
    }

    /** Makes [entry] a favorite or stops it being one; making it one asks for its name first where the settings say so and it has none. */
    fun setFavorite(entry: HistoryEntry, favorite: Boolean, hasTitle: Boolean) {
        if (favorite && settings.namesFavorites.value && !hasTitle) {
            _dialog.value = HistoryDialog.Name(entry)
        } else {
            store.setFavorite(entry.text, entry.format, favorite)
        }
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

    fun clear() {
        store.clear()
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
