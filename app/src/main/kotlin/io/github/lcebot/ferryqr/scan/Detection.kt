package io.github.lcebot.ferryqr.scan

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF

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
 * QR codes zxing-cpp located in a live frame but could not decode yet. [bounds] covers all of them
 * in the pixel space of the upright frame; [deep] is set while the WeChat detector works on them.
 * [found] holds the codes already decoded while the rest of a multi-code frame is still awaited.
 */
data class Sighting(
    val frameWidth: Int,
    val frameHeight: Int,
    val bounds: Rect,
    val deep: Boolean,
    val found: List<Detection> = emptyList(),
)

internal fun List<Detection>.mergedWith(other: List<Detection>): List<Detection> {
    val seen = mapTo(HashSet()) { it.text }
    return this + other.filter { seen.add(it.text) }
}
