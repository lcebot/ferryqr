@file:Suppress("DEPRECATION")

package com.lcebot.ferryqr.hook

import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.Camera
import android.media.Image
import android.os.SystemClock
import com.lcebot.ferryqr.generate.CodeGenerator
import com.lcebot.ferryqr.generate.CodeType
import com.lcebot.ferryqr.generate.Generated
import java.nio.ByteBuffer
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** How long an armed feed waits for the scanner's camera to deliver its first frame. */
private const val WAIT_MS = 8_000L

/** How long frames are replaced once the first one was; WeChat reads a clean code in far less. */
private const val FEED_MS = 3_000L

/** The symbol's share of the frame's shorter side, small enough to sit inside any scan window. */
private const val CODE_FRACTION = 0.6f

private const val DARK: Byte = 0
private const val LIGHT: Byte = -1

/** Mid-range chroma makes the frame plain grey for anything that converts it to colour. */
private const val NEUTRAL_CHROMA: Byte = -128

/**
 * Replaces the luma of WeChat's camera frames with the handed-off code for a few seconds, so its
 * scanner reads exactly that one code at once: no aiming, and no choice between several codes.
 * The symbol comes from FerryQR's own [CodeGenerator], drawn centred on an otherwise white frame.
 */
internal object FrameFeed {

    private data class Geometry(val width: Int, val height: Int, val rowStride: Int, val length: Int)

    /** A Camera1 preview frame: its luma plane, and the size of the whole frame with chroma. */
    private data class Preview(val luma: Geometry, val frameSize: Int)

    private class Session(private val modules: Int, private val dark: BooleanArray, val expiresAt: Long) {
        @Volatile
        var feedUntil = 0L
        private val lumas = ConcurrentHashMap<Geometry, ByteArray>()
        private val buffers = ConcurrentHashMap<Geometry, ByteBuffer>()

        fun luma(g: Geometry): ByteArray = lumas.getOrPut(g) { render(g) }

        /** Direct, like the camera's own buffers, for native readers that take its address. */
        fun buffer(g: Geometry): ByteBuffer =
            buffers.getOrPut(g) { ByteBuffer.allocateDirect(g.length).apply { put(luma(g)); rewind() } }

        private fun render(g: Geometry): ByteArray {
            val out = ByteArray(g.length)
            out.fill(LIGHT)
            val scale = ((min(g.width, g.height) * CODE_FRACTION).toInt() / modules).coerceAtLeast(1)
            val left = (g.width - modules * scale) / 2
            val top = (g.height - modules * scale) / 2
            for (my in 0 until modules) {
                for (mx in 0 until modules) {
                    if (!dark[my * modules + mx]) continue
                    val x = left + mx * scale
                    if (x < 0 || x + scale > g.width) continue
                    for (dy in 0 until scale) {
                        val y = top + my * scale + dy
                        if (y < 0 || y >= g.height) continue
                        val start = y * g.rowStride + x
                        if (start + scale <= g.length) out.fill(DARK, start, start + scale)
                    }
                }
            }
            return out
        }
    }

    @Volatile
    private var session: Session? = null
    private var lastId = 0L
    private val previews = WeakHashMap<Camera, Preview>()
    private val substitutes = WeakHashMap<Any, ByteBuffer>()

    /** Returns whether frames will be fed; false when the text does not fit a QR code. */
    fun arm(text: String, id: Long): Boolean {
        synchronized(this) {
            // Retries of a handoff that was already taken must not restart its window.
            if (id != 0L && id == lastId) return session != null
            lastId = id
        }
        val code = (CodeGenerator.generate(text, CodeType.QR) as? Generated.Code)?.bitmap ?: return false
        val modules = code.width
        val pixels = IntArray(modules * code.height)
        code.getPixels(pixels, 0, modules, 0, 0, modules, code.height)
        code.recycle()
        session = Session(modules, BooleanArray(pixels.size) { pixels[it] == Color.BLACK }, SystemClock.uptimeMillis() + WAIT_MS)
        return true
    }

    /** The armed session, starting its feed window on the first frame and ending it after. */
    private fun active(): Session? {
        val s = session ?: return null
        val now = SystemClock.uptimeMillis()
        val live = when (val until = s.feedUntil) {
            0L -> (now <= s.expiresAt).also { if (it) s.feedUntil = now + FEED_MS }
            else -> now <= until
        }
        if (live) return s
        synchronized(this) { if (session === s) session = null }
        return null
    }

    fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        if (data == null || camera == null) return
        val s = active() ?: return
        try {
            val preview = preview(camera, data.size) ?: return
            val luma = s.luma(preview.luma)
            System.arraycopy(luma, 0, data, 0, luma.size)
            data.fill(NEUTRAL_CHROMA, luma.size, data.size)
        } catch (t: Throwable) {
            HookLog.w("preview frame feed failed", t)
        }
    }

    private fun preview(camera: Camera, frameSize: Int): Preview? {
        synchronized(previews) { previews[camera] }?.takeIf { it.frameSize == frameSize }?.let { return it }
        val parameters = camera.parameters
        val size = parameters.previewSize ?: return null
        val w = size.width
        val h = size.height
        val preview = when (parameters.previewFormat) {
            ImageFormat.NV21 -> Preview(Geometry(w, h, w, w * h), w * h * 3 / 2)
            // Both YV12 strides are aligned to 16 bytes, the chroma one to half the luma stride.
            ImageFormat.YV12 -> {
                val stride = align16(w)
                Preview(Geometry(w, h, stride, stride * h), stride * h + align16(stride / 2) * h)
            }
            else -> null
        }
        if (preview == null || preview.frameSize != frameSize) {
            HookLog.w("unexpected preview frame: format ${parameters.previewFormat}, ${w}x$h, $frameSize bytes")
            return null
        }
        synchronized(previews) { previews[camera] = preview }
        return preview
    }

    /**
     * Camera2 buffers are mapped from the camera's own memory and may not be writable, so the luma
     * plane is not written; its getBuffer returns a copy holding the code instead.
     */
    fun onImage(image: Image) {
        if (session == null || image.format != ImageFormat.YUV_420_888) return
        val s = active() ?: return
        try {
            val plane = image.planes.firstOrNull() ?: return
            if (plane.pixelStride != 1 || !CameraHooks.watchPlane(plane.javaClass)) return
            val g = Geometry(image.width, image.height, plane.rowStride, plane.buffer.capacity())
            val substitute = s.buffer(g).duplicate()
            synchronized(substitutes) { substitutes[plane] = substitute }
        } catch (t: Throwable) {
            HookLog.w("image feed failed", t)
        }
    }

    /** The buffer to return from [plane]'s getBuffer, or null to return its own. */
    fun substitute(plane: Any?): ByteBuffer? {
        if (plane == null) return null
        return synchronized(substitutes) { substitutes[plane] }
    }

    private fun align16(value: Int) = (value + 15) and 15.inv()
}
