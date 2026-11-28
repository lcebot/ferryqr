package io.github.lcebot.ferryqr.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

private const val MAX_STILL_SIDE = 3000

/** A region smaller than this is enlarged before a deep read, so its modules span several pixels. */
private const val MIN_REGION_SIDE = 400

/** A region narrower or shorter than this holds too few pixels to be read at all. */
private const val MIN_REGION_READ = 8

/**
 * A whole frame held while it shows frozen on screen: a camera frame, or an image of up to 3000
 * pixels a side. Its owner releases it once, when whatever shows it has left the screen, its fade
 * included, since a picture of several megabytes would otherwise wait for the collector.
 */
class FrozenFrame(val bitmap: Bitmap) {
    fun release() = bitmap.recycle()
}

/**
 * Crops and rotates the frame the same way zxing-cpp does internally, so its positions map onto
 * the returned bitmap without further transformation.
 */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val raw = toBitmap()
    val crop = cropRect
    val cropped = if (crop.left == 0 && crop.top == 0 && crop.width() == raw.width && crop.height() == raw.height) raw
    else Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height())
    // Each step leaves a whole frame behind, several megabytes, so it goes at once rather than
    // waiting for the collector several times a second.
    if (cropped !== raw) raw.recycle()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return cropped
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    // A turn by right angles moves every pixel onto another whole pixel, so filtering would only cost time.
    return Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, false).also {
        if (it !== cropped) cropped.recycle()
    }
}

/**
 * A region of an upright frame or image as a bitmap of its own, [scale] times its size there, with
 * its top left corner at [left] and [top] there. What a reader finds in [image] goes back onto the
 * frame through [toFrame]. Closing it recycles [image].
 */
class RegionCrop(val image: Bitmap, private val left: Int, private val top: Int, private val scale: Float) : AutoCloseable {

    fun toFrame(found: Detection): Detection =
        found.copy(corners = found.corners.map { PointF(left + it.x / scale, top + it.y / scale) })

    override fun close() = image.recycle()
}

/** How much a deep read enlarges a region of [width] by [height] pixels. */
private fun enlargement(width: Int, height: Int): Float {
    val longest = max(width, height)
    return if (longest < MIN_REGION_SIDE) MIN_REGION_SIDE.toFloat() / longest else 1f
}

/** [region] clipped to a [width] by [height] image, or null when too little of it is left to read. */
private fun clipped(region: Rect, width: Int, height: Int): Rect? {
    val r = Rect(region)
    if (!r.intersect(0, 0, width, height) || r.width() < MIN_REGION_READ || r.height() < MIN_REGION_READ) return null
    return r
}

/**
 * [rect] of this bitmap as a bitmap of its own, which the caller recycles, enlarged or shrunk by
 * what [scale] gives for the part of [rect] that lies in the bitmap; null when none of it does.
 * Every crop goes through here, since the platform hands back the source itself for a cut that
 * covers all of it, and the source stays with whoever owns it.
 */
fun Bitmap.ownedCrop(rect: Rect, scale: (cut: Rect) -> Float = { 1f }): Bitmap? {
    val r = Rect(rect)
    if (!r.intersect(0, 0, width, height) || r.isEmpty) return null
    val factor = scale(r)
    // Scaled in the same pass as the cut, so no cut at the region's own size is made only to be
    // thrown away. Each side keeps at least a pixel, since a thin region shrunk would otherwise
    // round to none, which the platform refuses.
    val sizing = if (factor == 1f) {
        null
    } else {
        val w = (r.width() * factor).roundToInt().coerceAtLeast(1)
        val h = (r.height() * factor).roundToInt().coerceAtLeast(1)
        Matrix().apply { setScale(w.toFloat() / r.width(), h.toFloat() / r.height()) }
    }
    val cut = Bitmap.createBitmap(this, r.left, r.top, r.width(), r.height(), sizing, true)
    return if (cut === this) copy(Bitmap.Config.ARGB_8888, false) else cut
}

/**
 * [region] of this colour image, enlarged when small if [enlarged], as zxing-cpp's deep read needs;
 * the WeChat decoder takes it at its own size, since it enlarges small codes itself. Null when too
 * little of the region lies in the image.
 */
fun Bitmap.regionCrop(region: Rect, enlarged: Boolean): RegionCrop? {
    val r = clipped(region, width, height) ?: return null
    val scale = if (enlarged) enlargement(r.width(), r.height()) else 1f
    val crop = ownedCrop(r) { scale } ?: return null
    return RegionCrop(crop, r.left, r.top, scale)
}

/**
 * [region] of this upright grey frame, as [Bitmap.regionCrop] cuts it from a colour one. Only the
 * region is turned into a bitmap, which costs far less than turning the whole frame into colour.
 */
internal fun Mat.regionCrop(region: Rect, enlarged: Boolean): RegionCrop? {
    val r = clipped(region, cols(), rows()) ?: return null
    val scale = if (enlarged) enlargement(r.width(), r.height()) else 1f
    val cut = submat(r.top, r.bottom, r.left, r.right)
    val sized = if (scale == 1f) cut else Mat()
    return try {
        if (sized !== cut) {
            Imgproc.resize(
                cut, sized,
                Size((r.width() * scale).toInt().toDouble(), (r.height() * scale).toInt().toDouble()),
                0.0, 0.0, Imgproc.INTER_LINEAR,
            )
        }
        val image = Bitmap.createBitmap(sized.cols(), sized.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(sized, image)
        RegionCrop(image, r.left, r.top, scale)
    } finally {
        if (sized !== cut) sized.release()
        cut.release()
    }
}

fun decodeStill(context: Context, uri: Uri): Bitmap? = try {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > MAX_STILL_SIDE) {
            val scale = MAX_STILL_SIDE.toFloat() / longest
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }
    // Wide gamut and HDR photos decode at twice the bytes per pixel, so the original goes at once.
    if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
} catch (e: Exception) {
    null
}
