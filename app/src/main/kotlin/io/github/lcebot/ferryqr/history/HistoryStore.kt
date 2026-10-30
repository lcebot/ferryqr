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
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_ENTRIES = 500

data class HistoryEntry(val text: String, val format: String, val time: Long)

/**
 * Scan history kept in a single JSON file in app-private storage. Newest entries come first, and
 * scanning the same content again moves it to the top instead of adding a duplicate.
 */
class HistoryStore private constructor(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "history.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loaded = false

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        scope.launch { mutex.withLock { ensureLoaded() } }
    }

    fun add(text: String, format: String) {
        val entry = HistoryEntry(text, format, System.currentTimeMillis())
        scope.launch {
            mutex.withLock {
                ensureLoaded()
                val updated = (listOf(entry) + _entries.value.filter { it.text != text }).take(MAX_ENTRIES)
                _entries.value = updated
                save(updated)
            }
        }
    }

    fun clear() {
        scope.launch {
            mutex.withLock {
                loaded = true
                _entries.value = emptyList()
                file.delete()
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _entries.value = try {
            val array = JSONArray(String(file.readFully(), Charsets.UTF_8))
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                HistoryEntry(o.getString("text"), o.optString("format"), o.getLong("time"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("text", it.text).put("format", it.format).put("time", it.time)) }
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
