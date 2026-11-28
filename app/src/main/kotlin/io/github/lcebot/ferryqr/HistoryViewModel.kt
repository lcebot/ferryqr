package io.github.lcebot.ferryqr

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.lcebot.ferryqr.dispatch.CardSource
import io.github.lcebot.ferryqr.dispatch.ContentNames
import io.github.lcebot.ferryqr.dispatch.Origin
import io.github.lcebot.ferryqr.dispatch.ReadContent
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.history.HistoryEntry
import io.github.lcebot.ferryqr.history.HistoryStore
import io.github.lcebot.ferryqr.history.isSaved
import io.github.lcebot.ferryqr.history.savedForm
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The history split the way its page shows it: favorites in their order, then the other entries. */
class HistoryLists private constructor(
    val all: List<HistoryEntry>,
    private val names: Map<String, ContentNames>,
) {
    val favorites: List<HistoryEntry> = HistoryStore.favoritesOf(all)
    val recent: List<HistoryEntry> = all.filterNot { it.favorite }
    val favoritesByKey: Map<String, HistoryEntry> = favorites.associateBy { it.text }

    /** The names of the favorites, in their order, which the tiles' widths are measured from. */
    val favoriteNames: List<ContentNames> = favorites.map { names.getValue(it.text) }

    /** The other entries by the day they were scanned on, newest first, in the time zone of the phone. */
    val days: Map<LocalDate, List<HistoryEntry>> = ZoneId.systemDefault().let { zone ->
        recent.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
    }

    /** The names [entry] is shown by, or null for an entry these lists do not hold as it is, such as one deleted. */
    fun namesOf(entry: HistoryEntry): ContentNames? =
        names[entry.text]?.takeIf { it.content.format == entry.format && it.label == entry.label }

    companion object {
        /**
         * The lists of [entries] in the language of [context] when every entry is named in it
         * already, so making them takes no time, else null.
         */
        fun ifNamed(context: Context, entries: List<HistoryEntry>): HistoryLists? {
            val names = HashMap<String, ContentNames>(entries.size)
            for (entry in entries) {
                names[entry.text] = ReadContent.knownNames(context, entry.text, entry.format, entry.label) ?: return null
            }
            return HistoryLists(entries, names)
        }

        /**
         * The lists of [entries] in the language of [context], naming every entry not named yet,
         * which runs the patterns of every link FerryQR knows on each.
         */
        fun named(context: Context, entries: List<HistoryEntry>): HistoryLists =
            HistoryLists(entries, entries.associate { it.text to it.content.names(context, it.label) })
    }
}

/**
 * Entries deleted one after another, earliest first, which one undo brings back together; [id]
 * tells sessions apart.
 */
data class UndoSession(val id: Long, val entries: List<HistoryEntry>)

/** The keys the open card and dialog are kept under, so both come back after the process is let go. */
private const val SHOWING_KEY = "showing"
private const val DIALOG_KEY = "dialog"
private const val DIALOG_ENTRY_KEY = "dialog-entry"

/** What each dialog is kept as; the entry a rename shows is kept beside it. */
private const val CLEAR_DIALOG = "clear"
private const val SUGGEST_TILES_DIALOG = "suggest-tiles"
private const val RENAME_DIALOG = "rename"

/** A dialog the history page shows over its list, kept across a configuration change and the process being let go. */
sealed interface HistoryDialog {
    data object Clear : HistoryDialog

    /** Suggests tiles, which history does once, when enough favorites would fill fewer rows as tiles. */
    data object SuggestTiles : HistoryDialog

    data class Rename(val entry: HistoryEntry) : HistoryDialog
}

/**
 * What the history page shows and does: the entries, the favorites folded or not, the dialog open
 * and the card shown, and the entries deleted while their deletion can be undone.
 */
class HistoryViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val store = HistoryStore.get(app)
    val settings = AppSettings.of(app)

    /** The languages the app shows text in, which the names in the lists are made in. */
    private val languages = app.languages(viewModelScope)

    /**
     * The entries with the names each is shown by, or null while the history is still being read.
     * Naming content runs the patterns of every link FerryQR knows, so lists holding entries not
     * named yet in the current language are made off the main thread. When every entry is named,
     * as after a delete, a favorite or a reorder, or on a page opened again, the lists are made on
     * the main thread at once, so the change reaches the rows in its own frame and the page shows
     * its list from the first frame.
     */
    val lists: StateFlow<HistoryLists?> = combine(store.entries, languages) { entries, _ -> entries }
        .map { entries ->
            entries?.let { HistoryLists.ifNamed(app, it) ?: withContext(Dispatchers.Default) { HistoryLists.named(app, it) } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.entries.value?.let { HistoryLists.ifNamed(app, it) })

    private val _dialog = MutableStateFlow<HistoryDialog?>(null)
    val dialog: StateFlow<HistoryDialog?> = _dialog.asStateFlow()

    /** The entry whose card is open. */
    private val _showing = MutableStateFlow<HistoryEntry?>(null)
    val showing: StateFlow<HistoryEntry?> = _showing.asStateFlow()

    private val cards = CardHolder(app, viewModelScope, languages)

    /** The card of the entry [showing] shows, with its code drawn again. */
    val card: StateFlow<ResultCard?> = cards.card

    init {
        // A view model made again after the process was let go finds the card and the dialog it
        // kept. Those showing an entry wait for the history to be read and stay closed when the
        // entry is gone by then, or when the page has opened or closed one meanwhile.
        val dialogEntry = saved.get<String>(DIALOG_ENTRY_KEY)
        when (saved.get<String>(DIALOG_KEY)) {
            CLEAR_DIALOG -> _dialog.value = HistoryDialog.Clear
            SUGGEST_TILES_DIALOG -> _dialog.value = HistoryDialog.SuggestTiles
            RENAME_DIALOG -> viewModelScope.launch {
                val entry = dialogEntry?.let { kept -> store.find { isSaved(it, kept) } }
                val stillKept = saved.get<String>(DIALOG_KEY) == RENAME_DIALOG && saved.get<String>(DIALOG_ENTRY_KEY) == dialogEntry
                if (stillKept) show(entry?.let { HistoryDialog.Rename(it) })
            }
        }
        saved.get<String>(SHOWING_KEY)?.let { kept ->
            viewModelScope.launch {
                val entry = store.find { isSaved(it, kept) }
                if (saved.get<String>(SHOWING_KEY) == kept) open(entry)
            }
        }
    }

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
        saved[DIALOG_KEY] = when (dialog) {
            null -> null
            HistoryDialog.Clear -> CLEAR_DIALOG
            HistoryDialog.SuggestTiles -> SUGGEST_TILES_DIALOG
            is HistoryDialog.Rename -> RENAME_DIALOG
        }
        saved[DIALOG_ENTRY_KEY] = (dialog as? HistoryDialog.Rename)?.entry?.let { savedForm(it, settings) }
    }

    fun open(entry: HistoryEntry?) {
        // Another entry's card never stands in for the one opened, while the same entry opened
        // again keeps its card until it is built again.
        val same = entry != null && entry.text == _showing.value?.text
        _showing.value = entry
        saved[SHOWING_KEY] = entry?.let { savedForm(it, settings) }
        cards.show(entry?.let { CardSource.Read(it.content, Origin.HISTORY, it.time) }, keep = same)
    }

    /** Opens the entry kept as [saved], a [savedForm] from a favorite's app shortcut, once the history is read. */
    suspend fun openSaved(saved: String) {
        store.find { isSaved(it, saved) }?.let(::open)
    }

    /** Deletes [entries], which join the deletions the current undo would bring back. */
    fun delete(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        store.delete(entries)
        val session = _undo.value
        _undo.value = session?.copy(entries = session.entries + entries) ?: UndoSession(++sessions, entries)
    }

    /**
     * Brings back every entry deleted since the chance to undo last passed, and returns them in the
     * order they were deleted, the order the page flies them back in.
     */
    fun undoDeletes(): List<HistoryEntry> {
        val session = _undo.value ?: return emptyList()
        store.restore(session.entries)
        _undo.value = null
        return session.entries
    }

    /** The chance to undo the deletions so far has passed. */
    fun deletesDone() {
        _undo.value = null
    }

    fun rename(entry: HistoryEntry, name: String) {
        store.rename(entry.text, name)
        show(null)
    }

    fun setShortcut(entry: HistoryEntry, shortcut: Boolean) = store.setShortcut(entry.text, shortcut)

    fun moveToTop(entry: HistoryEntry) = store.moveFavoriteToTop(entry.text)

    fun setOrder(texts: List<String>) = store.setFavoriteOrder(texts)

    /** Clears the history, which also ends the chance to undo earlier deletions. */
    fun clear() {
        store.clear()
        deletesDone()
        show(null)
    }

    fun setFolded(folded: Boolean) {
        settings.favoritesFolded.value = folded
    }

    /** Suggests tiles, once. */
    fun suggestTiles() {
        if (settings.tilesSuggested.value) return
        settings.tilesSuggested.value = true
        show(HistoryDialog.SuggestTiles)
    }

    fun useTiles() {
        settings.favoriteTiles.value = true
        show(null)
    }
}
