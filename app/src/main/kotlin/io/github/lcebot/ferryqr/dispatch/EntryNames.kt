package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.content.WebPayload

/**
 * Every name content goes by, worked out from one reading of it, so a row, a tile, a shortcut and
 * the naming dialog call it the same way, and a name the user gives is told apart from those
 * FerryQR would show anyway by the same rules that show them. [label] is the name the user gave,
 * if any. The names that need more than the content's kind are worked out only when asked for.
 */
class EntryNames(private val context: Context, private val text: String, format: String, val label: String?) {
    private val payload = Classifier.classify(text, format)

    /** The name the content carries, as [Titles] gives it, or null when it carries none. */
    private val carried: String? = Titles.of(context, payload)

    val kind: Kind = Cards.kindOf(payload)

    /** As a list row shows it: its name, else the name it carries, else the content itself. */
    val rowTitle: String = label ?: carried ?: payload.raw

    /** As a tile shows it: as a row does, except a link without a name shows only its host, keeping the tile short. */
    val tileTitle: String = label ?: carried ?: (payload as? WebPayload)?.host ?: payload.raw

    /** The short title FerryQR gives it by itself, which a shortcut shows and a name starts from. */
    val automaticShortTitle: String by lazy(LazyThreadSafetyMode.NONE) {
        Cards.titleOf(context, payload) ?: context.getString(kind.label)
    }

    /** As a shortcut shows it: its name, else [automaticShortTitle]. */
    val shortTitle: String get() = label ?: automaticShortTitle

    /**
     * Whether it has a name of its own: one the user gave it, or one it carries that tells it apart
     * from other content of its kind, as a boarding pass is named by its flight. A name every code of
     * a kind shares, such as a passkey sign-in's, does not count.
     */
    val hasOwnName: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        label != null || Titles.distinct(context, payload) != null
    }

    /**
     * Whether [name] is one the user gave the content rather than one FerryQR shows anyway: not
     * blank, not the content itself, and none of the titles a row, a tile or a shortcut shows
     * without a name, which the naming dialog offers to start from.
     */
    fun isOwnName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == text.trim()) return false
        val unnamedTile = carried ?: (payload as? WebPayload)?.host ?: payload.raw
        return trimmed !in setOf(carried ?: payload.raw, unnamedTile, automaticShortTitle, context.getString(kind.label))
    }
}
