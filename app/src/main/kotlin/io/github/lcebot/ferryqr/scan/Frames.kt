package io.github.lcebot.ferryqr.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import androidx.camera.core.ImageProxy
import io.github.lcebot.wxcode.LumaImage
import kotlin.math.max
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
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
 * Where [uprightGray] turns frames and the mini program check does its work, kept from one frame to
 * the next by the one thread that reads them, since new buffers for every look would keep the
 * garbage collector busy on the analysis thread just while a code waits to be read. It holds
 * nothing until OpenCV is loaded.
 */
class UprightBuffer {
    internal var mat: Mat? = null

    private var luma = ByteArray(0)
    private var labels = IntArray(0)
    private var points: MatOfPoint2f? = null

    /**
     * Room for [size] grey pixels, the frame's pixels copied out for the mini program code reader.
     * It grows only with the frame and may be longer than asked for.
     */
    internal fun luma(size: Int): ByteArray {
        if (luma.size < size) luma = ByteArray(size)
        return luma
    }

    /**
     * Room for [size] component labels, which [MiniCodeCheck] reads out for every proposal it checks.
     * Like [luma], it grows only with the region and may be longer than asked for.
     */
    internal fun labels(size: Int): IntArray {
        if (labels.size < size) labels = IntArray(size)
        return labels
    }

    /** The float points [MiniCodeCheck] turns each contour into for its enclosing circle. */
    internal fun points(): MatOfPoint2f = points ?: MatOfPoint2f().also { points = it }

    /** Gives the native memory back at once rather than when the collector gets to it. */
    fun release() {
        mat?.release()
        mat = null
        points?.release()
        points = null
    }
}

/**
 * The luma plane of this image, cropped and turned upright the way zxing-cpp reads the frame, so
 * positions in it match the upright frame; null for a plane OpenCV cannot read in place. Read
 * straight from the camera's plane, it saves turning the frame into colour and back into grey.
 * Call it only once OpenCV is loaded, while the image is still open. What this returns belongs to
 * [into] and holds until the next frame is turned there.
 */
internal fun ImageProxy.uprightGray(into: UprightBuffer): Mat? = try {
    val plane = planes[0]
    // OpenCV reads a direct buffer in place, where the camera wrote it.
    val buffer = plane.buffer.takeIf { it.isDirect }
    if (buffer == null) {
        null
    } else {
        val crop = cropRect
        val turn = when (imageInfo.rotationDegrees) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> null
        }
        // The luma plane always has a pixel stride of one, but its rows may be padded, which the
        // step skips. Neither header copies a pixel, so the frame is copied only as it is turned.
        val whole = Mat(crop.bottom, crop.right, CvType.CV_8UC1, buffer, plane.rowStride.toLong())
        val cropped = whole.submat(crop.top, crop.bottom, crop.left, crop.right)
        val upright = into.mat ?: Mat().also { into.mat = it }
        try {
            if (turn == null) cropped.copyTo(upright) else Core.rotate(cropped, upright, turn)
        } finally {
            cropped.release()
            whole.release()
        }
        upright
    }
} catch (t: Throwable) {
    null
}

/** The pixels of this grey image copied into [buffer]'s room, as the mini program code reader reads them. */
internal fun Mat.toLuma(buffer: UprightBuffer): LumaImage {
    val pixels = buffer.luma(cols() * rows())
    get(0, 0, pixels)
    return LumaImage(pixels, cols(), rows())
}

/**
 * A camera frame upright, as [width] by [height] pixels. Its grey image for the detector and for
 * deep reads is read straight from the camera's luma plane, and the colour bitmap, which costs
 * several conversions of the whole frame, is made only for the steps that need colour: the frame
 * handed on with a result and the crop of a mini program code. Each is made at most once, as are
 * the grey pixels the mini program code reader reads, and the grey image lives in [buffer], which
 * the next frame reuses. Only once the WeChat engine is [ready] does the frame touch OpenCV.
 */
internal class UprightFrame(private val image: ImageProxy, val buffer: UprightBuffer, val ready: Boolean) {
    private val turned = image.imageInfo.rotationDegrees % 180 != 0
    val width = if (turned) image.cropRect.height() else image.cropRect.width()
    val height = if (turned) image.cropRect.width() else image.cropRect.height()

    private var bitmap: Bitmap? = null
    private var grayMat: Mat? = null
    private var grayTried = false
    private var lumaImage: LumaImage? = null

    /** Set once [bitmap] is handed on with a result, which then owns it. */
    var handedOn = false

    /**
     * The mini program codes already read or tried on this frame, so a deep pass that looks over the
     * frame again after relocating some of them does not decode those a second time.
     */
    val minisTried = ArrayList<MiniCode>()

    fun bitmap(): Bitmap = bitmap ?: image.toUprightBitmap().also { bitmap = it }

    /** Null until OpenCV is loaded; the colour bitmap stands in for it until then. */
    fun gray(): Mat? {
        if (!grayTried) {
            grayTried = true
            grayMat = if (ready) image.uprightGray(buffer) else null
        }
        return grayMat
    }

    /** The grey pixels for the mini program code reader, copied once; only asked for once [gray] has an image. */
    fun luma(): LumaImage = lumaImage ?: checkNotNull(gray()).toLuma(buffer).also { lumaImage = it }

    /** [region] of the frame on its own, as [regionCrop] cuts it. */
    fun region(region: Rect, enlarged: Boolean): RegionCrop? {
        val gray = gray()
        return if (gray != null) gray.regionCrop(region, enlarged) else bitmap().regionCrop(region, enlarged)
    }

    fun recycle() {
        if (!handedOn) bitmap?.recycle()
    }
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
