package io.github.lcebot.ferryqr.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a still image holds, worked out once for every caller. Only [Codes] keeps the image, which
 * its taker recycles once it has what it needs from it; every other outcome has let the image go.
 */
sealed interface StillOutcome {
    /** Lets go of the pictures this holds, for an outcome nobody takes up. */
    fun release() {}

    data object Unreadable : StillOutcome

    /** Neither a code read nor a mini program code located. */
    data object Empty : StillOutcome

    /**
     * Nothing was read, and [crop] is the mini program code located, for handing to WeChat. A read
     * code always takes precedence over a guess, so a located one stands alone only when none was.
     */
    class LocatedMini(val crop: Bitmap) : StillOutcome {
        override fun release() = crop.recycle()
    }

    /**
     * The codes found in [frame]: those read, joined by the mini program codes only located, so a
     * chooser offers every code.
     */
    class Codes(val codes: List<Found>, val frame: Bitmap) : StillOutcome {
        override fun release() = frame.recycle()
    }
}

/**
 * A still image is read only once, so every reader and the most thorough look run on it. Code-like
 * regions the detector proposes but neither reader decoded on the whole image get a deep read of
 * their own.
 */
suspend fun readStillImage(context: Context, uri: Uri, zxing: ZxingEngine, wechat: WeChatEngine): StillOutcome {
    // The image takes up to a few dozen megabytes, so a read cancelled at any point lets it go at once.
    var decoded: Bitmap? = null
    // A read cancelled while the work runs still finishes it, and withContext then throws in place of
    // returning what the work made, which may hold the image or a crop cut from it.
    var made: StillOutcome? = null
    return try {
        val frame = withContext(Dispatchers.IO) { decodeStill(context, uri)?.also { decoded = it } }
            ?: return StillOutcome.Unreadable
        withContext(Dispatchers.Default) { outcomeOf(frame, zxing, wechat).also { made = it } }
    } catch (e: CancellationException) {
        // Once made, the outcome holds whatever is left of the image.
        val outcome = made
        if (outcome != null) outcome.release() else decoded?.recycle()
        throw e
    }
}

private fun outcomeOf(frame: Bitmap, zxing: ZxingEngine, wechat: WeChatEngine): StillOutcome {
    val found = zxing.read(frame).mergedWith(wechat.decode(frame))
    val survey = wechat.survey(frame, Look.THOROUGH)
    val known = found + survey.read
    val deep = survey.regions
        .filter { region -> known.none { region.contains(it.bounds.centerX().toInt(), it.bounds.centerY().toInt()) } }
        .flatMap { region ->
            val padded = region.paddedWithin(frame.width, frame.height)
            frame.regionCrop(padded, enlarged = true)?.use { zxing.readRegion(it) }.orEmpty()
                .ifEmpty { frame.regionCrop(padded, enlarged = false)?.use { wechat.decode(it) }.orEmpty() }
        }
    val detections = found.mergedWith(survey.read).mergedWith(deep)
    if (detections.isNotEmpty()) {
        return StillOutcome.Codes(detections.map(Found::Read) + survey.located.map { it.locatedIn(frame.width, frame.height) }, frame)
    }
    val crop = survey.located.firstOrNull()?.crop(frame)
    frame.recycle()
    return if (crop != null) StillOutcome.LocatedMini(crop) else StillOutcome.Empty
}

/** Margin around a code's thumbnail, as a share of the code's longer side. */
private const val THUMBNAIL_MARGIN = 0.15f

/** The code's own region of [frame] with a little margin, or null when its position is unknown. */
fun Found.thumbnail(frame: Bitmap): Bitmap? {
    if (this is Found.Read && detection.corners.isEmpty()) return null
    val margin = max(bounds.width(), bounds.height()) * THUMBNAIL_MARGIN
    return frame.ownedCrop(
        Rect(
            (bounds.left - margin).roundToInt(),
            (bounds.top - margin).roundToInt(),
            (bounds.right + margin).roundToInt(),
            (bounds.bottom + margin).roundToInt(),
        )
    )
}
