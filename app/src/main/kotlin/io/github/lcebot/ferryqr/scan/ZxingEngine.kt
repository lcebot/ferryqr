package io.github.lcebot.ferryqr.scan

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader
import kotlin.math.max

/** A region smaller than this is enlarged before a deep read, so its modules span several pixels. */
private const val MIN_REGION_SIDE = 400

class ZxingEngine {

    /**
     * [suspects] are QR symbols whose finder patterns were located but whose content failed to
     * decode, the first candidates for a deep read.
     */
    class Outcome(val decoded: List<Detection>, val suspects: List<Rect>)

    private val live = BarcodeReader(
        BarcodeReader.Options(
            tryHarder = false,
            tryRotate = true,
            tryInvert = true,
            tryDownscale = true,
            returnErrors = true,
        )
    )

    private val still = BarcodeReader(
        BarcodeReader.Options(
            tryHarder = true,
            tryRotate = true,
            tryInvert = true,
            tryDownscale = true,
            tryDenoise = true,
        )
    )

    fun read(image: ImageProxy): Outcome {
        val decoded = ArrayList<Detection>()
        val suspects = ArrayList<Rect>()
        for (result in live.read(image)) {
            val detection = result.toDetection()
            when {
                detection != null -> decoded += detection
                result.error != null && result.format.isQr() -> suspects += result.position.bounds()
            }
        }
        return Outcome(decoded, suspects)
    }

    /** Deep readers for one region: a harder search, then a second binarizer for uneven light. */
    private val deep = listOf(BarcodeReader.Binarizer.LOCAL_AVERAGE, BarcodeReader.Binarizer.GLOBAL_HISTOGRAM).map { binarizer ->
        BarcodeReader(
            BarcodeReader.Options(
                tryHarder = true,
                tryRotate = true,
                tryInvert = true,
                tryDenoise = true,
                binarizer = binarizer,
            )
        )
    }

    fun read(bitmap: Bitmap): List<Detection> = still.read(bitmap).mapNotNull { it.toDetection() }

    /**
     * Reads [region] of [frame] at full resolution, which the fast read loses once it downscales,
     * enlarged when small, with each deep reader in turn. It costs a few milliseconds, far less than
     * the WeChat decoder, so it is tried first. Positions come back in [frame]'s pixels.
     */
    fun readRegion(frame: Bitmap, region: Rect): List<Detection> {
        val r = Rect(region)
        if (!r.intersect(0, 0, frame.width, frame.height) || r.width() < 8 || r.height() < 8) return emptyList()
        val crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        val longest = max(r.width(), r.height())
        val scale = if (longest < MIN_REGION_SIDE) MIN_REGION_SIDE.toFloat() / longest else 1f
        val input = if (scale > 1f) {
            Bitmap.createScaledBitmap(crop, (r.width() * scale).toInt(), (r.height() * scale).toInt(), true)
        } else crop
        try {
            for (reader in deep) {
                val found = reader.read(input).mapNotNull { it.toDetection() }
                if (found.isNotEmpty()) {
                    return found.map { d -> d.copy(corners = d.corners.map { PointF(r.left + it.x / scale, r.top + it.y / scale) }) }
                }
            }
            return emptyList()
        } finally {
            if (input !== crop) input.recycle()
            if (crop !== frame) crop.recycle()
        }
    }
}

private fun BarcodeReader.Format.isQr(): Boolean = name.contains("QR")

private fun BarcodeReader.Result.toDetection(): Detection? {
    if (error != null) return null
    val value = text?.takeIf { it.isNotEmpty() } ?: return null
    return Detection(value, format.name, position.corners())
}

private fun BarcodeReader.Position.points() = listOf(topLeft, topRight, bottomRight, bottomLeft)

private fun BarcodeReader.Position.corners(): List<PointF> =
    points().map { PointF(it.x.toFloat(), it.y.toFloat()) }

private fun BarcodeReader.Position.bounds(): Rect {
    val p = points()
    return Rect(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })
}
