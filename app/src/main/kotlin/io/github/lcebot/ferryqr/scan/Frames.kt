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
 * [region] of this colour image, enlarged when small if [enlarged], as zxing-cpp's deep read needs;
 * the WeChat decoder takes it at its own size, since it enlarges small codes itself. Null when too
 * little of the region lies in the image.
 */
fun Bitmap.regionCrop(region: Rect, enlarged: Boolean): RegionCrop? {
    val r = clipped(region, width, height) ?: return null
    val cut = Bitmap.createBitmap(this, r.left, r.top, r.width(), r.height())
    // A region covering the whole image comes back as the image itself, which stays the caller's.
    val crop = if (cut === this) copy(Bitmap.Config.ARGB_8888, false) else cut
    val scale = if (enlarged) enlargement(r.width(), r.height()) else 1f
    if (scale == 1f) return RegionCrop(crop, r.left, r.top, 1f)
    val input = Bitmap.createScaledBitmap(crop, (r.width() * scale).toInt(), (r.height() * scale).toInt(), true)
    if (input !== crop) crop.recycle()
    return RegionCrop(input, r.left, r.top, scale)
}

/**
 * [region] of this upright grey frame, as [Bitmap.regionCrop] cuts it from a colour one. Only the
 * region is turned into a bitmap, which costs far less than turning the whole frame into colour.
 */
internal fun Mat.regionCrop(region: Rect, enlarged: Boolean): RegionCrop? {
    val r = clipped(region, cols(), rows()) ?: return null
    val cut = submat(r.top, r.bottom, r.left, r.right)
    val scale = if (enlarged) enlargement(r.width(), r.height()) else 1f
    val sized = if (scale == 1f) cut else Mat().also {
        Imgproc.resize(
            cut, it,
            Size((r.width() * scale).toInt().toDouble(), (r.height() * scale).toInt().toDouble()),
            0.0, 0.0, Imgproc.INTER_LINEAR,
        )
    }
    return try {
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
    if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
} catch (e: Exception) {
    null
}
