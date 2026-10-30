package io.github.lcebot.ferryqr.scan

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.hypot
import kotlin.math.max

/**
 * While nothing is in view, the detector looks for codes zxing-cpp cannot see at this interval. It
 * is a rate limit, not the time a look takes.
 */
private const val SURVEY_INTERVAL_MS = 400L

/** Between two deep reads of the codes waiting to be read. */
private const val DEEP_INTERVAL_MS = 150L

/** The WeChat decoder costs far more than a deep zxing-cpp read, so one region gets it this rarely. */
private const val WECHAT_INTERVAL_MS = 400L

/**
 * Once a code was read, the result waits at most this long for the other codes in view, so a frame
 * with several codes is reported whole instead of by its quickest one.
 */
private const val COLLECT_MS = 500L

/** A waiting code not seen again for this long has left the view. */
private const val CANDIDATE_KEEP_MS = 900L

/** Codes decoded per deep pass, so a page full of codes cannot stall a frame. */
private const val DEEP_PER_PASS = 3

private const val MIN_REGION_PADDING = 48

/**
 * A located mini program code that cannot be read is offered to WeChat once two looks in a row
 * agree; a code that was read is reported at once.
 */
private const val MINI_CONFIRMATIONS = 2

/** Looks further apart than this do not count as agreeing, and a muted code is free again after it. */
private const val MINI_KEEP_MS = 1500L

/**
 * Reads every code in view, as a set. Each frame, zxing-cpp reads what it can and points out QR
 * codes it located but could not read. The WeChat detector, run every [SURVEY_INTERVAL_MS] while
 * nothing is in view and once more before a result is reported, finds what zxing-cpp cannot see:
 * mini program codes, and QR codes zxing-cpp missed. Everything located but not read is a
 * candidate: the camera focuses and meters on it, zooms toward it while it is small, and deep
 * reads try it every [DEEP_INTERVAL_MS], first zxing-cpp on the region at full resolution, then
 * the WeChat decoder. A result goes out when every candidate has been read or the collection
 * window has passed; mini program codes still unread then go along as located entries.
 *
 * Runs on a single analysis thread; only [paused], [reset] and [muteMiniCode] are touched from
 * elsewhere.
 */
class LiveAnalyzer(
    private val zxing: ZxingEngine,
    private val wechat: WeChatEngine,
    private val autoZoom: AutoZoom,
    private val onSighting: (Sighting?) -> Unit,
    private val onHit: (frame: Bitmap, detections: List<Detection>, alreadyShown: Set<String>) -> Unit,
    private val onMiniCode: (crop: Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {

    private enum class Kind { QR, MINI }

    /** A code located in view but not read yet, followed across frames by where it is. */
    private class Candidate(var region: Rect, val kind: Kind, var seenAt: Long) {
        var mini: MiniCode? = null
        var wechatAt = 0L
    }

    /** Set before every hit so a burst of frames cannot report the same code twice. */
    @Volatile
    var paused = false

    @Volatile
    private var clearRequested = false

    /** Codes read so far in the current collection, keyed by content. */
    private val collected = LinkedHashMap<String, Detection>()
    private var collectingSince = 0L

    /** Whether the current collection already had its look for codes zxing-cpp cannot see. */
    private var surveyedForHit = false

    /** Collected codes already sent to the screen in a sighting during this collection. */
    private val announced = HashSet<String>()

    private val candidates = ArrayList<Candidate>()
    private var lastDeepAt = 0L
    private var lastSurveyAt = 0L

    /** Idle looks alternate between the quick and the wide one; the wide one finds smaller codes. */
    private var surveys = 0

    private var miniSeen: MiniCode? = null
    private var miniSeenAt = 0L
    private var miniHits = 0

    /** Set after the user dismissed a mini program code, until that code has been out of view. */
    @Volatile
    private var miniMuted = false
    private var miniMutedSeenAt = 0L

    /** Starts over as if the analyzer had just been created. */
    fun reset() {
        clearRequested = true
        miniMuted = false
        paused = false
    }

    /** The mini program code just dismissed is not reported again until it leaves the view. */
    fun muteMiniCode() {
        miniMutedSeenAt = SystemClock.elapsedRealtime()
        miniMuted = true
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
            startOver()
        }
        val fast = try {
            zxing.read(image)
        } catch (e: RuntimeException) {
            return
        }

        // zxing-cpp positions are in the upright frame, so everything downstream works in that frame too.
        val rotation = image.imageInfo.rotationDegrees
        val frameWidth = if (rotation % 180 != 0) image.cropRect.height() else image.cropRect.width()
        val frameHeight = if (rotation % 180 != 0) image.cropRect.width() else image.cropRect.height()
        val now = SystemClock.elapsedRealtime()
        // Only codes the screen could draw in an earlier frame count as already shown.
        val shownBefore = announced.toSet()
        val frame = UprightFrame(image)

        try {
            fast.decoded.forEach { collected[it.text] = it }
            fast.suspects.forEach { addCandidate(it, Kind.QR, now) }
            dropRead()
            candidates.removeAll { now - it.seenAt > CANDIDATE_KEEP_MS }

            if (collected.isNotEmpty() && collectingSince == 0L) collectingSince = now

            // Before reporting, one look for the codes zxing-cpp cannot see, so the hit holds every
            // code in view and not only the ones zxing-cpp read.
            if (collected.isNotEmpty() && !surveyedForHit) {
                surveyedForHit = true
                survey(frame.get(), Look.WIDE, now)
            }

            if (candidates.isEmpty()) {
                if (collected.isNotEmpty()) {
                    finish(frame, shownBefore)
                } else {
                    onSighting(null)
                    autoZoom.observe(emptyList(), frameWidth, frameHeight)
                    lookAround(frame, now)
                }
                return
            }

            val waiting = candidates.map { it.region }
            autoZoom.focusOn(candidates.minBy { it.seenAt }.region, frameWidth, frameHeight, rotation)
            onSighting(sighting(frameWidth, frameHeight, waiting.union(), deep = false))
            // Zooming in on a small code is far cheaper than a deep read, so it goes first. While
            // collecting it stays off, since zooming could push the codes already read out of view.
            if (collected.isEmpty() && autoZoom.observe(waiting, frameWidth, frameHeight)) return

            if (now - lastDeepAt >= DEEP_INTERVAL_MS) {
                onSighting(sighting(frameWidth, frameHeight, waiting.union(), deep = true))
                deepRead(frame.get(), now)
                lastDeepAt = SystemClock.elapsedRealtime()
                onSighting(candidates.takeIf { it.isNotEmpty() }?.let {
                    sighting(frameWidth, frameHeight, it.map { c -> c.region }.union(), deep = false)
                })
            }

            if (collected.isEmpty()) {
                offerUnreadMiniCode(frame, now)
                return
            }
            if (candidates.isEmpty() || now - collectingSince >= COLLECT_MS) finish(frame, shownBefore)
        } finally {
            frame.recycle()
        }
    }

    /** Converts the frame to an upright bitmap once, and only when a step needs it. */
    private class UprightFrame(private val image: ImageProxy) {
        private var bitmap: Bitmap? = null
        var handedOn = false

        fun get(): Bitmap = bitmap ?: image.toUprightBitmap().also { bitmap = it }

        fun recycle() {
            if (!handedOn) bitmap?.recycle()
        }
    }

    /** While nothing is in view, looks for codes zxing-cpp cannot see, alternating quick and wide looks. */
    private fun lookAround(frame: UprightFrame, now: Long) {
        val confirming = miniHits in 1 until MINI_CONFIRMATIONS
        if (!confirming && now - lastSurveyAt < SURVEY_INTERVAL_MS) {
            if (miniSeen != null && now - miniSeenAt > MINI_KEEP_MS) forgetMiniCode()
            return
        }
        val look = if (surveys++ % 2 == 0) Look.QUICK else Look.WIDE
        survey(frame.get(), look, now)
        if (collected.isNotEmpty()) {
            // A mini program code that was read needs no second look: its error correction vouches for it.
            surveyedForHit = true
            collectingSince = now
            if (candidates.isEmpty()) finish(frame, emptySet())
            return
        }
        offerUnreadMiniCode(frame, now)
    }

    /**
     * Runs the detector on [frame]: mini program codes it reads join the collection, and what it
     * only locates becomes a candidate, unless it is a code already read.
     */
    private fun survey(frame: Bitmap, look: Look, now: Long) {
        val found = wechat.survey(frame, look)
        lastSurveyAt = SystemClock.elapsedRealtime()
        found.read.forEach { collected.putIfAbsent(it.text, it) }
        found.located.forEach { code ->
            addCandidate(code.bounds(frame.width, frame.height), Kind.MINI, now)?.mini = code
        }
        found.regions.forEach { addCandidate(it, Kind.QR, now) }
        dropRead()
    }

    /** Adds a candidate, or updates the one already at that place; returns it. */
    private fun addCandidate(region: Rect, kind: Kind, now: Long): Candidate? {
        if (region.isEmpty) return null
        val same = candidates.firstOrNull { it.kind == kind && it.region.overlapsMostly(region) }
        if (same != null) {
            same.region = Rect(region)
            same.seenAt = now
            return same
        }
        return Candidate(Rect(region), kind, now).also { candidates += it }
    }

    /** A candidate where a code was read in the meantime is that code. */
    private fun dropRead() {
        if (collected.isEmpty()) return
        val read = collected.values.map { it.bounds }
        candidates.removeAll { c -> read.any { b -> c.region.contains(b.centerX().toInt(), b.centerY().toInt()) } }
    }

    /**
     * Tries to read the oldest waiting codes. QR codes first get zxing-cpp at full resolution on
     * their region, which costs a few milliseconds, then the WeChat decoder at most every
     * [WECHAT_INTERVAL_MS]. Mini program codes are read by looking at the frame again.
     */
    private fun deepRead(frame: Bitmap, now: Long) {
        if (candidates.any { it.kind == Kind.MINI }) survey(frame, Look.QUICK, now)
        for (candidate in candidates.filter { it.kind == Kind.QR }.sortedBy { it.seenAt }.take(DEEP_PER_PASS)) {
            val region = candidate.region.paddedWithin(frame)
            var read = zxing.readRegion(frame, region)
            if (read.isEmpty() && now - candidate.wechatAt >= WECHAT_INTERVAL_MS) {
                candidate.wechatAt = now
                read = wechat.decode(frame, region)
            }
            read.forEach { collected.putIfAbsent(it.text, it) }
        }
        dropRead()
    }

    /**
     * With nothing read, a mini program code that cannot be read is offered to WeChat once two
     * looks in a row agree on it; a dismissed one stays muted until it has left the view.
     */
    private fun offerUnreadMiniCode(frame: UprightFrame, now: Long) {
        val found = candidates.filter { it.kind == Kind.MINI && it.seenAt == now }.mapNotNull { it.mini }.maxByOrNull { it.radius }
        if (miniMuted) {
            if (found != null) miniMutedSeenAt = now
            else if (now - miniMutedSeenAt > MINI_KEEP_MS) miniMuted = false
            return
        }
        if (found == null) {
            if (miniSeen != null && now - miniSeenAt > MINI_KEEP_MS) forgetMiniCode()
            return
        }
        val previous = miniSeen
        val same = previous != null && now - miniSeenAt <= MINI_KEEP_MS &&
            hypot(found.center.x - previous.center.x, found.center.y - previous.center.y) < previous.radius
        if (same && miniSeenAt == now) return
        miniHits = if (same) miniHits + 1 else 1
        miniSeen = found
        miniSeenAt = now
        if (miniHits < MINI_CONFIRMATIONS) return
        val crop = found.crop(frame.get()) ?: return
        startOver()
        paused = true
        onSighting(null)
        onMiniCode(crop)
    }

    private fun forgetMiniCode() {
        miniSeen = null
        miniHits = 0
    }

    private fun startOver() {
        collected.clear()
        announced.clear()
        candidates.clear()
        collectingSince = 0L
        surveyedForHit = false
        lastDeepAt = 0L
        forgetMiniCode()
    }

    private fun sighting(frameWidth: Int, frameHeight: Int, bounds: Rect, deep: Boolean): Sighting {
        announced.addAll(collected.keys)
        return Sighting(frameWidth, frameHeight, bounds, deep, found = collected.values.toList())
    }

    /**
     * Reports what was collected, with mini program codes located but still unread as located
     * entries, and hands the frame on with it.
     */
    private fun finish(frame: UprightFrame, shown: Set<String>) {
        val upright = frame.get()
        val located = candidates.mapNotNull { it.mini?.asLocatedDetection(upright.width, upright.height) }
        val detections = collected.values.toList() + located
        startOver()
        paused = true
        onSighting(null)
        frame.handedOn = true
        onHit(upright, detections, shown)
    }
}

private fun List<Rect>.union(): Rect = Rect(first()).apply { for (i in 1 until size) union(this@union[i]) }

/** Two boxes for one code: their overlap covers most of the smaller one. */
private fun Rect.overlapsMostly(other: Rect): Boolean {
    val inter = Rect(this)
    if (!inter.intersect(other)) return false
    val smaller = minOf(width().toLong() * height(), other.width().toLong() * other.height())
    return smaller > 0 && inter.width().toLong() * inter.height() * 2 > smaller
}

/** Padded so the decoders see the quiet zone around the symbol. */
internal fun Rect.paddedWithin(frame: Bitmap): Rect {
    val region = Rect(this)
    val pad = max(region.width(), region.height()) / 2 + MIN_REGION_PADDING
    region.inset(-pad, -pad)
    if (!region.intersect(0, 0, frame.width, frame.height)) region.set(0, 0, frame.width, frame.height)
    return region
}
