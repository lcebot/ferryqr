package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import android.graphics.Bitmap
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.TextPayload
import io.github.lcebot.ferryqr.generate.CodeGenerator
import kotlin.coroutines.cancellation.CancellationException

/** What a result card is built for, the same on every screen that shows one. */
sealed interface CardSource {
    /** [content] that was read, from [origin]; [scannedAt] dates what the content itself leaves undated. */
    data class Read(
        val content: ReadContent,
        val origin: Origin,
        val scannedAt: Long = System.currentTimeMillis(),
    ) : CardSource

    /** A mini program code located but not read, shown as [crop]; [live] while the camera still sees it. */
    data class LocatedMini(val crop: Bitmap, val live: Boolean) : CardSource
}

/**
 * Builds the result card for a [CardSource]. It asks the package manager which apps there are and
 * may draw the code again, so it runs off the main thread. What the card offers depends on [ready],
 * the packages of the apps that take content through the module, so a card on screen is built again
 * whenever that changes.
 */
object CardFactory {
    /**
     * The card for [source]. Scanned content is untrusted, and a card that fails on it, however
     * malformed, falls back to what is left when nothing is made of the content: the text it holds,
     * or the picture of a code that was not read.
     */
    fun build(context: Context, source: CardSource, ready: Set<String>): ResultCard = try {
        card(context, source, ready)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        plain(context, source, ready)
    }

    private fun card(context: Context, source: CardSource, ready: Set<String>): ResultCard = when (source) {
        is CardSource.LocatedMini -> Cards.miniProgram(context, source.crop, live = source.live, ready = ready)
        is CardSource.Read -> {
            val content = source.content
            val card = Cards.build(context, content.payload, content.format, source.origin, ready, source.scannedAt)
            if (source.origin != Origin.HISTORY) {
                card
            } else {
                // The original code is out of sight, so an entry is shown with its code drawn again,
                // to be scanned by another phone or kept as an image.
                val image = card.image ?: CodeGenerator.redraw(content.text, content.format)
                card.copy(image = image, exportable = image != null)
            }
        }
    }

    private fun plain(context: Context, source: CardSource, ready: Set<String>): ResultCard = when (source) {
        is CardSource.LocatedMini -> ResultCard(
            raw = "",
            kind = R.string.kind_mini_program,
            icon = R.drawable.ic_qr_code_scanner,
            headline = context.getString(R.string.mini_headline),
            body = null,
            notes = emptyList(),
            actions = emptyList(),
            image = source.crop,
        )
        is CardSource.Read -> {
            val content = source.content
            Cards.build(context, TextPayload(content.text), content.format, source.origin, ready, source.scannedAt)
        }
    }
}
