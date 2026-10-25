package com.lcebot.ferryqr.scan

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.max

/** Minimum gap between two WeChat runs, measured from the end of the previous run. */
private const val DEEP_INTERVAL_MS = 600L

/**
 * When a frame decodes some codes but also shows QR codes that were located and not decoded, the
 * result waits at most this long for the rest instead of reporting the first code alone.
 */
private const val COLLECT_MS = 450L

/** The collection window is short and bounded, so the WeChat detector may run more often inside it. */
private const val COLLECT_DEEP_INTERVAL_MS = 150L

private const val MIN_REGION_PADDING = 48

/** Runs on a single analysis thread; only [paused] and [reset] are touched from elsewhere. */
class LiveAnalyzer(
    private val zxing: ZxingEngine,
    private val wechat: WeChatEngine,
    private val autoZoom: AutoZoom,
    private val onSighting: (Sighting?) -> Unit,
    private val onHit: (frame: Bitmap, detections: List<Detection>, alreadyShown: Set<String>) -> Unit,
) : ImageAnalysis.Analyzer {

    /** Set before every hit so a burst of frames cannot report the same code twice. */
    @Volatile
    var paused = false

    @Volatile
    private var clearRequested = false

    private var lastDeepAt = 0L

    /** Codes decoded so far in the current multi-code collection, keyed by content. */
    private val collected = LinkedHashMap<String, Detection>()
    private var collectingSince = 0L

    /** Collected codes already sent to the screen in a sighting during this collection. */
    private val announced = HashSet<String>()

    /** Starts over as if the analyzer had just been created. */
    fun reset() {
        clearRequested = true
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
        if (clearRequested) {
            clearRequested = false
            collected.clear()
            announced.clear()
            collectingSince = 0L
            lastDeepAt = 0L
        }
        val outcome = try {
            zxing.read(image)
        } catch (e: RuntimeException) {
            return
        }

        // zxing-cpp positions are in the upright frame, so everything downstream works in that frame too.
        val rotated = image.imageInfo.rotationDegrees % 180 != 0
        val frameWidth = if (rotated) image.cropRect.height() else image.cropRect.width()
        val frameHeight = if (rotated) image.cropRect.width() else image.cropRect.height()
        val now = SystemClock.elapsedRealtime()
        // Only codes the screen could draw in an earlier frame count as already shown.
        val shownBefore = announced.toSet()

        outcome.decoded.forEach { collected[it.text] = it }
        var unresolved = outcome.suspects.size

        if (unresolved == 0) {
            onSighting(null)
            // Nothing else is waiting to be decoded, so a lone code goes out without delay.
            if (collected.isNotEmpty()) finish(image, null, shownBefore)
            else autoZoom.observe(emptyList(), frameWidth, frameHeight)
            return
        }

        val bounds = outcome.suspects.union()
        onSighting(sighting(frameWidth, frameHeight, bounds, deep = false))
        val collecting = collected.isNotEmpty()
        // Zooming in on a small code is far cheaper than the WeChat detector, so it goes first. While
        // collecting it stays off, since zooming could push the codes already read out of view.
        if (!collecting && autoZoom.observe(outcome.suspects, frameWidth, frameHeight)) return

        var frame: Bitmap? = null
        val interval = if (collecting) COLLECT_DEEP_INTERVAL_MS else DEEP_INTERVAL_MS
        if (now - lastDeepAt >= interval) {
            frame = image.toUprightBitmap()
            onSighting(sighting(frameWidth, frameHeight, bounds, deep = true))
            val readByZxing = outcome.decoded.mapTo(HashSet()) { it.text }
            val rescued = wechat.decode(frame, bounds.paddedWithin(frame)).filter { it.text !in readByZxing }
            lastDeepAt = SystemClock.elapsedRealtime()
            rescued.forEach { collected.putIfAbsent(it.text, it) }
            unresolved -= rescued.size
            onSighting(sighting(frameWidth, frameHeight, bounds, deep = false))
        }

        if (collected.isEmpty()) {
            frame?.recycle()
            return
        }
        if (collectingSince == 0L) collectingSince = now
        if (unresolved <= 0 || now - collectingSince >= COLLECT_MS) finish(image, frame, shownBefore) else frame?.recycle()
    }

    private fun sighting(frameWidth: Int, frameHeight: Int, bounds: Rect, deep: Boolean): Sighting {
        announced.addAll(collected.keys)
        return Sighting(frameWidth, frameHeight, bounds, deep, found = collected.values.toList())
    }

    private fun finish(image: ImageProxy, frame: Bitmap?, shown: Set<String>) {
        val detections = collected.values.toList()
        collected.clear()
        announced.clear()
        collectingSince = 0L
        paused = true
        onSighting(null)
        onHit(frame ?: image.toUprightBitmap(), detections, shown)
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
