package io.github.lcebot.ferryqr

import android.content.Context
import androidx.core.content.edit
import io.github.lcebot.ferryqr.content.SensitiveKind

/** How long codes, barcodes and PDF417, grow when enlarged. */
enum class LongCodeEnlarge { TILT, STRETCH, FULL_SCREEN }

/**
 * How history shows favorites: as rows like the other entries, or as tiles as wide as their names,
 * either packed into rows that they fill or lined up in columns.
 */
enum class FavoriteLayout { LIST, COMPACT, GRID }

/** The user's choices from the settings page, read where they apply. */
object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_LONG_CODES = "long_code_enlarge"
    private const val KEY_FULL_BRIGHTNESS = "full_brightness"
    private const val KEY_REMEMBER_FORMAT = "remember_format"
    private const val KEY_LAST_FORMAT = "last_format"
    private const val KEY_FAVORITE_LAYOUT = "favorite_layout"
    private const val KEY_NAME_FAVORITES = "name_favorites"
    private const val KEY_FOLDED_ROWS = "folded_rows"

    /**
     * How many rows folded favorites can show, whether rows of the list or rows of tiles, and how
     * many they show until set.
     */
    val FOLDED_ROWS = 1..5
    const val DEFAULT_FOLDED_ROWS = 2

    fun longCodeEnlarge(context: Context): LongCodeEnlarge =
        prefs(context).getString(KEY_LONG_CODES, null)?.let { saved -> LongCodeEnlarge.entries.firstOrNull { it.name == saved } }
            ?: LongCodeEnlarge.TILT

    fun setLongCodeEnlarge(context: Context, value: LongCodeEnlarge) = prefs(context).edit { putString(KEY_LONG_CODES, value.name) }

    fun fullBrightness(context: Context): Boolean = prefs(context).getBoolean(KEY_FULL_BRIGHTNESS, false)

    fun setFullBrightness(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_FULL_BRIGHTNESS, on) }

    fun rememberFormat(context: Context): Boolean = prefs(context).getBoolean(KEY_REMEMBER_FORMAT, false)

    fun setRememberFormat(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_REMEMBER_FORMAT, on) }

    fun favoriteLayout(context: Context): FavoriteLayout =
        prefs(context).getString(KEY_FAVORITE_LAYOUT, null)?.let { saved -> FavoriteLayout.entries.firstOrNull { it.name == saved } }
            ?: FavoriteLayout.LIST

    fun setFavoriteLayout(context: Context, value: FavoriteLayout) = prefs(context).edit { putString(KEY_FAVORITE_LAYOUT, value.name) }

    fun foldedRows(context: Context): Int =
        prefs(context).getInt(KEY_FOLDED_ROWS, DEFAULT_FOLDED_ROWS).coerceIn(FOLDED_ROWS)

    fun setFoldedRows(context: Context, rows: Int) = prefs(context).edit { putInt(KEY_FOLDED_ROWS, rows.coerceIn(FOLDED_ROWS)) }

    /** Whether making something a favorite asks for its name first. */
    fun namesFavorites(context: Context): Boolean = prefs(context).getBoolean(KEY_NAME_FAVORITES, false)

    fun setNamesFavorites(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_NAME_FAVORITES, on) }

    /** Whether scans of [kind] go into history. */
    fun keeps(context: Context, kind: SensitiveKind): Boolean = prefs(context).getBoolean(keepKey(kind), kind.keptByDefault)

    fun setKeeps(context: Context, kind: SensitiveKind, on: Boolean) = prefs(context).edit { putBoolean(keepKey(kind), on) }

    private fun keepKey(kind: SensitiveKind) = "keep_" + kind.name.lowercase()

    /** The create page's last choice, a format's name or the automatic choice's key. */
    fun lastFormat(context: Context): String? = prefs(context).getString(KEY_LAST_FORMAT, null)

    fun setLastFormat(context: Context, choice: String) = prefs(context).edit { putString(KEY_LAST_FORMAT, choice) }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
