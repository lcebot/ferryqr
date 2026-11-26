package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import android.graphics.Bitmap
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.generate.CodeGenerator

/** What a result card is built for, the same on every screen that shows one. */
sealed interface CardSource {
    /** Content that was read, from [origin]; [scannedAt] dates what the content itself leaves undated. */
    data class Read(
        val text: String,
        val format: String,
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
    fun build(context: Context, source: CardSource, ready: Set<String>): ResultCard = when (source) {
        is CardSource.LocatedMini -> Cards.miniProgram(context, source.crop, live = source.live, ready = ready)
        is CardSource.Read -> {
            val payload = Classifier.classify(source.text, source.format)
            val card = Cards.build(context, payload, source.format, source.origin, ready, source.scannedAt)
            if (source.origin != Origin.HISTORY) {
                card
            } else {
                // The original code is no longer in sight, so an entry is shown with its code drawn
                // again, to be scanned by another phone or kept as an image.
                val image = card.image ?: CodeGenerator.redraw(source.text, source.format)
                card.copy(image = image, exportable = image != null)
            }
        }
    }
}
