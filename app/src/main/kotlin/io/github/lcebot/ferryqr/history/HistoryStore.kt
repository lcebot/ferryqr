package io.github.lcebot.ferryqr.history

import android.app.Application
import android.content.Context
import android.os.LocaleList
import android.util.AtomicFile
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.dispatch.ContentNames
import io.github.lcebot.ferryqr.dispatch.Kind
import io.github.lcebot.ferryqr.dispatch.ReadContent
import io.github.lcebot.ferryqr.languages
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Entries other than favorites beyond this many are dropped, oldest first. */
private const val MAX_ENTRIES = 500

/**
 * [favoritedAt] is when the entry became a favorite, or 0 while it is not one. [label] is a name the
 * user gave the entry, shown in place of its content. [shortcutAt] is when the user added the
 * favorite to the app shortcuts, or 0 while it is not among them. [favoriteRank] places a favorite
 * among the others, lowest first; favorites of the same rank, as all are until the user reorders
 * them, show the newest first.
 */
data class HistoryEntry(
    val text: String,
    val format: String,
    val time: Long,
    val favoritedAt: Long = 0L,
    val label: String? = null,
    val shortcutAt: Long = 0L,
    val favoriteRank: Int = 0,
) {
    val favorite: Boolean get() = favoritedAt > 0L
    val shortcut: Boolean get() = shortcutAt > 0L

    /** What the entry holds, read once for every place that shows, names or opens it. */
    val content: ReadContent get() = ReadContent.of(text, format)
}

/** Marks what is kept of an entry outside the app: its content itself, or only a digest of it. */
private const val SAVED_TEXT = "text:"
private const val SAVED_DIGEST = "sha256:"

/** The SHA-256 digest of [text], in hexadecimal. */
internal fun digestOf(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * What is kept of [entry] where the system holds it outside the app, as saved state while the
 * process is gone and app shortcuts are: its content itself, unless it is of a sensitive kind, such
 * as a two-factor key, that [settings] do not keep in history, when only a digest goes there. Either
 * finds the entry again through [isSaved] once the history is read.
 */
fun savedForm(entry: HistoryEntry, settings: AppSettings): String {
    val kind = entry.content.sensitiveKind
    return if (kind == null || settings.keeps(kind).value) SAVED_TEXT + entry.text else SAVED_DIGEST + digestOf(entry.text)
}

/** Whether [entry] is the one kept as [saved], a [savedForm]. */
fun isSaved(entry: HistoryEntry, saved: String): Boolean = when {
    saved.startsWith(SAVED_TEXT) -> entry.text == saved.removePrefix(SAVED_TEXT)
    saved.startsWith(SAVED_DIGEST) -> digestOf(entry.text) == saved.removePrefix(SAVED_DIGEST)
    else -> false
}

/** What asking to make content a favorite, or to stop it being one, came to. */
sealed interface FavoriteRequest {
    /** The change is made. */
    data object Done : FavoriteRequest

    /** Nothing has changed yet: the content becomes a favorite once named, the name starting from [initialName]. */
    data class NeedsName(val initialName: String) : FavoriteRequest
}

/**
 * Scan history kept in a single JSON file in app-private storage. Newest entries come first, and
 * scanning the same content again moves it to the top instead of adding a duplicate, keeping what
 * the user set on it. Favorites survive both the size limit and clearing the history.
 *
 * Every change applies at once, in the order it was made, on the thread that made it, so the next
 * frame already shows it; the file is written afterwards, one write at a time, each with the latest
 * entries. Changes made while the file is still being read wait for it and then apply in order.
 */
class HistoryStore private constructor(private val context: Context) {

    private val file = AtomicFile(File(context.filesDir, "history.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** Changes made before the file was read, applied once it is. Guarded by [lock]. */
    private val pending = ArrayList<(List<HistoryEntry>) -> List<HistoryEntry>>()

    /** Asks the writer to save the latest entries; requests made while it writes merge into one. */
    private val saves = Channel<Unit>(Channel.CONFLATED)

    /**
     * Whether what the shortcuts show or carry changed since they were last brought in line, as
     * [shortcutView] tells, or a change of language or of the settings for sensitive kinds makes it.
     * Guarded by [lock].
     */
    private var shortcutsStale = false

    /** The languages the shortcuts were last labelled in, null until they first are. */
    @Volatile
    private var shortcutLanguages: LocaleList? = null

    private val _entries = MutableStateFlow<List<HistoryEntry>?>(null)

    /** The entries, newest first, or null while the file is still being read. */
    val entries: StateFlow<List<HistoryEntry>?> = _entries.asStateFlow()

    /**
     * The history file as it was when it could not be read in full, kept aside so it can still be
     * exported for recovering what it held, until it is exported or let go. Saving goes on with the
     * entries that could be read, so the app keeps working meanwhile.
     */
    private val unreadable = File(context.filesDir, "history.unreadable.json")

    private val _damaged = MutableStateFlow(false)

    /** Whether a history file that could not be read in full waits to be exported or let go. */
    val damaged: StateFlow<Boolean> = _damaged.asStateFlow()

    init {
        scope.launch {
            val read = runCatching { read() }.getOrElse { FileRead(emptyList(), writable = false) }
            _damaged.value = unreadable.exists()
            synchronized(lock) {
                var current = read.entries
                pending.forEach { current = limited(it(current)) }
                pending.clear()
                _entries.value = current
                if (current != read.entries) saves.trySend(Unit)
            }
            // A file that could neither be read nor set aside holds the only copy of the history, so
            // for the rest of the process nothing is written over it, and the shortcuts stay as they
            // were with it. The entries still change in memory, so the app keeps working meanwhile.
            if (!read.writable) return@launch
            // Shortcuts outlive the process, so they are brought in line with the file once it is read.
            syncShortcuts(_entries.value.orEmpty())
            // The entries last written. A pass asked for only to bring the shortcuts in line, as a
            // change of language asks for one, finds them unchanged and writes nothing.
            var written: List<HistoryEntry>? = null
            for (request in saves) {
                // The flag is taken together with the entries it was raised for, so a favorites change
                // made while this pass writes keeps its flag for the next pass, which saves its entries.
                val (snapshot, stale) = synchronized(lock) { _entries.value to shortcutsStale.also { shortcutsStale = false } }
                if (snapshot == null) continue
                if (snapshot !== written) {
                    written = snapshot
                    // One failed write leaves the next to try again, rather than ending saving for good.
                    try {
                        save(snapshot)
                    } catch (e: Exception) {
                        // The entries stay in memory, and the next change writes them all again.
                    }
                }
                if (stale) syncShortcuts(snapshot)
            }
        }
        // Unnamed shortcuts are labelled with titles in the app's language, so a new language labels
        // them again. Before they are first labelled there is nothing to label again, since that
        // first time takes the language of the moment.
        scope.launch {
            (context as Application).languages(this).collect { locales ->
                val labelled = shortcutLanguages
                if (labelled != null && locales != labelled) markShortcutsStale()
            }
        }
        // Each shortcut carries its entry in the saved form, which depends on whether the settings
        // keep the entry's kind, so a change to any of them brings the shortcuts in line again, and a
        // pinned shortcut carries the text itself only while its kind is kept.
        scope.launch {
            AppSettings.of(context).kept.drop(1).collect { markShortcutsStale() }
        }
    }

    /** Asks the writer to bring the shortcuts in line, though the entries may be unchanged. */
    private fun markShortcutsStale() {
        synchronized(lock) { shortcutsStale = true }
        saves.trySend(Unit)
    }

    /** Shortcuts the launcher refuses leave the history and its saving untouched. */
    private fun syncShortcuts(entries: List<HistoryEntry>) {
        shortcutLanguages = context.resources.configuration.locales
        try {
            FavoriteShortcuts.update(context, favoritesOf(entries))
        } catch (e: Exception) {
            // The shortcuts are brought in line again with the next change to what they show.
        }
    }

    /**
     * What the app shortcuts show of [entries], which brings them in line only when it changes: the
     * shortcut favorites in their order, each by its content, short title and kind, and every
     * favorite by what names it, which decides the pinned shortcuts that stay enabled and how they
     * are labelled. When a favorite was made or how the favorites around the shortcuts are ordered
     * changes nothing shown, so neither counts.
     */
    private fun shortcutView(entries: List<HistoryEntry>): ShortcutView {
        val favorites = favoritesOf(entries)
        return ShortcutView(
            shortcuts = favorites.filter { it.shortcut }.take(FavoriteShortcuts.MAX).map { entry ->
                val names = entry.content.names(context, entry.label)
                ShortcutFace(entry.text, names.shortTitle, names.kind)
            },
            favorites = favorites.mapTo(HashSet()) { FavoriteName(it.text, it.format, it.label) },
        )
    }

    private data class ShortcutFace(val text: String, val shortTitle: String, val kind: Kind)

    /** A favorite's content and its name, from which a pinned shortcut's title and kind follow. */
    private data class FavoriteName(val text: String, val format: String, val label: String?)

    private data class ShortcutView(val shortcuts: List<ShortcutFace>, val favorites: Set<FavoriteName>)

    /** Writes the history file that could not be read to [out], as it was; false when that fails. */
    fun exportDamaged(out: OutputStream): Boolean = runCatching {
        unreadable.inputStream().use { it.copyTo(out) }
    }.isSuccess

    /** Lets go of the history file that could not be read, once exported or no longer wanted. */
    fun forgetDamaged() {
        scope.launch {
            unreadable.delete()
            _damaged.value = false
        }
    }

    /**
     * The first entry [matching] takes, once the file is read, so a shortcut opened at launch, or
     * saved state brought back, still finds its entry.
     */
    suspend fun find(matching: (HistoryEntry) -> Boolean): HistoryEntry? = entries.filterNotNull().first().find(matching)

    /**
     * The entry with [text], or null while the history has none, from once the file is read. Only
     * changes to that entry reach its collectors, so one control following one content is left
     * alone as the rest of the history changes.
     */
    fun entryOf(text: String): Flow<HistoryEntry?> =
        entries.filterNotNull().map { all -> all.find { it.text == text } }.distinctUntilChanged()

    /**
     * Records [content], read from a code that was shown or handed on, the one way scans enter
     * history. Sensitive content is left out where the settings say so; an entry it already has
     * stays as it was, and favoriting such content still keeps it, as the user asked for that.
     */
    fun record(content: ReadContent) {
        val kind = content.sensitiveKind
        if (kind != null && !AppSettings.of(context).keeps(kind).value) return
        addEntry(content.text, content.format)
    }

    private fun addEntry(text: String, format: String) = update { entries ->
        val now = System.currentTimeMillis()
        val entry = entries.find { it.text == text }?.copy(format = format, time = now) ?: HistoryEntry(text, format, now)
        listOf(entry) + entries.filter { it.text != text }
    }

    /**
     * Makes the content a favorite or stops it being one, the one way everything that favorites
     * goes. Making it one asks for its name first where the settings say so and it has no name of
     * its own, or always where [askName] does, as naming and favoriting at once does; then nothing
     * changes until [nameFavorite].
     */
    fun requestFavorite(content: ReadContent, favorite: Boolean, askName: Boolean = false): FavoriteRequest {
        if (favorite) {
            val names = content.names(context, _entries.value?.find { it.text == content.text }?.label)
            if (askName || (AppSettings.of(context).namesFavorites.value && !names.hasOwnName)) {
                return FavoriteRequest.NeedsName(names.shortTitle)
            }
        }
        setFavorite(content, favorite)
        return FavoriteRequest.Done
    }

    /**
     * Makes the content a favorite under [name], which [requestFavorite] asked for; a blank one
     * leaves it showing its content.
     */
    fun nameFavorite(content: ReadContent, name: String) = setFavorite(content, true, name)

    /**
     * Favoriting content that is not in the history, such as a created code, adds it as well. An
     * entry that stops being a favorite also leaves the app shortcuts. A [label] given with a new
     * favorite names it in the same step, a blank one leaving it to show its content.
     */
    private fun setFavorite(content: ReadContent, favorite: Boolean, label: String? = null) = update { entries ->
        val text = content.text
        val now = System.currentTimeMillis()
        val old = entries.find { it.text == text }
        // A new favorite goes to the top, above whatever order the user gave the others.
        val top = (entries.filter { it.favorite }.minOfOrNull { it.favoriteRank } ?: 0) - 1
        val name = label?.let { ownLabel(content, it) }
        when {
            old == null && favorite ->
                listOf(HistoryEntry(text, content.format, now, favoritedAt = now, label = name, favoriteRank = top)) + entries
            old == null || old.favorite == favorite -> entries
            else -> entries.map {
                when {
                    it.text != text -> it
                    favorite -> it.copy(favoritedAt = now, favoriteRank = top, label = if (label != null) name else it.label)
                    else -> it.copy(favoritedAt = 0L, shortcutAt = 0L, favoriteRank = 0)
                }
            }
        }
    }

    /** Puts the favorites in the order of [texts], the favorites' contents; any left out keep their place after them. */
    fun setFavoriteOrder(texts: List<String>) = update { entries ->
        val ranks = texts.withIndex().associate { (index, text) -> text to index }
        entries.map { entry -> ranks[entry.text]?.takeIf { entry.favorite }?.let { entry.copy(favoriteRank = it) } ?: entry }
    }

    /** Moves the favorite with [text] to the top of the favorites. */
    fun moveFavoriteToTop(text: String) = moveFavorite(text) { 0 }

    private fun moveFavorite(text: String, to: (Int) -> Int) = update { entries ->
        val order = favoritesOf(entries).map { it.text }.toMutableList()
        val from = order.indexOf(text)
        if (from < 0) return@update entries
        order.removeAt(from)
        order.add(to(from).coerceIn(0, order.size), text)
        val ranks = order.withIndex().associate { (rank, value) -> value to rank }
        entries.map { entry -> ranks[entry.text]?.let { entry.copy(favoriteRank = it) } ?: entry }
    }

    /** Only favorites become shortcuts, and no more than [FavoriteShortcuts.MAX] of them. */
    fun setShortcut(text: String, shortcut: Boolean) = update { entries ->
        val full = entries.count { it.shortcut } >= FavoriteShortcuts.MAX
        entries.map {
            when {
                it.text != text || !it.favorite || it.shortcut == shortcut -> it
                !shortcut -> it.copy(shortcutAt = 0L)
                full -> it
                else -> it.copy(shortcutAt = System.currentTimeMillis())
            }
        }
    }

    /** A [label] that is no name of the user's own, see [ownLabel], removes the name. */
    fun rename(text: String, label: String) = update { entries ->
        entries.map { if (it.text == text) it.copy(label = ownLabel(it.content, label)) else it }
    }

    /**
     * [label] as a name the user gave the content, or null when it is none, see
     * [ContentNames.isOwnName]. Accepted unchanged, a title FerryQR shows anyway changes nothing, so
     * the content still counts as unnamed and goes on following its own title.
     */
    private fun ownLabel(content: ReadContent, label: String): String? =
        label.trim().takeIf { content.names(context, null).isOwnName(it) }

    /** Deletes [deleted] in one change, so the page and the saved file see them go together. */
    fun delete(deleted: Collection<HistoryEntry>) {
        val texts = deleted.mapTo(HashSet()) { it.text }
        update { entries -> entries.filterNot { it.text in texts } }
    }

    /** Puts deleted entries back where their times place them, except content scanned again since. */
    fun restore(deleted: List<HistoryEntry>) = update { entries ->
        val present = entries.mapTo(HashSet()) { it.text }
        (entries + deleted.filter { it.text !in present }).sortedByDescending { it.time }
    }

    /** Removes every entry that is not a favorite. */
    fun clear() = update { entries -> entries.filter { it.favorite } }

    private fun update(change: (List<HistoryEntry>) -> List<HistoryEntry>) {
        synchronized(lock) {
            val current = _entries.value
            if (current == null) {
                pending += change
                return
            }
            val next = limited(change(current))
            if (next == current) return
            if (shortcutView(next) != shortcutView(current)) shortcutsStale = true
            _entries.value = next
        }
        saves.trySend(Unit)
    }

    /** Entries other than favorites past [MAX_ENTRIES] are dropped. */
    private fun limited(entries: List<HistoryEntry>): List<HistoryEntry> {
        val dropped = entries.filterNot { it.favorite }.drop(MAX_ENTRIES).toSet()
        return if (dropped.isEmpty()) entries else entries.filterNot { it in dropped }
    }

    /** What reading the file came to: the entries it could read, and whether saving may write over it. */
    private class FileRead(val entries: List<HistoryEntry>, val writable: Boolean = true)

    /**
     * The entries in the file, an entry that cannot be read left out. A file that cannot be read
     * in full is first set aside as it is, unless one already waits there, so the next save does
     * not leave it lost. A file whose bytes cannot be read at all is copied aside as a file, and
     * where that copy cannot be made, saving must not write over it.
     */
    private fun read(): FileRead {
        val bytes = try {
            file.readFully()
        } catch (e: FileNotFoundException) {
            return FileRead(emptyList())
        } catch (e: Exception) {
            return FileRead(emptyList(), writable = setAside { file.baseFile.copyTo(unreadable) })
        }
        var whole = true
        val entries = try {
            val array = JSONArray(String(bytes, Charsets.UTF_8))
            (0 until array.length()).mapNotNull { i ->
                try {
                    val o = array.getJSONObject(i)
                    HistoryEntry(
                        text = o.getString("text"),
                        format = o.optString("format"),
                        time = o.getLong("time"),
                        favoritedAt = o.optLong("favoritedAt"),
                        label = o.optString("label").ifEmpty { null },
                        shortcutAt = o.optLong("shortcutAt"),
                        favoriteRank = o.optInt("favoriteRank"),
                    )
                } catch (e: Exception) {
                    whole = false
                    null
                }
            }
        } catch (e: Exception) {
            whole = false
            emptyList()
        }
        // Without the copy the file is still read as far as it can be; only exporting it is lost.
        if (!whole) setAside { unreadable.writeBytes(bytes) }
        return FileRead(entries)
    }

    /**
     * Keeps the history file that could not be read in full at [unreadable] through [copy], where
     * [damaged] offers it for exporting. False when it is not kept: one set aside earlier already
     * waits there and is not written over, or the copy failed, which leaves no part of it behind.
     */
    private fun setAside(copy: () -> Unit): Boolean {
        if (unreadable.exists()) return false
        return try {
            copy()
            true
        } catch (e: Exception) {
            unreadable.delete()
            false
        }
    }

    private fun save(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach {
            val o = JSONObject().put("text", it.text).put("format", it.format).put("time", it.time)
            if (it.favorite) o.put("favoritedAt", it.favoritedAt)
            if (it.shortcut) o.put("shortcutAt", it.shortcutAt)
            if (it.favorite && it.favoriteRank != 0) o.put("favoriteRank", it.favoriteRank)
            it.label?.let { label -> o.put("label", label) }
            array.put(o)
        }
        val stream = try {
            file.startWrite()
        } catch (e: Exception) {
            return
        }
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
        }
    }

    companion object {
        @Volatile
        private var instance: HistoryStore? = null

        /** The favorites among [entries], in the order history shows them and shortcuts follow. */
        fun favoritesOf(entries: List<HistoryEntry>): List<HistoryEntry> =
            entries.filter { it.favorite }.sortedWith(compareBy<HistoryEntry> { it.favoriteRank }.thenByDescending { it.favoritedAt })

        fun get(context: Context): HistoryStore = instance ?: synchronized(this) {
            instance ?: HistoryStore(context.applicationContext).also { instance = it }
        }
    }
}
