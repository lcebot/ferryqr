package io.github.lcebot.ferryqr.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.king.wechat.qrcode.WeChatQRCodeDetector
import io.github.lcebot.wxcode.LumaImage
import org.opencv.OpenCV
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How hard a [WeChatEngine.survey] looks. The detector's input area decides how small a code it
 * still proposes: on camera frames 400 x 400, OpenCV's own size, finds codes a sixth of the frame
 * tall anywhere in view, and 800 x 800 also most of those half that size, at about four times the
 * cost. A full page screenshot shrinks a mini program code further still, and on one the detector
 * often proposes only the QR code beside it, so still images also search the whole image.
 */
enum class Look(val detectorArea: Double, val wholeImageSearch: Boolean) {
    QUICK(400.0 * 400.0, false),
    WIDE(800.0 * 800.0, false),
    THOROUGH(800.0 * 800.0, true),
}

/** Low on purpose: the structural check decides, the detector only proposes where to look. */
private const val MIN_PROPOSAL_SCORE = 0.2f

/** Pages full of product pictures yield many proposals, and a code may rank low among them. */
private const val MAX_PROPOSALS = 8

/**
 * A proposal that is not a mini program code is decoded as a QR code only from this score up, since
 * that decode costs far more than the mini program code check.
 */
private const val QR_REGION_SCORE = 0.5f

/**
 * What a look at an image found beyond what zxing-cpp reads: mini program codes that were [read]
 * and those only [located], and other code-like [regions], mostly QR codes zxing-cpp missed.
 */
class Survey(val read: List<Detection>, val located: List<MiniCode>, val regions: List<Rect>) {
    companion object {
        val NONE = Survey(emptyList(), emptyList(), emptyList())
    }
}

/**
 * WeChat's CNN code detector, run directly as a proposer, and OpenCV's WeChatQRCode as a second QR
 * decoder for regions zxing-cpp cannot read.
 */
class WeChatEngine(context: Context) {

    private val app = context.applicationContext
    private val lock = Any()

    @Volatile
    private var ready: Boolean? = null

    /** The bundled SSD detector run directly, since WeChatQRCode keeps undecoded boxes to itself. */
    private var detector: Net? = null
    private var detectorFailed = false

    private fun ensureReady(): Boolean {
        ready?.let { return it }
        synchronized(lock) {
            ready?.let { return it }
            val ok = try {
                // Loading OpenCV and copying the models is deferred until a code actually needs it.
                OpenCV.initOpenCV().also { if (it) WeChatQRCodeDetector.init(app) }
            } catch (t: Throwable) {
                false
            }
            ready = ok
            return ok
        }
    }

    /** Lets the next decode try loading OpenCV and the models again after a failed first attempt. */
    fun retryIfFailed() {
        if (ready == false) ready = null
        detectorFailed = false
    }

    /** Called under [lock]. The model files ship in the WeChatQRCode assets. */
    private fun detector(): Net? {
        detector?.let { return it }
        if (detectorFailed) return null
        return try {
            fun asset(name: String) = MatOfByte(*app.assets.open("models/$name").use { it.readBytes() })
            Dnn.readNetFromCaffe(asset("detect.prototxt"), asset("detect.caffemodel")).also { detector = it }
        } catch (t: Throwable) {
            detectorFailed = true
            null
        }
    }

    /**
     * Looks at [bitmap] with the detector, as hard as [look] says. Mini program codes pass
     * [MiniCodeCheck] at its proposals and are read where they can be; other confident proposals
     * come back as [Survey.regions] for a QR decode.
     */
    fun survey(bitmap: Bitmap, look: Look): Survey {
        if (!ensureReady()) return Survey.NONE
        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val boxes = synchronized(lock) { proposals(gray, look.detectorArea) }
            val proposed = boxes.mapNotNull { MiniCodeCheck.inRegion(gray, it.second) }.distinctBy(::cellOf)
            val searched = if (look.wholeImageSearch) MiniCodeCheck.inImage(gray)?.takeIf { code -> proposed.none { it.overlaps(code) } } else null
            val mini = proposed + listOfNotNull(searched)
            val regions = boxes
                .filter { (score, box) -> score >= QR_REGION_SCORE && mini.none { it.covers(box) } }
                .map { it.second }
            if (mini.isEmpty()) return Survey(emptyList(), emptyList(), regions)
            val pixels = ByteArray(gray.cols() * gray.rows())
            gray.get(0, 0, pixels)
            val frame = LumaImage(pixels, gray.cols(), gray.rows())
            val read = mini.associateWith { it.read(frame) }
            Survey(read.values.filterNotNull(), read.filterValues { it == null }.keys.toList(), regions)
        } catch (t: Throwable) {
            Survey.NONE
        } finally {
            rgba.release()
            gray.release()
        }
    }

    /** Overlapping proposals for one code collapse into one, keyed by a coarse grid cell. */
    private fun cellOf(code: MiniCode): Pair<Int, Int> {
        val cell = maxOf(1f, code.radius)
        return (code.center.x / cell).toInt() to (code.center.y / cell).toInt()
    }

    /** The same code found twice, once at a proposal and once by the whole image search. */
    private fun MiniCode.overlaps(other: MiniCode): Boolean =
        hypot(center.x - other.center.x, center.y - other.center.y) < maxOf(radius, other.radius)

    /** Whether a detector box is this mini program code rather than another code. */
    private fun MiniCode.covers(box: Rect): Boolean =
        hypot(center.x - box.exactCenterX(), center.y - box.exactCenterY()) < radius

    /**
     * Called under [lock]. Mirrors OpenCV's WeChatQRCode::applyDetector and SSDDetector::forward.
     * Returns the best [MAX_PROPOSALS] boxes with their scores.
     */
    private fun proposals(gray: Mat, area: Double): List<Pair<Float, Rect>> {
        val net = detector() ?: return emptyList()
        val w = gray.cols()
        val h = gray.rows()
        val scale = min(1.0, sqrt(area / (w.toDouble() * h)))
        val small = Mat()
        Imgproc.resize(gray, small, Size((w * scale).toInt().toDouble(), (h * scale).toInt().toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
        val blob = Dnn.blobFromImage(small, 1.0 / 255, Size(small.cols().toDouble(), small.rows().toDouble()), Scalar(0.0), false, false)
        net.setInput(blob, "data")
        val out = net.forward("detection_output")
        try {
            val rows = (out.total() / 7).toInt()
            if (rows == 0) return emptyList()
            val values = FloatArray(rows * 7)
            val flat = out.reshape(1, rows)
            flat.get(0, 0, values)
            flat.release()
            val boxes = ArrayList<Pair<Float, Rect>>()
            for (i in 0 until rows) {
                val o = i * 7
                // Column 1 is the label, where 1 means a code; column 2 is the score.
                if (values[o + 1] != 1f || values[o + 2] < MIN_PROPOSAL_SCORE) continue
                val box = Rect(
                    (values[o + 3] * w).toInt().coerceIn(0, w - 1), (values[o + 4] * h).toInt().coerceIn(0, h - 1),
                    (values[o + 5] * w).toInt().coerceIn(0, w - 1), (values[o + 6] * h).toInt().coerceIn(0, h - 1),
                )
                if (!box.isEmpty) boxes += values[o + 2] to box
            }
            return boxes.sortedByDescending { it.first }.take(MAX_PROPOSALS)
        } finally {
            small.release()
            blob.release()
            out.release()
        }
    }

    fun decode(bitmap: Bitmap, region: Rect? = null): List<Detection> {
        if (!ensureReady()) return emptyList()
        val area = region?.let { Rect(it) }?.takeIf { it.intersect(0, 0, bitmap.width, bitmap.height) }
        val source = if (area == null) bitmap
        else Bitmap.createBitmap(bitmap, area.left, area.top, area.width(), area.height())
        val dx = area?.left ?: 0
        val dy = area?.top ?: 0
        val points = ArrayList<Mat>()
        return try {
            synchronized(lock) {
                val texts = WeChatQRCodeDetector.detectAndDecode(source, points)
                texts.mapIndexedNotNull { i, text ->
                    if (text.isEmpty()) null
                    else Detection(text, "QR_CODE", points.getOrNull(i)?.corners(dx, dy).orEmpty())
                }
            }
        } catch (t: Throwable) {
            emptyList()
        } finally {
            points.forEach { it.release() }
            if (source !== bitmap) source.recycle()
        }
    }
}

private fun Mat.corners(dx: Int, dy: Int): List<PointF> = (0 until rows()).map { row ->
    PointF(get(row, 0)[0].toFloat() + dx, get(row, 1)[0].toFloat() + dy)
}
