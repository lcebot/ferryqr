package io.github.lcebot.ferryqr.scan

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

/** The [Detection.format] of a mini program code that was located but could not be read. */
const val MINI_LOCATED_FORMAT = "WX_CODE_LOCATED"

/** A decoded symbol; [corners] are in the pixel space of the upright frame it was read from. */
data class Detection(
    val text: String,
    val format: String,
    val corners: List<PointF>,
) {
    val center: PointF
        get() = if (corners.isEmpty()) PointF() else PointF(
            corners.map { it.x }.average().toFloat(),
            corners.map { it.y }.average().toFloat(),
        )

    val bounds: RectF
        get() = if (corners.isEmpty()) RectF() else RectF(
            corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y },
        )
}

/**
 * Codes located in a live frame but not decoded yet. [bounds] covers all of them in the pixel space
 * of the upright frame; [deep] is set while a deep read works on them. [found] holds the codes
 * already decoded while the rest of a multi-code frame is still awaited.
 */
data class Sighting(
    val frameWidth: Int,
    val frameHeight: Int,
    val bounds: Rect,
    val deep: Boolean,
    val found: List<Detection> = emptyList(),
)

/**
 * Whether this stands for a mini program code that was located but could not be read. Its text
 * is then only a key, and it is offered to WeChat as a picture rather than routed by content.
 */
val Detection.isLocatedMiniCode: Boolean get() = format == MINI_LOCATED_FORMAT

/** A located mini program code as an entry among read codes, keyed by its place in the image. */
fun MiniCode.asLocatedDetection(width: Int, height: Int): Detection {
    val b = bounds(width, height)
    val corners = listOf(
        PointF(b.left.toFloat(), b.top.toFloat()), PointF(b.right.toFloat(), b.top.toFloat()),
        PointF(b.right.toFloat(), b.bottom.toFloat()), PointF(b.left.toFloat(), b.bottom.toFloat()),
    )
    return Detection("located:${center.x.roundToInt()},${center.y.roundToInt()}", MINI_LOCATED_FORMAT, corners)
}

/** The region of [frame] a located mini program code covers, for handing to WeChat. */
fun Detection.locatedCrop(frame: Bitmap): Bitmap? {
    val b = bounds
    return cropForWeChat(frame, Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
}

internal fun List<Detection>.mergedWith(other: List<Detection>): List<Detection> {
    val seen = mapTo(HashSet()) { it.text }
    return this + other.filter { seen.add(it.text) }
}
