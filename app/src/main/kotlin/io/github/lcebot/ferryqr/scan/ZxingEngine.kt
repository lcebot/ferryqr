package io.github.lcebot.ferryqr.scan

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

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
     * Reads [region] with each deep reader in turn, at the full resolution the fast read loses once
     * it downscales. It costs a few milliseconds, far less than the WeChat decoder, so it is tried
     * first. Positions come back in the pixels of the frame [region] was cut from. The scanner and
     * the image sheet may read with one engine at once: each read hands its options to a native call
     * that keeps nothing between calls.
     */
    fun readRegion(region: RegionCrop): List<Detection> {
        for (reader in deep) {
            val found = reader.read(region.image).mapNotNull { it.toDetection() }
            if (found.isNotEmpty()) return found.map(region::toFrame)
        }
        return emptyList()
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
