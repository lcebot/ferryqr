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
import io.github.lcebot.ferryqr.dispatch.cutToCodePoints
import kotlin.math.roundToInt

/**
 * Adaptive icon canvas and the glyph inside it, in dp, sized like the launcher's own 108 by 108 dp
 * grid. They are drawn at the density of the display, so they stay as sharp as the app's own icon.
 */
private const val ICON_DP = 108
private const val GLYPH_DP = 48

/** The label lengths the shortcut documentation recommends; longer ones are cut with an ellipsis. */
private const val SHORT_LABEL = 10
private const val LONG_LABEL = 25

/** Colours used before Android 12, matching the app's static light colour scheme. */
private const val FALLBACK_CONTAINER = 0xFFEADDFF.toInt()
private const val FALLBACK_ON_CONTAINER = 0xFF21005D.toInt()

/**
 * Offers the favorites the user chose as app shortcuts, in the order of the favorites. Shortcuts
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
            val dynamic = favorites.filter { it.shortcut }.take(MAX).mapIndexed { rank, entry -> shortcut(context, entry, rank) }
            ShortcutManagerCompat.setDynamicShortcuts(context, dynamic)
            val dynamicById = dynamic.associateBy { it.id }
            val byId = favorites.associateBy { idOf(it.text) }
            val onHomeScreen = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            val (kept, stale) = onHomeScreen.partition { it.id in byId }
            if (kept.isNotEmpty()) {
                // An update sets the rank it carries, so a shortcut that is also dynamic is refreshed
                // with the rank it was just given. One that is only pinned is in no list a rank orders.
                val refreshed = kept.map { dynamicById[it.id] ?: shortcut(context, byId.getValue(it.id)) }
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

    /** The shortcut for [entry], placed at [rank] among the dynamic ones when it is one of them. */
    private fun shortcut(context: Context, entry: HistoryEntry, rank: Int? = null): ShortcutInfoCompat {
        val names = entry.content.names(context, entry.label)
        val kind = names.kind
        val title = names.shortTitle
        // History opens above the scanner, so going back from it leads there as usual.
        val scanner = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val history = Intent(context, HistoryActivity::class.java)
            .setAction(ACTION_SHOW)
            .putExtra(EXTRA_TEXT, entry.text)
        val builder = ShortcutInfoCompat.Builder(context, idOf(entry.text))
            .setShortLabel(title.shortened(SHORT_LABEL))
            .setLongLabel(title.shortened(LONG_LABEL))
            .setIcon(icon(context, kind.icon))
            .setIntents(arrayOf(scanner, history))
        if (rank != null) builder.setRank(rank)
        return builder.build()
    }

    /** The kind's glyph on a tonal container, the way the system draws its own shortcut icons. */
    private fun icon(context: Context, glyphRes: Int): IconCompat {
        val (container, onContainer) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getColor(android.R.color.system_accent1_100) to context.getColor(android.R.color.system_accent1_900)
        } else {
            FALLBACK_CONTAINER to FALLBACK_ON_CONTAINER
        }
        val density = context.resources.displayMetrics.density
        val size = (ICON_DP * density).roundToInt()
        val glyphSize = (GLYPH_DP * density).roundToInt()
        val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(container)
        ContextCompat.getDrawable(context, glyphRes)?.mutate()?.let { glyph ->
            val inset = (size - glyphSize) / 2
            glyph.setTint(onContainer)
            glyph.setBounds(inset, inset, inset + glyphSize, inset + glyphSize)
            glyph.draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}

/** On one line, as a launcher shows a label, and cut to [max] code points. */
private fun String.shortened(max: Int): String = replace(Regex("\\s+"), " ").trim().cutToCodePoints(max)
