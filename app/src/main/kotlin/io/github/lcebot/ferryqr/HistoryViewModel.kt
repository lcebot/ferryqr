package io.github.lcebot.ferryqr

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lcebot.ferryqr.dispatch.Kind
import io.github.lcebot.ferryqr.dispatch.ReadContent
import io.github.lcebot.ferryqr.history.HistoryEntry
import io.github.lcebot.ferryqr.history.HistoryStore
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
import kotlinx.coroutines.withContext

/**
 * The most code points a title keeps. A row or tile shows two lines at most, which even a wide
 * window fills with far fewer, so cutting content of a few kilobytes, such as a boarding pass or a
 * PDF417 code, to this many spares breaking and measuring the rest and changes nothing shown.
 */
private const val TITLE_CODE_POINTS = 1000

/** This string cut to [TITLE_CODE_POINTS] whole code points, so no surrogate pair is split. */
private fun String.bounded(): String {
    // A string of at most that many chars holds at most that many code points.
    if (length <= TITLE_CODE_POINTS || codePointCount(0, length) <= TITLE_CODE_POINTS) return this
    return substring(0, offsetByCodePoints(0, TITLE_CODE_POINTS)) + "…"
}

/**
 * How an entry is shown, worked out once from its [content] and its name, so rows,
 * tiles and the measuring of tiles only read it: its [kind], the [title] a row shows and the shorter
 * [tileTitle] a tile shows, both cut to a length two lines cannot show in full.
 */
class EntryPresentation private constructor(
    val content: ReadContent,
    private val label: String?,
    val kind: Kind,
    val title: String,
    val tileTitle: String,
) {
    /** Whether this shows [entry] as it is: the same content under the same name. */
    fun shows(entry: HistoryEntry): Boolean =
        content.text == entry.text && content.format == entry.format && label == entry.label

    companion object {
        /** The names are in the language of [context]. */
        fun of(context: Context, entry: HistoryEntry): EntryPresentation {
            val content = entry.content
            val names = content.names(context, entry.label)
            return EntryPresentation(content, entry.label, names.kind, names.rowTitle.bounded(), names.tileTitle.bounded())
        }
    }
}

/** The history split the way its page shows it: favorites in their order, then the other entries. */
class HistoryLists internal constructor(
    val all: List<HistoryEntry>,
    private val presentations: Map<String, EntryPresentation>,
) {
    val favorites: List<HistoryEntry> = HistoryStore.favoritesOf(all)
    val recent: List<HistoryEntry> = all.filterNot { it.favorite }
    val favoritesByKey: Map<String, HistoryEntry> = favorites.associateBy { it.text }

    /** How the favorites are shown, in their order, which the tiles' widths are measured from. */
    val favoritePresentations: List<EntryPresentation> = favorites.map { presentations.getValue(it.text) }

    /** The other entries by the day they were scanned on, newest first, in the time zone of the phone. */
    val days: Map<LocalDate, List<HistoryEntry>> = ZoneId.systemDefault().let { zone ->
        recent.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
    }

    /** How [entry] is shown, or null for an entry these lists do not hold as it is, such as one deleted. */
    fun presentationOf(entry: HistoryEntry): EntryPresentation? = presentations[entry.text]?.takeIf { it.shows(entry) }
}

/**
 * The presentations of entries, by content, format and name, in the language they were made in,
 * kept for the whole process: a history page opened again, and entries brought back by undo, find
 * theirs made already, and a change makes only those of entries new to it. A new language changes
 * every name, so then every presentation is made again. One build runs at a time.
 */
private object Presentations {
    /** More than history keeps, so entries deleted a while ago and brought back are still known. */
    private const val KEPT = 2048

    private var language: LocaleList? = null
    private val kept = object : LinkedHashMap<Triple<String, String, String?>, EntryPresentation>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, String?>, EntryPresentation>) =
            size > KEPT
    }

    private fun HistoryEntry.key() = Triple(text, format, label)

    /** Whether every one of [entries] has its presentation made already, so the lists take no time to make. */
    @Synchronized
    fun knows(context: Context, entries: List<HistoryEntry>): Boolean =
        context.resources.configuration.locales == language && entries.all { it.key() in kept }

    @Synchronized
    fun lists(context: Context, entries: List<HistoryEntry>): HistoryLists {
        // Read from the context the names are made with, so the presentations kept are those made in it.
        val current = context.resources.configuration.locales
        if (current != language) {
            kept.clear()
            language = current
        }
        val byText = HashMap<String, EntryPresentation>(entries.size)
        entries.forEach { entry -> byText[entry.text] = kept.getOrPut(entry.key()) { EntryPresentation.of(context, entry) } }
        return HistoryLists(entries, byText)
    }
}

/**
 * Entries deleted one after another, earliest first, which one undo brings back together; [id]
 * tells sessions apart.
 */
data class UndoSession(val id: Long, val entries: List<HistoryEntry>) {
    val texts: Set<String> = entries.mapTo(HashSet()) { it.text }
}

/** A dialog the history page shows over its list, kept across a configuration change. */
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
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = HistoryStore.get(app)
    val settings = AppSettings.of(app)

    /**
     * The languages the app shows text in. This view model outlives the page across a change of
     * language, while the names in its lists hold words in the language they were made in, so it
     * follows the language itself and makes the lists again when it changes. It listens on the
     * application, which hears of the change with its resources already in the new language and
     * before the page is made again, so the lists in the new language are on their way by the time
     * the page shows. A page passing in its own language would be heard only once made again, and
     * would show the old names until then.
     */
    private val languages = MutableStateFlow(app.resources.configuration.locales)

    private val languageChanges = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            languages.value = newConfig.locales
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
    }

    init {
        app.registerComponentCallbacks(languageChanges)
    }

    /**
     * The entries with how each is shown, or null while the history is still being read. Naming
     * content runs the patterns of every link FerryQR knows, so entries new to [Presentations] are
     * named off the main thread. When every entry is known, as after a delete, a favorite or a
     * reorder, or on a page opened again, the lists are made on the main thread at once, so the
     * change reaches the rows in its own frame and the page shows its list from the first frame.
     */
    val lists: StateFlow<HistoryLists?> = combine(store.entries, languages) { entries, _ -> entries }
        .map { entries ->
            when {
                entries == null -> null
                Presentations.knows(app, entries) -> Presentations.lists(app, entries)
                else -> withContext(Dispatchers.Default) { Presentations.lists(app, entries) }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            store.entries.value?.takeIf { Presentations.knows(app, it) }?.let { Presentations.lists(app, it) },
        )

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
        _dialog.value = null
    }

    fun setShortcut(entry: HistoryEntry, shortcut: Boolean) = store.setShortcut(entry.text, shortcut)

    fun moveToTop(entry: HistoryEntry) = store.moveFavoriteToTop(entry.text)

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

    override fun onCleared() {
        getApplication<Application>().unregisterComponentCallbacks(languageChanges)
    }
}
