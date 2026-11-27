package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import android.os.LocaleList
import android.util.LruCache
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.content.SensitiveKind
import io.github.lcebot.ferryqr.content.sensitiveKind

/**
 * Room for a whole history several times over. History keeps at most 500 entries besides the
 * favorites, and the cards, the shortcuts and the naming dialogs keep readings and names of their
 * own beside those of the history lists, so every layer finds the reading the others made, and
 * the history lists still find every entry named once it has been, which is what lets them show
 * from the first frame.
 */
private const val CACHED = 4096

/**
 * Content that was read, [text] in [format], classified once into [payload], from which every layer
 * takes what it needs: the scanner its way to an app, history whether to keep it, and every list,
 * shortcut, dialog and card its [names]. Values for the same text in the same format are
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
    fun names(context: Context, label: String?): ContentNames {
        val app = context.applicationContext
        val key = NamesKey(text, format, label, app.resources.configuration.locales)
        return NAMES.get(key) ?: ContentNames(app, this, label).also { NAMES.put(key, it) }
    }

    override fun equals(other: Any?): Boolean = other is ReadContent && other.text == text && other.format == format

    override fun hashCode(): Int = 31 * text.hashCode() + format.hashCode()

    /**
     * Names are kept by text and format rather than by content, so whether they are kept can be asked
     * without classifying the content.
     */
    private data class NamesKey(val text: String, val format: String, val label: String?, val locales: LocaleList)

    companion object {
        private val CONTENTS = LruCache<Pair<String, String>, ReadContent>(CACHED)
        private val NAMES = LruCache<NamesKey, ContentNames>(CACHED)

        /**
         * The names [names] gives the content [text] read in [format] under [label], in the language
         * of [context], when they are kept already, else null. Nothing is classified or named, so the
         * main thread can ask.
         */
        fun knownNames(context: Context, text: String, format: String, label: String?): ContentNames? =
            NAMES.get(NamesKey(text, format, label, context.applicationContext.resources.configuration.locales))

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
