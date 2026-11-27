package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import android.graphics.Bitmap
import kotlin.coroutines.cancellation.CancellationException

/** What a result card is built for, the same on every screen that shows one. */
sealed interface CardSource {
    /** [content] that was read, from [origin]; [scannedAt] dates what the content itself leaves undated. */
    data class Read(
        val content: ReadContent,
        val origin: Origin,
        val scannedAt: Long = System.currentTimeMillis(),
    ) : CardSource

    /** A mini program code located but not read, shown as [crop], from [origin]. */
    data class LocatedMini(val crop: Bitmap, val origin: Origin) : CardSource
}

/**
 * Builds the result card for a [CardSource]. It asks the package manager which apps there are and
 * may draw the code again, so it runs off the main thread. What the card offers depends on the
 * readiness its [FerryEnv] holds, the packages of the apps that take content through the module, so
 * a card on screen is built again whenever that changes.
 */
object CardFactory {
    /**
     * The card for [source]. Scanned content is untrusted, and a card that fails on it, however
     * malformed, falls back to what is left when nothing is made of the content: the text it holds,
     * or the picture of a code that was not read.
     */
    fun build(env: FerryEnv, source: CardSource): ResultCard = try {
        card(env, source)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        plain(env.context, source)
    }

    private fun card(env: FerryEnv, source: CardSource): ResultCard = when (source) {
        is CardSource.LocatedMini -> Cards.miniProgram(env, source.crop, source.origin)
        is CardSource.Read -> {
            val content = source.content
            val card = Cards.build(env, content, source.origin, source.scannedAt)
            if (source.origin != Origin.HISTORY) {
                card
            } else {
                // The original code is out of sight, so an entry is shown with its code drawn again,
                // to be scanned by another phone or kept as an image.
                val drawing = if (card.image == null) Cards.codeDrawing(content.text, content.format) else null
                val image = card.image ?: drawing?.base
                card.copy(image = image, drawing = drawing, exportable = image != null)
            }
        }
    }

    private fun plain(context: Context, source: CardSource): ResultCard = when (source) {
        is CardSource.LocatedMini -> Cards.plainMiniProgram(context, source.crop)
        is CardSource.Read -> Cards.plainText(context, source.content.text, source.content.format)
    }
}
