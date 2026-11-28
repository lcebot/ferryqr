package io.github.lcebot.ferryqr

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.lcebot.ferryqr.content.SensitiveKind
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** How long codes, barcodes and PDF417, grow when enlarged. */
enum class LongCodeEnlarge { TILT, STRETCH, FULL_SCREEN }

/**
 * How history shows favorites: as rows like the other entries, or as tiles as wide as their names,
 * either packed into rows that they fill or lined up in columns.
 */
enum class FavoriteLayout {
    LIST, COMPACT, GRID;

    companion object {
        /** The layout the choice of tiles and, for tiles, of a grid come to. */
        fun of(tiles: Boolean, grid: Boolean): FavoriteLayout = when {
            !tiles -> LIST
            grid -> GRID
            else -> COMPACT
        }
    }
}

/**
 * One saved choice. [flow] follows every change to it, wherever it is made, so a screen that shows
 * it stays in step without reading it again; [value] reads it, and writing it saves it.
 */
class Setting<T> internal constructor(
    internal val prefs: SharedPreferences,
    private val key: String,
    private val read: SharedPreferences.(String) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) {
    private val state = MutableStateFlow(prefs.read(key))
    val flow: StateFlow<T> = state.asStateFlow()

    var value: T
        get() = state.value
        set(value) {
            state.value = value
            prefs.edit { write(key, value) }
        }

    /** Reads the saved value again after [changed] changed in [prefs]; a null key means all were cleared. */
    internal fun reload(changed: String?) {
        if (changed == null || changed == key) state.value = prefs.read(key)
    }
}

/**
 * The user's choices, from the settings page and from the screens that remember how they were left,
 * each a [Setting] that screens observe.
 */
class AppSettings private constructor(context: Context) {
    private val settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val history = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
    private val openIn = context.getSharedPreferences(OPEN_IN_PREFS, Context.MODE_PRIVATE)
    private val all = CopyOnWriteArrayList<Setting<*>>()

    // Kept here, since preferences hold their listeners only weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        all.forEach { if (it.prefs === prefs) it.reload(key) }
    }

    init {
        listOf(settings, history, openIn).forEach { it.registerOnSharedPreferenceChangeListener(listener) }
    }

    private fun <T> setting(
        prefs: SharedPreferences,
        key: String,
        read: SharedPreferences.(String) -> T,
        write: SharedPreferences.Editor.(String, T) -> Unit,
    ): Setting<T> = Setting(prefs, key, read, write).also { all += it }

    private fun flag(prefs: SharedPreferences, key: String, default: Boolean = false) =
        setting(prefs, key, { getBoolean(it, default) }, { k, v -> putBoolean(k, v) })

    val longCodeEnlarge = setting(
        settings, KEY_LONG_CODES,
        { key -> getString(key, null)?.let { saved -> LongCodeEnlarge.entries.firstOrNull { it.name == saved } } ?: LongCodeEnlarge.TILT },
        { key, value -> putString(key, value.name) },
    )

    val fullBrightness = flag(settings, KEY_FULL_BRIGHTNESS)

    /** Whether the create page starts with the format chosen there last. */
    val rememberFormat = flag(settings, KEY_REMEMBER_FORMAT)

    /** The create page's last choice, a format's name or the automatic choice's key. */
    val lastFormat = setting(settings, KEY_LAST_FORMAT, { getString(it, null) }, { key, value -> putString(key, value) })

    /** Whether favorites show as tiles rather than rows. */
    val favoriteTiles = flag(settings, KEY_FAVORITE_TILES)

    /** Whether tiles line up in columns; kept while favorites show as rows, for when they turn back to tiles. */
    val tileGrid = flag(settings, KEY_TILE_GRID)

    /** How many rows folded favorites show, whether rows of the list or rows of tiles. */
    val foldedRows = setting(
        settings, KEY_FOLDED_ROWS,
        { getInt(it, DEFAULT_FOLDED_ROWS).coerceIn(FOLDED_ROWS) },
        { key, value -> putInt(key, value.coerceIn(FOLDED_ROWS)) },
    )

    /** Whether making something a favorite asks for its name first. */
    val namesFavorites = flag(settings, KEY_NAME_FAVORITES)

    /** Whether history shows its favorites folded to [foldedRows] rows. */
    val favoritesFolded = flag(history, KEY_FAVORITES_FOLDED)

    /** Whether history already suggested showing favorites as tiles, which it does once. */
    val tilesSuggested = flag(history, KEY_TILES_SUGGESTED)

    /**
     * Whether the camera permission was ever refused in the system's prompt. Android tells a refusal
     * for good from a prompt merely dismissed only by a refusal coming before it, and that refusal
     * may have been in an earlier run of the app.
     */
    val cameraRefused = flag(settings, KEY_CAMERA_REFUSED)

    private val keeps = ConcurrentHashMap<SensitiveKind, Setting<Boolean>>()

    /** Whether scans of [kind] go into history. */
    fun keeps(kind: SensitiveKind): Setting<Boolean> =
        keeps.computeIfAbsent(kind) { flag(settings, "keep_" + it.name.lowercase(), it.keptByDefault) }

    /** Whether scans of each sensitive kind go into history, following a change to any of them. */
    val kept: Flow<Map<SensitiveKind, Boolean>>
        get() = combine(SensitiveKind.entries.map { kind -> keeps(kind).flow.map { kind to it } }) { it.toMap() }

    private val cameraFeeds = ConcurrentHashMap<String, Setting<Boolean>>()

    /**
     * Whether text for the app [pkg] is shown to its camera instead of replacing its decoder's
     * result. Slower, but it works when the result cannot be replaced in that app's version.
     */
    fun cameraFeed(pkg: String): Setting<Boolean> =
        cameraFeeds.computeIfAbsent(pkg) { flag(openIn, CAMERA_FEED_KEY + it) }

    companion object {
        private const val PREFS = "settings"
        private const val HISTORY_PREFS = "history"
        private const val OPEN_IN_PREFS = "open_in"
        private const val KEY_LONG_CODES = "long_code_enlarge"
        private const val KEY_FULL_BRIGHTNESS = "full_brightness"
        private const val KEY_REMEMBER_FORMAT = "remember_format"
        private const val KEY_LAST_FORMAT = "last_format"
        private const val KEY_FAVORITE_TILES = "favorite_tiles"
        private const val KEY_TILE_GRID = "tile_grid"
        private const val KEY_NAME_FAVORITES = "name_favorites"
        private const val KEY_FOLDED_ROWS = "folded_rows"
        private const val KEY_FAVORITES_FOLDED = "favorites_folded"
        private const val KEY_TILES_SUGGESTED = "tiles_suggested"
        private const val KEY_CAMERA_REFUSED = "camera_refused"
        private const val CAMERA_FEED_KEY = "camera_feed:"

        /** How many rows folded favorites can show, and how many they show until set. */
        val FOLDED_ROWS = 1..5
        const val DEFAULT_FOLDED_ROWS = 2

        @Volatile
        private var instance: AppSettings? = null

        fun of(context: Context): AppSettings = instance ?: synchronized(this) {
            instance ?: AppSettings(context.applicationContext).also { instance = it }
        }
    }
}
