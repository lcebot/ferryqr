package io.github.lcebot.ferryqr.scan

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

/** A decoded symbol; [corners] are in the pixel space of the upright frame it was read from. */
data class Detection(
    val text: String,
    val format: String,
    val corners: List<PointF>,
) {
    // Worked out once, since a detection never changes and both are read on every camera frame.
    val center: PointF = if (corners.isEmpty()) PointF() else PointF(
        corners.map { it.x }.average().toFloat(),
        corners.map { it.y }.average().toFloat(),
    )

    val bounds: RectF = if (corners.isEmpty()) RectF() else RectF(
        corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y },
    )
}

/**
 * Codes located in a live frame but not decoded yet. [bounds] covers all of them in the pixel space
 * of the upright frame; [deep] is set once deep reads work on them. [found] holds the codes
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
 * A code found in a frame or an image: one that was read, or a mini program code that was located
 * but could not be read, which is offered to WeChat as its picture. [center] and [bounds] are in the
 * pixel space of the upright frame or image it was found in.
 */
sealed interface Found {
    val center: PointF
    val bounds: RectF

    /**
     * Tells the code from the others found with it, and can be kept in saved state: a read code's
     * text, or a located code's place as a [Point], which no text can be equal to.
     */
    val key: Any

    data class Read(val detection: Detection) : Found {
        override val center: PointF get() = detection.center
        override val bounds: RectF get() = detection.bounds
        override val key: Any get() = detection.text
    }

    /** [mini] as the mini program check located it, in [region] of its frame. */
    data class Located(val mini: MiniCode, val region: Rect) : Found {
        override val bounds: RectF = RectF(region)
        override val center: PointF = PointF(bounds.centerX(), bounds.centerY())
        override val key: Any = Point(mini.center.x.roundToInt(), mini.center.y.roundToInt())

        /** The region of [frame] the code covers, for handing to WeChat. */
        fun crop(frame: Bitmap): Bitmap? = frame.cropForWeChat(region)
    }
}

/** The code read when it is the only one found, which then needs no choosing. */
fun List<Found>.soleRead(): Detection? = (singleOrNull() as? Found.Read)?.detection

/** This code as found in a [width] by [height] frame, its region clipped to the frame. */
fun MiniCode.locatedIn(width: Int, height: Int): Found.Located = Found.Located(this, bounds(width, height))

internal fun List<Detection>.mergedWith(other: List<Detection>): List<Detection> {
    val seen = mapTo(HashSet()) { it.text }
    return this + other.filter { seen.add(it.text) }
}
