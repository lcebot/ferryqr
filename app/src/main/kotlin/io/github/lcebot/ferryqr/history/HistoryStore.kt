package io.github.lcebot.ferryqr.history

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Entries other than favorites beyond this many are dropped, oldest first. */
private const val MAX_ENTRIES = 500

/**
 * [favoritedAt] is when the entry became a favorite, or 0 while it is not one. [label] is a name the
 * user gave the entry, shown in place of its content. [shortcutAt] is when the user added the
 * favorite to the app shortcuts, or 0 while it is not among them.
 */
data class HistoryEntry(
    val text: String,
    val format: String,
    val time: Long,
    val favoritedAt: Long = 0L,
    val label: String? = null,
    val shortcutAt: Long = 0L,
) {
    val favorite: Boolean get() = favoritedAt > 0L
    val shortcut: Boolean get() = shortcutAt > 0L
}

/**
 * Scan history kept in a single JSON file in app-private storage. Newest entries come first, and
 * scanning the same content again moves it to the top instead of adding a duplicate, keeping what
 * the user set on it. Favorites survive both the size limit and clearing the history.
 */
class HistoryStore private constructor(private val context: Context) {

    private val file = AtomicFile(File(context.filesDir, "history.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loaded = false

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        // Shortcuts outlive the process, so they are brought in line with the file once it is read.
        scope.launch {
            mutex.withLock {
                ensureLoaded()
                FavoriteShortcuts.update(context, favoritesOf(_entries.value))
            }
        }
    }

    /** Waits for the file to be read, so a shortcut opened at launch still finds its entry. */
    suspend fun find(text: String): HistoryEntry? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            _entries.value.find { it.text == text }
        }
    }

    fun add(text: String, format: String) = update { entries ->
        val now = System.currentTimeMillis()
        val entry = entries.find { it.text == text }?.copy(format = format, time = now) ?: HistoryEntry(text, format, now)
        listOf(entry) + entries.filter { it.text != text }
    }

    /**
     * Favoriting content that is not in the history, such as a created code, adds it as well. An
     * entry that stops being a favorite also leaves the app shortcuts.
     */
    fun setFavorite(text: String, format: String, favorite: Boolean) = update { entries ->
        val now = System.currentTimeMillis()
        val old = entries.find { it.text == text }
        when {
            old == null && favorite -> listOf(HistoryEntry(text, format, now, favoritedAt = now)) + entries
            old == null || old.favorite == favorite -> entries
            else -> entries.map {
                when {
                    it.text != text -> it
                    favorite -> it.copy(favoritedAt = now)
                    else -> it.copy(favoritedAt = 0L, shortcutAt = 0L)
                }
            }
        }
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

    /** A blank [label] removes the name, so the entry shows its content again. */
    fun rename(text: String, label: String) = update { entries ->
        entries.map { if (it.text == text) it.copy(label = label.trim().ifEmpty { null }) else it }
    }

    fun delete(entry: HistoryEntry) = update { entries -> entries.filter { it.text != entry.text } }

    /** Puts a deleted entry back where its time places it, unless the same content was scanned since. */
    fun restore(entry: HistoryEntry) = update { entries ->
        if (entries.any { it.text == entry.text }) entries
        else (entries + entry).sortedByDescending { it.time }
    }

    /** Removes every entry that is not a favorite. */
    fun clear() = update { entries -> entries.filter { it.favorite } }

    private fun update(change: (List<HistoryEntry>) -> List<HistoryEntry>) {
        scope.launch {
            mutex.withLock {
                ensureLoaded()
                val changed = change(_entries.value)
                val dropped = changed.filterNot { it.favorite }.drop(MAX_ENTRIES).toSet()
                val limited = if (dropped.isEmpty()) changed else changed.filterNot { it in dropped }
                if (limited == _entries.value) return@withLock
                val favoritesChanged = favoritesOf(limited) != favoritesOf(_entries.value)
                _entries.value = limited
                save(limited)
                if (favoritesChanged) FavoriteShortcuts.update(context, favoritesOf(limited))
            }
        }
    }

    private fun favoritesOf(entries: List<HistoryEntry>) = entries.filter { it.favorite }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _entries.value = try {
            val array = JSONArray(String(file.readFully(), Charsets.UTF_8))
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                HistoryEntry(
                    text = o.getString("text"),
                    format = o.optString("format"),
                    time = o.getLong("time"),
                    favoritedAt = o.optLong("favoritedAt"),
                    label = o.optString("label").ifEmpty { null },
                    shortcutAt = o.optLong("shortcutAt"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach {
            val o = JSONObject().put("text", it.text).put("format", it.format).put("time", it.time)
            if (it.favorite) o.put("favoritedAt", it.favoritedAt)
            if (it.shortcut) o.put("shortcutAt", it.shortcutAt)
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

        fun get(context: Context): HistoryStore = instance ?: synchronized(this) {
            instance ?: HistoryStore(context.applicationContext).also { instance = it }
        }
    }
}
