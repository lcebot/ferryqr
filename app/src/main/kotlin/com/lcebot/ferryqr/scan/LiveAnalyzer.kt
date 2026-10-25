package com.lcebot.ferryqr.scan

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.max

/** Minimum gap between two WeChat runs, measured from the end of the previous run. */
private const val DEEP_INTERVAL_MS = 600L
private const val MIN_REGION_PADDING = 48

class LiveAnalyzer(
    private val zxing: ZxingEngine,
    private val wechat: WeChatEngine,
    private val autoZoom: AutoZoom,
    private val onSighting: (Sighting?) -> Unit,
    private val onHit: (frame: Bitmap, detections: List<Detection>) -> Unit,
) : ImageAnalysis.Analyzer {

    /** Set before every hit so a burst of frames cannot report the same code twice. */
    @Volatile
    var paused = false

    @Volatile
    private var lastDeepAt = 0L

    /** Starts over as if the analyzer had just been created. */
    fun reset() {
        lastDeepAt = 0L
        paused = false
    }

    override fun analyze(image: ImageProxy) {
        try {
            process(image)
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy) {
        if (paused) return
        val outcome = try {
            zxing.read(image)
        } catch (e: RuntimeException) {
            return
        }

        // zxing-cpp positions are in the upright frame, so everything downstream works in that frame too.
        val rotated = image.imageInfo.rotationDegrees % 180 != 0
        val frameWidth = if (rotated) image.cropRect.height() else image.cropRect.width()
        val frameHeight = if (rotated) image.cropRect.width() else image.cropRect.height()

        if (outcome.suspects.isEmpty()) {
            onSighting(null)
            if (outcome.decoded.isNotEmpty()) deliver(image.toUprightBitmap(), outcome.decoded)
            else autoZoom.observe(emptyList(), frameWidth, frameHeight)
            return
        }

        val bounds = outcome.suspects.union()
        onSighting(Sighting(frameWidth, frameHeight, bounds, deep = false))
        // Zooming in on a small code is far cheaper than the WeChat detector, so it goes first.
        if (outcome.decoded.isEmpty() && autoZoom.observe(outcome.suspects, frameWidth, frameHeight)) return
        if (SystemClock.elapsedRealtime() - lastDeepAt < DEEP_INTERVAL_MS) {
            if (outcome.decoded.isNotEmpty()) deliver(image.toUprightBitmap(), outcome.decoded)
            return
        }

        val frame = image.toUprightBitmap()
        onSighting(Sighting(frameWidth, frameHeight, bounds, deep = true))
        val rescued = wechat.decode(frame, bounds.paddedWithin(frame))
        lastDeepAt = SystemClock.elapsedRealtime()
        val all = outcome.decoded.mergedWith(rescued)
        if (all.isNotEmpty()) {
            deliver(frame, all)
        } else {
            onSighting(Sighting(frameWidth, frameHeight, bounds, deep = false))
            frame.recycle()
        }
    }

    private fun deliver(frame: Bitmap, detections: List<Detection>) {
        paused = true
        onSighting(null)
        onHit(frame, detections)
    }
}

private fun List<Rect>.union(): Rect = Rect(first()).apply { for (i in 1 until size) union(this@union[i]) }

/** Padded so the detector sees the quiet zone around the symbols. */
private fun Rect.paddedWithin(frame: Bitmap): Rect {
    val region = Rect(this)
    val pad = max(region.width(), region.height()) / 2 + MIN_REGION_PADDING
    region.inset(-pad, -pad)
    if (!region.intersect(0, 0, frame.width, frame.height)) region.set(0, 0, frame.width, frame.height)
    return region
}
