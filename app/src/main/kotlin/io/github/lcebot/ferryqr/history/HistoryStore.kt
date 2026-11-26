package io.github.lcebot.ferryqr.history

import android.content.Context
import android.util.AtomicFile
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.content.sensitiveKind
import io.github.lcebot.ferryqr.dispatch.EntryNames
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.isLocatedMiniCode
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

    /** Whether the favorites changed since the shortcuts were last brought in line. Guarded by [lock]. */
    private var shortcutsStale = false

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
            val read = runCatching { read() }.getOrDefault(emptyList())
            _damaged.value = unreadable.exists()
            synchronized(lock) {
                var current = read
                pending.forEach { current = limited(it(current)) }
                pending.clear()
                _entries.value = current
                if (current != read) saves.trySend(Unit)
            }
            // Shortcuts outlive the process, so they are brought in line with the file once it is read.
            syncShortcuts(_entries.value.orEmpty())
            for (request in saves) {
                val snapshot = _entries.value ?: continue
                // One failed write leaves the next to try again, rather than ending saving for good.
                try {
                    save(snapshot)
                } catch (e: Exception) {
                    // The entries stay in memory, and the next change writes them all again.
                }
                val stale = synchronized(lock) { shortcutsStale.also { shortcutsStale = false } }
                if (stale) syncShortcuts(snapshot)
            }
        }
    }

    /** Shortcuts the launcher refuses leave the history and its saving untouched. */
    private fun syncShortcuts(entries: List<HistoryEntry>) {
        try {
            FavoriteShortcuts.update(context, favoritesOf(entries))
        } catch (e: Exception) {
            // The shortcuts are brought in line again with the next change to the favorites.
        }
    }

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

    /** Waits for the file to be read, so a shortcut opened at launch still finds its entry. */
    suspend fun find(text: String): HistoryEntry? = entries.filterNotNull().first().find { it.text == text }

    /**
     * Records a scan. Sensitive content is left out where the settings say so; an entry it already
     * has stays as it was, and favoriting such content still keeps it, as the user asked for that.
     */
    private fun add(text: String, format: String) {
        val kind = Classifier.classify(text, format).sensitiveKind
        if (kind != null && !AppSettings.of(context).keeps(kind).value) return
        addEntry(text, format)
    }

    /**
     * Records a code that was read and shown or handed on, the one way scans enter history. A mini
     * program code that was only located has no content to keep.
     */
    fun record(detection: Detection) {
        if (!detection.isLocatedMiniCode) add(detection.text, detection.format)
    }

    private fun addEntry(text: String, format: String) = update { entries ->
        val now = System.currentTimeMillis()
        val entry = entries.find { it.text == text }?.copy(format = format, time = now) ?: HistoryEntry(text, format, now)
        listOf(entry) + entries.filter { it.text != text }
    }

    /**
     * Favoriting content that is not in the history, such as a created code, adds it as well. An
     * entry that stops being a favorite also leaves the app shortcuts. A [label] given with a new
     * favorite names it in the same step, a blank one leaving it to show its content.
     */
    fun setFavorite(text: String, format: String, favorite: Boolean, label: String? = null) = update { entries ->
        val now = System.currentTimeMillis()
        val old = entries.find { it.text == text }
        // A new favorite goes to the top, above whatever order the user gave the others.
        val top = (entries.filter { it.favorite }.minOfOrNull { it.favoriteRank } ?: 0) - 1
        val name = label?.let { ownLabel(text, format, it) }
        when {
            old == null && favorite ->
                listOf(HistoryEntry(text, format, now, favoritedAt = now, label = name, favoriteRank = top)) + entries
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

    /**
     * Moves the favorite with [text] [by] places among the favorites, later for a positive count,
     * from wherever it is when the change applies rather than where a screen last saw it.
     */
    fun moveFavorite(text: String, by: Int) = moveFavorite(text) { it + by }

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
        entries.map { if (it.text == text) it.copy(label = ownLabel(it.text, it.format, label)) else it }
    }

    /**
     * [label] as a name the user gave the content, or null when it is none, see
     * [EntryNames.isOwnName]. Accepted unchanged, a title FerryQR shows anyway changes nothing, so
     * the content still counts as unnamed and goes on following its own title.
     */
    private fun ownLabel(text: String, format: String, label: String): String? =
        label.trim().takeIf { EntryNames(context, text, format, null).isOwnName(it) }

    /**
     * Whether making the content a favorite asks for its name first: the settings say so, and it
     * has no name of its own. Everything that makes a favorite asks this.
     */
    fun asksName(text: String, format: String, label: String?): Boolean =
        AppSettings.of(context).namesFavorites.value && !EntryNames(context, text, format, label).hasOwnName

    fun delete(entry: HistoryEntry) = update { entries -> entries.filter { it.text != entry.text } }

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
            if (favoritesOf(next) != favoritesOf(current)) shortcutsStale = true
            _entries.value = next
        }
        saves.trySend(Unit)
    }

    /** Entries other than favorites past [MAX_ENTRIES] are dropped. */
    private fun limited(entries: List<HistoryEntry>): List<HistoryEntry> {
        val dropped = entries.filterNot { it.favorite }.drop(MAX_ENTRIES).toSet()
        return if (dropped.isEmpty()) entries else entries.filterNot { it in dropped }
    }

    /**
     * The entries in the file, an entry that cannot be read left out. A file that cannot be read
     * in full is first set aside as it is, unless one already waits there, so the next save does
     * not leave it lost.
     */
    private fun read(): List<HistoryEntry> {
        val bytes = try {
            file.readFully()
        } catch (e: FileNotFoundException) {
            return emptyList()
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
        if (!whole && !unreadable.exists()) {
            try {
                unreadable.writeBytes(bytes)
            } catch (e: Exception) {
                // Without the copy the file is still read as far as it can be; only exporting it is lost.
            }
        }
        return entries
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
