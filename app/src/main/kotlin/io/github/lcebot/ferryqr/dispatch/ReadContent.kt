package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import android.os.LocaleList
import android.util.LruCache
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.content.SensitiveKind
import io.github.lcebot.ferryqr.content.sensitiveKind

/**
 * Room for a whole history, 500 entries besides the favorites, with room to spare, so the history
 * lists, the shortcuts, the naming dialogs and the store all find the reading the others made.
 */
private const val CACHED = 1024

/**
 * Content that was read, [text] in [format], classified once into [payload], from which every layer
 * takes what it needs: the scanner its way to an app and its card, history whether to keep it, and
 * every list, shortcut and dialog its [names]. Values for the same text in the same format are
 * equal, as the content they hold is, and [of] hands out one value for each, so classifying a
 * content and naming it are done once in the process rather than again in every layer.
 */
class ReadContent private constructor(val text: String, val format: String) {

    val payload: Payload = Classifier.classify(text, format)

    val kind: Kind = Cards.kindOf(payload)

    /** The kind of sensitive content this is, or null for content that is not. */
    val sensitiveKind: SensitiveKind? get() = payload.sensitiveKind

    /**
     * Every name the content goes by, [label] being the name the user gave it, if any, in the
     * language of [context]. Names hold words in that language, so they are kept for each language
     * apart, and asking in another one works them out again. They are worked out with the
     * application's context, so the names kept for the process hold on to no activity.
     */
    fun names(context: Context, label: String?): EntryNames {
        val app = context.applicationContext
        val key = NamesKey(this, label, app.resources.configuration.locales)
        return NAMES.get(key) ?: EntryNames(app, this, label).also { NAMES.put(key, it) }
    }

    override fun equals(other: Any?): Boolean = other is ReadContent && other.text == text && other.format == format

    override fun hashCode(): Int = 31 * text.hashCode() + format.hashCode()

    private data class NamesKey(val content: ReadContent, val label: String?, val locales: LocaleList)

    companion object {
        private val CONTENTS = LruCache<Pair<String, String>, ReadContent>(CACHED)
        private val NAMES = LruCache<NamesKey, EntryNames>(CACHED)

        /**
         * The content [text] read in [format], classified here unless it was already. Classifying
         * is quick; naming, which runs the patterns of every known link, waits for [names].
         */
        fun of(text: String, format: String): ReadContent {
            val key = text to format
            return CONTENTS.get(key) ?: ReadContent(text, format).also { CONTENTS.put(key, it) }
        }
    }
}
