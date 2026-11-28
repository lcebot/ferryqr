package io.github.lcebot.ferryqr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.github.lcebot.ferryqr.dispatch.ReadContent
import io.github.lcebot.ferryqr.scan.Engines
import io.github.lcebot.ferryqr.scan.Found
import io.github.lcebot.ferryqr.scan.FrozenFrame
import io.github.lcebot.ferryqr.scan.codesIn
import io.github.lcebot.ferryqr.scan.decodeStill
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a still image holds, worked out once for whichever page shows it. Only [Codes] and
 * [LocatedMini] hold pictures, and their taker owns them: it releases them once it has what it needs
 * from them, or hands them on to what shows them. Every other scan has let the image go.
 */
sealed interface StillScan {
    /** Lets go of the pictures this holds, for a scan nobody takes up. */
    fun release() {}

    data object Unreadable : StillScan

    /** Neither a code read nor a mini program code located. */
    data object Empty : StillScan

    /**
     * Nothing was read, and [crop] is the mini program code located, for handing to WeChat. A read
     * code always takes precedence over a guess, so a located one stands alone only when none was.
     */
    class LocatedMini(val crop: Bitmap) : StillScan {
        override fun release() = crop.recycle()
    }

    /**
     * The codes found in [frame]: those read, joined by the mini program codes only located, so a
     * chooser offers every code.
     */
    class Codes(val codes: List<StillCode>, val frame: FrozenFrame) : StillScan {
        /** The content of the code read when it is the only one found, which then needs no choosing. */
        val sole: ReadContent? get() = codes.singleOrNull()?.takeIf { it.found is Found.Read }?.content

        override fun release() = frame.release()
    }
}

/**
 * A code [found] in a still image, with its [content] read once when it was read; a mini program
 * code only located has none. A page that lets the image go keeps the pictures it shows of the code
 * with it: the [crop] of a code only located, for WeChat, and a [thumbnail] for its chooser.
 */
class StillCode(val found: Found, val content: ReadContent?, val thumbnail: Bitmap? = null, val crop: Bitmap? = null)

/**
 * Reads still images with the decoders the whole process shares, for the scanner's gallery button
 * and for an image shared to FerryQR alike.
 */
class StillReader(context: Context) {
    private val app = context.applicationContext
    private val zxing = Engines.zxing
    private val wechat = Engines.wechat(app)

    suspend fun read(uri: Uri): StillScan {
        // The image takes up to a few dozen megabytes, so a read cancelled at any point lets it go at once.
        var decoded: Bitmap? = null
        // A read cancelled while the work runs still finishes it, and withContext then throws in place
        // of returning what the work made, which may hold the image or a crop cut from it.
        var made: StillScan? = null
        return try {
            val image = withContext(Dispatchers.IO) { decodeStill(app, uri)?.also { decoded = it } }
                ?: return StillScan.Unreadable
            withContext(Dispatchers.Default) { scanOf(image).also { made = it } }
        } catch (e: CancellationException) {
            // Once made, the scan holds whatever is left of the image.
            val scan = made
            if (scan != null) scan.release() else decoded?.recycle()
            throw e
        }
    }

    /** The scan of [image], whose content is read here, off the main thread, once for every page. */
    private fun scanOf(image: Bitmap): StillScan {
        val codes = codesIn(image, zxing, wechat)
        if (codes.any { it is Found.Read }) {
            val read = codes.map { found ->
                StillCode(found, (found as? Found.Read)?.detection?.let { ReadContent.of(it.text, it.format) })
            }
            return StillScan.Codes(read, FrozenFrame(image))
        }
        // With nothing read, every code found is a located mini program code.
        val crop = (codes.firstOrNull() as? Found.Located)?.crop(image)
        image.recycle()
        return if (crop != null) StillScan.LocatedMini(crop) else StillScan.Empty
    }
}
