package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import io.github.lcebot.ferryqr.content.SchemePayload
import io.github.lcebot.ferryqr.content.TextPayload
import io.github.lcebot.ferryqr.content.WebPayload

/**
 * The most code points a row's or a tile's title keeps. A row or tile shows two lines at most, which
 * even a wide window fills with far fewer, so cutting content of a few kilobytes, such as a boarding
 * pass or a PDF417 code, to this many spares breaking and measuring the rest and changes nothing shown.
 */
private const val TITLE_CODE_POINTS = 1000

/**
 * This string as it stands when it holds at most [max] code points, else cut at whole code points to
 * [max] with the ellipsis that ends it, so no emoji or other surrogate pair is split.
 */
internal fun String.cutToCodePoints(max: Int): String {
    // A string of at most that many chars holds at most that many code points.
    if (length <= max || codePointCount(0, length) <= max) return this
    return substring(0, offsetByCodePoints(0, max - 1)) + "…"
}

/**
 * Every name [content] goes by, worked out from its one reading, so a row, a tile, a shortcut, the
 * naming dialog and a card call it the same way, and a name the user gives is told apart from those
 * FerryQR would show anyway by the same rules that show them. [label] is the name the user gave,
 * if any. [ReadContent.names] hands them out, kept for each language and shared by every thread.
 */
class ContentNames internal constructor(context: Context, val content: ReadContent, val label: String?) {
    private val payload = content.payload

    /** The name the content carries, as [Titles] gives it. */
    private val carried: CarriedName = Titles.carried(context, payload)

    val kind: Kind = content.kind

    private val kindLabel: String = context.getString(kind.label)

    /**
     * The content's own name, which a card leads with and a tile shows when the user gave none: the
     * name it carries, else a link's host, which keeps a tile short, else the content itself. A card
     * shows the content, so it never takes the user's [label].
     */
    val headline: String = carried.name ?: (payload as? WebPayload)?.host ?: payload.raw

    /**
     * As a list row shows it: its name, else the name it carries, else the content itself, cut to a
     * length two lines cannot show in full.
     */
    val rowTitle: String = (label ?: carried.name ?: payload.raw).cutToCodePoints(TITLE_CODE_POINTS)

    /** As a tile shows it: its name, else [headline], cut as [rowTitle] is. */
    val tileTitle: String = (label ?: headline).cutToCodePoints(TITLE_CODE_POINTS)

    /**
     * The short title FerryQR gives it by itself, which a shortcut shows and a name starts from: the
     * name it carries, else a link's host or the text itself, else its kind when only that describes it.
     */
    val automaticShortTitle: String = carried.name ?: when (payload) {
        is WebPayload -> payload.host
        is TextPayload, is SchemePayload -> payload.raw
        else -> null
    } ?: kindLabel

    /** As a shortcut shows it: its name, else [automaticShortTitle]. */
    val shortTitle: String get() = label ?: automaticShortTitle

    /**
     * Whether it has a name of its own: one the user gave it, or one it carries that tells it apart
     * from other content of its kind, as a boarding pass is named by its flight. A name every code of
     * a kind shares, such as a passkey sign-in's, does not count.
     */
    val hasOwnName: Boolean = label != null || carried.distinct

    /**
     * Whether [name] is one the user gave the content rather than one FerryQR shows anyway: not
     * blank, not the content itself, and none of the titles a row, a tile or a shortcut shows
     * without a name, which the naming dialog offers to start from.
     */
    fun isOwnName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == content.text.trim()) return false
        return trimmed !in setOf(carried.name ?: payload.raw, headline, automaticShortTitle, kindLabel)
    }
}
