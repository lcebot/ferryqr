package io.github.lcebot.ferryqr.history

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import io.github.lcebot.ferryqr.HistoryActivity
import io.github.lcebot.ferryqr.MainActivity
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.dispatch.Cards

/** Adaptive icon canvas and the glyph inside it, sized like the launcher's own 108 by 108 dp grid. */
private const val ICON_SIZE = 216
private const val GLYPH_SIZE = 96

/** The label lengths the shortcut documentation recommends; longer ones are cut with an ellipsis. */
private const val SHORT_LABEL = 10
private const val LONG_LABEL = 25

/** Colours used before Android 12, matching the app's static light colour scheme. */
private const val FALLBACK_CONTAINER = 0xFFEADDFF.toInt()
private const val FALLBACK_ON_CONTAINER = 0xFF21005D.toInt()

/**
 * Offers the favorites the user chose as app shortcuts, in the order they were added. Shortcuts
 * the user dragged onto the home screen cannot be removed by the app, so those of entries that are
 * no longer favorites are disabled, and the system explains why when one is tapped.
 */
object FavoriteShortcuts {

    /** Launchers show only the first few app shortcuts, so more would never be seen. */
    const val MAX = 4

    const val ACTION_SHOW = "io.github.lcebot.ferryqr.action.SHOW_FAVORITE"
    const val EXTRA_TEXT = "text"

    fun update(context: Context, favorites: List<HistoryEntry>) {
        try {
            val chosen = favorites.filter { it.shortcut }.sortedBy { it.shortcutAt }.take(MAX)
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                chosen.mapIndexed { rank, entry -> shortcut(context, entry, rank) },
            )
            val byId = favorites.associateBy { idOf(it.text) }
            val onHomeScreen = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            val (kept, stale) = onHomeScreen.partition { it.id in byId }
            if (kept.isNotEmpty()) {
                val refreshed = kept.map { shortcut(context, byId.getValue(it.id), 0) }
                ShortcutManagerCompat.enableShortcuts(context, refreshed)
                ShortcutManagerCompat.updateShortcuts(context, refreshed)
            }
            if (stale.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, stale.map { it.id }, null)
        } catch (e: IllegalArgumentException) {
            // The launcher refused the set; shortcuts are a convenience, so the app goes on without them.
        } catch (e: IllegalStateException) {
            // Shortcuts cannot be changed while the user is locked.
        }
    }

    private fun idOf(text: String) = "favorite-${text.hashCode().toUInt().toString(16)}-${text.length}"

    private fun shortcut(context: Context, entry: HistoryEntry, rank: Int): ShortcutInfoCompat {
        val payload = Classifier.classify(entry.text, entry.format)
        val kind = Cards.kindOf(payload)
        val title = entry.label ?: Cards.titleOf(context, payload) ?: context.getString(kind.label)
        // History opens above the scanner, so going back from it leads there as usual.
        val scanner = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val history = Intent(context, HistoryActivity::class.java)
            .setAction(ACTION_SHOW)
            .putExtra(EXTRA_TEXT, entry.text)
        return ShortcutInfoCompat.Builder(context, idOf(entry.text))
            .setShortLabel(title.shortened(SHORT_LABEL))
            .setLongLabel(title.shortened(LONG_LABEL))
            .setIcon(icon(context, kind.icon))
            .setIntents(arrayOf(scanner, history))
            .setRank(rank)
            .build()
    }

    /** The kind's glyph on a tonal container, the way the system draws its own shortcut icons. */
    private fun icon(context: Context, glyphRes: Int): IconCompat {
        val (container, onContainer) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getColor(android.R.color.system_accent1_100) to context.getColor(android.R.color.system_accent1_900)
        } else {
            FALLBACK_CONTAINER to FALLBACK_ON_CONTAINER
        }
        val bitmap = createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(container)
        ContextCompat.getDrawable(context, glyphRes)?.mutate()?.let { glyph ->
            val inset = (ICON_SIZE - GLYPH_SIZE) / 2
            glyph.setTint(onContainer)
            glyph.setBounds(inset, inset, inset + GLYPH_SIZE, inset + GLYPH_SIZE)
            glyph.draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}

/** Cuts at whole code points, so an emoji or other surrogate pair is never split. */
private fun String.shortened(max: Int): String {
    val oneLine = replace(Regex("\\s+"), " ").trim()
    if (oneLine.codePointCount(0, oneLine.length) <= max) return oneLine
    return oneLine.substring(0, oneLine.offsetByCodePoints(0, max - 1)) + "\u2026"
}
