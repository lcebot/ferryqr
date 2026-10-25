package com.lcebot.ferryqr.scan

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

class ZxingEngine {

    /**
     * [suspects] are QR symbols whose finder patterns were located but whose content failed to
     * decode. They are the only case worth the cost of the WeChat detector: an empty result means
     * zxing-cpp saw nothing QR-like at all.
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

    fun read(bitmap: Bitmap): List<Detection> = still.read(bitmap).mapNotNull { it.toDetection() }
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
