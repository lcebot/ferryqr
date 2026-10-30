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
import kotlin.math.min
import kotlin.math.sqrt

/** The detector's own input area, as OpenCV's WeChatQRCode sizes it. */
private const val DETECTOR_AREA = 400.0 * 400.0

/** Low on purpose: the structural check decides, the detector only proposes where to look. */
private const val MIN_PROPOSAL_SCORE = 0.2f
private const val MAX_PROPOSALS = 4

/** Mini program codes in an image: [read] ones as detections, and those only [located]. */
data class MiniCodes(val read: List<Detection>, val located: List<MiniCode>) {
    companion object {
        val NONE = MiniCodes(emptyList(), emptyList())
    }
}

/**
 * CNN based QR detector from WeChat. It is far slower than zxing-cpp, so it only runs on regions
 * zxing-cpp flagged as suspected codes, and on still images.
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
     * Mini program codes in [bitmap] that pass [MiniCodeCheck], at the places the detector proposes,
     * split into those whose content was read and those only located. With [search], an image
     * where it proposes nothing is also searched as a whole, which is too slow for live frames but
     * finds codes the detector misses on clean, rendered images.
     */
    fun readMiniCodes(bitmap: Bitmap, search: Boolean = false): MiniCodes {
        if (!ensureReady()) return MiniCodes.NONE
        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val proposed = synchronized(lock) { proposals(gray) }
                .mapNotNull { MiniCodeCheck.inRegion(gray, it) }
                .distinctBy(::cellOf)
            val found = if (proposed.isEmpty() && search) listOfNotNull(MiniCodeCheck.inImage(gray)) else proposed
            if (found.isEmpty()) return MiniCodes.NONE
            val pixels = ByteArray(gray.cols() * gray.rows())
            gray.get(0, 0, pixels)
            val frame = LumaImage(pixels, gray.cols(), gray.rows())
            val read = found.associateWith { it.read(frame) }
            MiniCodes(read.values.filterNotNull(), read.filterValues { it == null }.keys.toList())
        } catch (t: Throwable) {
            MiniCodes.NONE
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

    /** Called under [lock]. Mirrors OpenCV's WeChatQRCode::applyDetector and SSDDetector::forward. */
    private fun proposals(gray: Mat): List<Rect> {
        val net = detector() ?: return emptyList()
        val w = gray.cols()
        val h = gray.rows()
        val scale = min(1.0, sqrt(DETECTOR_AREA / (w.toDouble() * h)))
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
            return boxes.sortedByDescending { it.first }.take(MAX_PROPOSALS).map { it.second }
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
