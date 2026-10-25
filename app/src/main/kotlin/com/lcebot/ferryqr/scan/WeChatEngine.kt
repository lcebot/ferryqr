package com.lcebot.ferryqr.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.king.wechat.qrcode.WeChatQRCodeDetector
import org.opencv.OpenCV
import org.opencv.core.Mat

/**
 * CNN based QR detector from WeChat. It is far slower than zxing-cpp, so it only runs on regions
 * zxing-cpp flagged as suspected codes, and on still images.
 */
class WeChatEngine(context: Context) {

    private val app = context.applicationContext
    private val lock = Any()

    @Volatile
    private var ready: Boolean? = null

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
