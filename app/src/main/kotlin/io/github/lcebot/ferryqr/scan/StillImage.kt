package io.github.lcebot.ferryqr.scan

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Every code in [frame], a still image, which is read only once, so every reader and the most
 * thorough look run on it: those read, joined by the mini program codes only located. Code-like
 * regions the detector proposes but neither reader decoded on the whole image get a deep read of
 * their own.
 */
fun codesIn(frame: Bitmap, zxing: ZxingEngine, wechat: WeChatEngine): List<Found> {
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
    return detections.map(Found::Read) + survey.located.map { it.locatedIn(frame.width, frame.height) }
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
