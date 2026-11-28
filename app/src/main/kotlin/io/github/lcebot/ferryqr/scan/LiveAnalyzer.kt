package io.github.lcebot.ferryqr.scan

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.hypot
import kotlin.math.max
import org.opencv.core.Mat

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

/** Looks further apart than this do not count as agreeing. */
private const val MINI_KEEP_MS = 1500L

/** A dismissed code is ignored until it has been out of view this long. */
private const val MUTE_KEEP_MS = 1500L

/**
 * Reads every code in view, as a set. Each frame, zxing-cpp reads what it can and points out QR
 * codes it located but could not read. The WeChat detector, run every [SURVEY_INTERVAL_MS] while
 * nothing is in view and once more before a result is reported, finds what zxing-cpp cannot see:
 * mini program codes, and QR codes zxing-cpp missed. Everything located but not read is a
 * candidate: the camera focuses and meters on it, zooms toward it while it is small, and deep
 * reads try it every [DEEP_INTERVAL_MS]: a QR code gets zxing-cpp on its region at full
 * resolution, then the WeChat decoder, and a mini program code is checked again where it was
 * located. A result goes out when every candidate has been read or the collection window has
 * passed; mini program codes still unread then go along as located entries.
 *
 * Until [WeChatEngine.isReady], which the scanner's warm-up brings about off this thread, frames are
 * read with zxing-cpp alone, so a code in view as the scanner opens is not held up by the loading.
 *
 * Codes the user dismissed are muted: they are dropped as soon as they are read, before anything
 * costly runs on them, until they have left the view.
 *
 * Once it has reported a result it reports nothing more until [resume] or [reset], so the frames
 * that arrive before the scanner takes the result up cannot report it a second time.
 *
 * Runs on a single analysis thread, and reports through its callbacks there; only [paused], [reset]
 * and [resume] are touched from elsewhere.
 */
class LiveAnalyzer(
    private val zxing: ZxingEngine,
    private val wechat: WeChatEngine,
    private val autoZoom: AutoZoom,
    private val onSighting: (Sighting?) -> Unit,
    private val onHit: (frame: Bitmap, codes: List<Found>, alreadyShown: Set<String>) -> Unit,
    private val onMiniCode: (crop: Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {

    private enum class Kind { QR, MINI }

    /**
     * A code located in view but not read yet, followed across frames by where it is. [firstSeenAt]
     * is when it was located, and [seenAt] when it was last seen.
     */
    private class Candidate(var region: Rect, val kind: Kind, val firstSeenAt: Long) {
        var seenAt = firstSeenAt
        var mini: MiniCode? = null
        var wechatAt = 0L

        /** The mini program code was found again as [code] in a [width] by [height] frame at [now]. */
        fun relocate(code: MiniCode, width: Int, height: Int, now: Long) {
            mini = code
            region = code.bounds(width, height)
            seenAt = now
        }
    }

    /** The codes located but not read yet, and the pace of the deep reads that try them. */
    private class Candidates {
        private val list = ArrayList<Candidate>()
        private var lastDeepAt = 0L

        /**
         * Set from the first deep read of the codes in view until none waits any longer, so a
         * sighting tells that they are being looked at closer throughout, not only during each read.
         */
        var readingDeep = false
            private set

        val isEmpty: Boolean get() = list.isEmpty()

        /** Adds a candidate, or updates the one already at that place; returns it. */
        fun add(region: Rect, kind: Kind, now: Long): Candidate? {
            if (region.isEmpty) return null
            val same = list.firstOrNull { it.kind == kind && it.region.overlapsMostly(region) }
            if (same != null) {
                same.region = Rect(region)
                same.seenAt = now
                return same
            }
            // The first code to wait after none did has not been looked at closer yet.
            if (list.isEmpty()) readingDeep = false
            return Candidate(Rect(region), kind, now).also { list += it }
        }

        /** Drops the candidates at [places], where codes were read: each is that code. */
        fun dropAt(places: List<RectF>) {
            if (places.isEmpty()) return
            list.removeAll { c -> places.any { b -> c.region.contains(b.centerX().toInt(), b.centerY().toInt()) } }
        }

        fun expire(now: Long) {
            list.removeAll { now - it.seenAt > CANDIDATE_KEEP_MS }
        }

        fun regions(): List<Rect> = list.map { it.region }

        /**
         * Where the camera focuses: of the codes seen in the latest look, the one waiting longest,
         * so the focus neither jumps from code to code nor goes to one that has left the view.
         */
        fun focusTarget(): Rect {
            val latest = list.maxOf { it.seenAt }
            return list.filter { it.seenAt == latest }.minBy { it.firstSeenAt }.region
        }

        fun deepDue(now: Long): Boolean = now - lastDeepAt >= DEEP_INTERVAL_MS

        fun deepDone() {
            readingDeep = true
            lastDeepAt = SystemClock.elapsedRealtime()
        }

        fun minis(): List<Candidate> = list.filter { it.kind == Kind.MINI }

        /**
         * The QR codes the next deep pass tries, at most [DEEP_PER_PASS], those seen least recently
         * first. A code the surveys have stopped finding is the nearest to being dropped, so it gets
         * its tries before it goes, and the passes do not keep to the codes found most often.
         */
        fun forDeepRead(): List<Candidate> = list.filter { it.kind == Kind.QR }.sortedBy { it.seenAt }.take(DEEP_PER_PASS)

        /** The mini program codes seen in the look at [now]. */
        fun minisSeenAt(now: Long): List<MiniCode> = list.filter { it.kind == Kind.MINI && it.seenAt == now }.mapNotNull { it.mini }

        /** Every mini program code located and still unread. */
        fun located(): List<MiniCode> = list.mapNotNull { it.mini }

        fun reset() {
            list.clear()
            lastDeepAt = 0L
            readingDeep = false
        }
    }

    /**
     * The codes read so far, keyed by content, which go out together once no candidate waits any
     * longer or [COLLECT_MS] has passed since the first of them.
     */
    private class Collecting {
        private val reads = LinkedHashMap<String, Detection>()
        private var since = 0L

        /** Whether this collection already had its look for codes zxing-cpp cannot see. */
        private var looked = false

        /** Codes already sent to the screen in a sighting during this collection. */
        private val announced = HashSet<String>()

        val isEmpty: Boolean get() = reads.isEmpty()

        fun add(read: Detection) {
            reads.putIfAbsent(read.text, read)
        }

        /** Puts [read] in place of an earlier read of its code, so its corners are where it is now. */
        fun update(read: Detection) {
            reads.remove(read.text)
            reads[read.text] = read
        }

        /** Opens the collection window at [now] once the first code is in. */
        fun begin(now: Long) {
            if (reads.isNotEmpty() && since == 0L) since = now
        }

        /** Whether the collection is due its one look for codes zxing-cpp cannot see, which this uses up. */
        fun takeLook(): Boolean {
            if (reads.isEmpty() || looked) return false
            looked = true
            return true
        }

        /** A collection that the look itself started at [now] has had its look. */
        fun startedByLook(now: Long) {
            looked = true
            since = now
        }

        fun windowPassed(now: Long): Boolean = now - since >= COLLECT_MS

        /** Where each code was read. */
        fun places(): List<RectF> = reads.values.map { it.bounds }

        /** The codes sent to the screen so far. */
        fun shown(): Set<String> = announced.toSet()

        /** The codes for a sighting, which counts them as sent to the screen. */
        fun announce(): List<Detection> {
            announced.addAll(reads.keys)
            return reads.values.toList()
        }

        fun found(): List<Found> = reads.values.map(Found::Read)

        fun reset() {
            reads.clear()
            announced.clear()
            since = 0L
            looked = false
        }
    }

    /**
     * The codes the user dismissed, and an unread mini program code if one was. A dismissal is
     * handed over from another thread and taken up by the next frame; the rest runs on the analysis
     * thread.
     */
    private class Mutes {
        private class Dismissed(val texts: Set<String>, val miniCode: Boolean)

        @Volatile
        private var dismissed: Dismissed? = null

        private var texts: Set<String> = emptySet()
        private var seenAt = 0L

        /** Whether an unread mini program code is muted. */
        var miniCode = false
            private set

        /** Where each muted code was read last, so the regions there are not read again. */
        private val readAt = HashMap<String, RectF>()

        /** With neither [texts] nor [miniCode], what is muted stays so. */
        fun dismiss(texts: Set<String>, miniCode: Boolean) {
            if (texts.isNotEmpty() || miniCode) dismissed = Dismissed(texts, miniCode)
        }

        /** Drops a dismissal not taken up yet. */
        fun cancelDismissal() {
            dismissed = null
        }

        /** Mutes what was dismissed last, in place of what was muted, as seen at [now]. */
        fun take(now: Long) {
            val taken = dismissed ?: return
            dismissed = null
            texts = taken.texts
            miniCode = taken.miniCode
            seenAt = now
            readAt.clear()
        }

        /** Whether [read] is muted, in which case it counts as seen at [now]. */
        fun holds(read: Detection, now: Long): Boolean {
            if (read.text !in texts) return false
            seenAt = now
            readAt[read.text] = read.bounds
            return true
        }

        /** The muted mini program code was located again at [now]. */
        fun miniCodeSeen(now: Long) {
            seenAt = now
        }

        /** Lets everything go once nothing muted has been seen for [MUTE_KEEP_MS]. */
        fun expire(now: Long) {
            if ((texts.isNotEmpty() || miniCode) && now - seenAt > MUTE_KEEP_MS) reset()
        }

        fun places(): List<RectF> = readAt.values.toList()

        fun reset() {
            texts = emptySet()
            miniCode = false
            readAt.clear()
        }
    }

    /**
     * Confirms a mini program code located but not read: it is offered to WeChat once
     * [MINI_CONFIRMATIONS] looks in a row agree on it, each within [MINI_KEEP_MS] of the one before.
     */
    private class MiniConfirmer {
        private var seen: MiniCode? = null
        private var seenAt = 0L
        private var hits = 0

        /** Whether a code was seen and waits for the looks that confirm it, which should not wait. */
        val confirming: Boolean get() = hits in 1 until MINI_CONFIRMATIONS

        /** Counts the look at [now] that found [code], and returns whether enough looks agree on it. */
        fun offer(code: MiniCode, now: Long): Boolean {
            val previous = seen
            val same = previous != null && now - seenAt <= MINI_KEEP_MS &&
                hypot(code.center.x - previous.center.x, code.center.y - previous.center.y) < previous.radius
            if (same && seenAt == now) return false
            hits = if (same) hits + 1 else 1
            seen = code
            seenAt = now
            return hits >= MINI_CONFIRMATIONS
        }

        /** Forgets a code not seen again for [MINI_KEEP_MS]. */
        fun expire(now: Long) {
            if (seen != null && now - seenAt > MINI_KEEP_MS) reset()
        }

        fun reset() {
            seen = null
            hits = 0
        }
    }

    /** Set by the scanner whenever it would not take what a frame shows, so frames are let go unread. */
    @Volatile
    var paused = false

    /** Set as a result goes out, and cleared only by [resume] and [reset]. */
    @Volatile
    private var reported = false

    @Volatile
    private var clearRequested = false

    private val collection = Collecting()
    private val candidates = Candidates()
    private val mutes = Mutes()
    private val confirmer = MiniConfirmer()

    /** The grey frame of each [UprightFrame], turned in the same place every frame. */
    private val uprightBuffer = UprightBuffer()

    private var lastSurveyAt = 0L

    /** Idle looks alternate between the quick and the wide one; the wide one finds smaller codes. */
    private var surveys = 0

    /** Whether the sighting sent last showed a code; with nothing in view, only the first frame says so. */
    private var sighted = false

    /** Lets go of the native image the frames are read into; call it on the analysis thread, after the last frame. */
    fun release() = uprightBuffer.release()

    /** Starts over as if the analyzer had just been created. */
    fun reset() {
        mutes.cancelDismissal()
        clearRequested = true
        reported = false
    }

    /**
     * Looks for codes again. The codes in [texts] and, with [miniCode], the unread mini program code
     * in view were just dismissed, so they are muted until they leave the view; with neither, what
     * is muted stays so.
     */
    fun resume(texts: Set<String>, miniCode: Boolean) {
        mutes.dismiss(texts, miniCode)
        reported = false
    }

    /**
     * Adds [read] to the collection unless it is muted, in which case it only counts as seen. A
     * [latest] read takes the place of an earlier one of its code.
     */
    private fun collect(read: Detection, now: Long, latest: Boolean = false) {
        if (mutes.holds(read, now)) return
        if (latest) collection.update(read) else collection.add(read)
    }

    override fun analyze(image: ImageProxy) {
        try {
            process(image)
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy) {
        if (paused || reported) return
        autoZoom.watchScene(image)
        if (clearRequested) {
            clearRequested = false
            startOver()
            mutes.reset()
        }
        val fast = try {
            zxing.read(image)
        } catch (e: RuntimeException) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        mutes.take(now)

        // zxing-cpp positions are in the upright frame, so everything downstream works in that frame too.
        val rotation = image.imageInfo.rotationDegrees
        val frameWidth = if (rotation % 180 != 0) image.cropRect.height() else image.cropRect.width()
        val frameHeight = if (rotation % 180 != 0) image.cropRect.width() else image.cropRect.height()
        // Only codes the screen could draw in an earlier frame count as already shown.
        val shownBefore = collection.shown()
        val frame = UprightFrame(image, frameWidth, frameHeight, wechat.isReady)

        try {
            fast.decoded.forEach { collect(it, now, latest = true) }
            mutes.expire(now)
            fast.suspects.forEach { candidates.add(it, Kind.QR, now) }
            dropRead()
            candidates.expire(now)
            collection.begin(now)

            // Before reporting, one look for the codes zxing-cpp cannot see, so the hit holds every
            // code in view and not only the ones zxing-cpp read.
            if (collection.takeLook()) survey(frame, Look.WIDE, now)

            if (candidates.isEmpty) {
                if (!collection.isEmpty) {
                    finish(frame, shownBefore)
                } else {
                    sight(null)
                    autoZoom.observe(emptyList(), frameWidth, frameHeight)
                    lookAround(frame, now)
                }
                return
            }

            val waiting = candidates.regions()
            autoZoom.focusOn(candidates.focusTarget(), frameWidth, frameHeight, rotation)
            sight(sighting(frame, waiting, deep = candidates.readingDeep))
            // Zooming in on a small code is far cheaper than a deep read, so it goes first. While
            // collecting it stays off, since zooming could push the codes already read out of view.
            if (collection.isEmpty && autoZoom.observe(waiting, frameWidth, frameHeight)) return

            if (candidates.deepDue(now)) {
                deepRead(frame, now)
                candidates.deepDone()
                sight(if (candidates.isEmpty) null else sighting(frame, candidates.regions(), deep = true))
            }

            if (collection.isEmpty) {
                offerUnreadMiniCode(frame, now)
                return
            }
            if (candidates.isEmpty || collection.windowPassed(now)) finish(frame, shownBefore)
        } finally {
            frame.recycle()
        }
    }

    /**
     * The frame upright, as [width] by [height] pixels. Its grey image for the detector and for deep
     * reads is read straight from the camera's luma plane, and the colour bitmap, which costs several
     * conversions of the whole frame, is made only for the steps that need colour: the frame handed
     * on with a result and the crop of a mini program code. Each is made at most once, and the grey
     * image lives in [uprightBuffer], which the next frame reuses. Only once the WeChat engine is
     * [ready] does the frame touch OpenCV.
     */
    private inner class UprightFrame(private val image: ImageProxy, val width: Int, val height: Int, val ready: Boolean) {
        private var bitmap: Bitmap? = null
        private var grayMat: Mat? = null
        private var grayTried = false
        var handedOn = false

        fun get(): Bitmap = bitmap ?: image.toUprightBitmap().also { bitmap = it }

        /** Null until OpenCV is loaded; the colour bitmap stands in for it until then. */
        private fun gray(): Mat? {
            if (!grayTried) {
                grayTried = true
                grayMat = if (ready) wechat.upright(image, uprightBuffer) else null
            }
            return grayMat
        }

        fun survey(look: Look): Survey = gray()?.let { wechat.survey(it, look, uprightBuffer) } ?: wechat.survey(get(), look)

        /** The mini program code at [region], where it was located before, as [WeChatEngine.lookAgain] finds it. */
        fun lookAgain(region: Rect): Relocated? = gray()?.let { wechat.lookAgain(it, region, uprightBuffer) }

        /** [region] of the frame on its own, as [regionCrop] cuts it. */
        fun region(region: Rect, enlarged: Boolean): RegionCrop? {
            val luma = gray()
            return if (luma != null) luma.regionCrop(region, enlarged) else get().regionCrop(region, enlarged)
        }

        fun recycle() {
            if (!handedOn) bitmap?.recycle()
        }
    }

    /** While nothing is in view, looks for codes zxing-cpp cannot see, alternating quick and wide looks. */
    private fun lookAround(frame: UprightFrame, now: Long) {
        if (!confirmer.confirming && now - lastSurveyAt < SURVEY_INTERVAL_MS) {
            confirmer.expire(now)
            return
        }
        val look = if (surveys++ % 2 == 0) Look.QUICK else Look.WIDE
        survey(frame, look, now)
        if (!collection.isEmpty) {
            // A mini program code that was read needs no second look: its error correction vouches for it.
            collection.startedByLook(now)
            if (candidates.isEmpty) finish(frame, emptySet())
            return
        }
        offerUnreadMiniCode(frame, now)
    }

    /**
     * Runs the detector on [frame]: mini program codes it reads join the collection, and what it
     * only locates becomes a candidate, unless it is a code already read. Until the WeChat engine is
     * ready, there is no look.
     */
    private fun survey(frame: UprightFrame, look: Look, now: Long) {
        if (!frame.ready) return
        val found = frame.survey(look)
        lastSurveyAt = SystemClock.elapsedRealtime()
        found.read.forEach { collect(it, now) }
        if (mutes.miniCode) {
            // A muted mini program code would only be offered again, so it is not looked at closer.
            if (found.located.isNotEmpty()) mutes.miniCodeSeen(now)
        } else {
            found.located.forEach { code ->
                candidates.add(code.bounds(frame.width, frame.height), Kind.MINI, now)?.mini = code
            }
        }
        found.regions.forEach { candidates.add(it, Kind.QR, now) }
        dropRead()
    }

    /** A candidate where a code was read in the meantime, or a muted code last was, is that code. */
    private fun dropRead() = candidates.dropAt(collection.places() + mutes.places())

    /**
     * Tries to read the waiting codes. A mini program code is checked again where it was located,
     * and read there when it can be; only when one has moved off its place does the detector look
     * over the frame again. QR codes, at most [DEEP_PER_PASS], first get zxing-cpp at full
     * resolution on their region, which costs a few milliseconds, then, once the WeChat engine is
     * ready, its decoder at most every [WECHAT_INTERVAL_MS].
     */
    private fun deepRead(frame: UprightFrame, now: Long) {
        val lost = candidates.minis().filterNot { lookAgain(frame, it, now) }
        if (lost.isNotEmpty()) survey(frame, Look.QUICK, now)
        for (candidate in candidates.forDeepRead()) {
            val region = candidate.region.paddedWithin(frame.width, frame.height)
            var read = frame.region(region, enlarged = true)?.use { zxing.readRegion(it) }.orEmpty()
            if (read.isEmpty() && frame.ready && now - candidate.wechatAt >= WECHAT_INTERVAL_MS) {
                candidate.wechatAt = now
                read = frame.region(region, enlarged = false)?.use { wechat.decode(it) }.orEmpty()
            }
            read.forEach { collect(it, now) }
        }
        dropRead()
    }

    /** Checks the mini program code of [candidate] again where it was, and returns whether it is still there. */
    private fun lookAgain(frame: UprightFrame, candidate: Candidate, now: Long): Boolean {
        val found = frame.lookAgain(candidate.region) ?: return false
        candidate.relocate(found.code, frame.width, frame.height, now)
        found.read?.let { collect(it, now) }
        return true
    }

    /**
     * With nothing read, a mini program code that cannot be read is offered to WeChat once looks
     * enough agree on it; a dismissed one stays muted until it has left the view.
     */
    private fun offerUnreadMiniCode(frame: UprightFrame, now: Long) {
        if (mutes.miniCode) return
        val found = candidates.minisSeenAt(now).maxByOrNull { it.radius }
        if (found == null) {
            confirmer.expire(now)
            return
        }
        if (!confirmer.offer(found, now)) return
        val crop = found.crop(frame.get()) ?: return
        startOver()
        reported = true
        onMiniCode(crop)
    }

    private fun startOver() {
        collection.reset()
        candidates.reset()
        confirmer.reset()
    }

    /**
     * Sends [sighting] to the screen. Nothing in view is sent once, after a sighting that showed a
     * code, rather than on every idle frame. A result needs none, since the scanner clears the
     * sighting as it stops reading.
     */
    private fun sight(sighting: Sighting?) {
        if (sighting == null && !sighted) return
        sighted = sighting != null
        onSighting(sighting)
    }

    private fun sighting(frame: UprightFrame, waiting: List<Rect>, deep: Boolean): Sighting =
        Sighting(frame.width, frame.height, waiting.union(), deep, found = collection.announce())

    /**
     * Reports what was collected, with the mini program codes located but still unread, and hands
     * the frame on with it.
     */
    private fun finish(frame: UprightFrame, shown: Set<String>) {
        val upright = frame.get()
        val codes = collection.found() + candidates.located().map { it.locatedIn(upright.width, upright.height) }
        startOver()
        reported = true
        frame.handedOn = true
        onHit(upright, codes, shown)
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

/** Padded so the decoders see the quiet zone around the symbol, within a [width] by [height] frame. */
internal fun Rect.paddedWithin(width: Int, height: Int): Rect {
    val region = Rect(this)
    val pad = max(region.width(), region.height()) / 2 + MIN_REGION_PADDING
    region.inset(-pad, -pad)
    if (!region.intersect(0, 0, width, height)) region.set(0, 0, width, height)
    return region
}
