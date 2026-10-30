package io.github.lcebot.wxcode

import kotlin.math.abs

/** A decoded mini program code. [homography] maps code coordinates onto the image it was read from. */
class WxCode internal constructor(
    val text: String,
    val version: Int,
    val level: EcLevel,
    val mask: Int,
    val homography: Homography,
) {
    /**
     * The corners of the square that holds every mark of the code, in image pixels: top left,
     * top right, bottom right and bottom left as the code is drawn upright.
     */
    val corners: List<Point>
        get() {
            val e = WxStyle.EXTENT
            return listOf(Point(-e, e), Point(e, e), Point(e, -e), Point(-e, -e)).map(homography::map)
        }
}

/** Reads mini program codes whose three eyes were located in an image. */
object WxDecoder {

    /** Codes smaller than this, as eye circumradius in pixels, have cells too small to read. */
    private const val MIN_RADIUS = 16.0

    /**
     * Reads the code whose eyes are at [eyes], in any order, or returns null. The eyes fix the
     * geometry up to perspective; the badge, when found, fixes that too. A small search around
     * that geometry absorbs the eyes' own error. [thorough] adds a search over perspective tilts,
     * for tilted codes whose badge is hidden or misread, which costs tens of milliseconds when
     * nothing decodes.
     */
    fun decode(image: LumaImage, eyes: List<Point>, thorough: Boolean = true): WxCode? {
        require(eyes.size == 3) { "a code has three eyes" }
        val ordered = orderEyes(eyes)
        val affine = Homography.throughEyes(ordered, 0.0, 0.0)
        if (affine.circumradius < MIN_RADIUS) return null
        val search = Search(image, affine.circumradius)

        val badge = Badge.locate(image, affine)
        val fourPoint = badge?.let { Homography.through(WxStyle.eyes + WxStyle.badge, ordered.toList() + it) }
        if (fourPoint != null) search.run(listOf(fourPoint), NEAR, strict = true)?.let { return it }
        search.run(listOf(affine), WIDE, strict = true)?.let { return it }
        if (thorough) {
            search.run(PERSPECTIVES.map { (p, q) -> Homography.throughEyes(ordered, p, q) }, TIGHT, strict = true)
                ?.let { return it }
        }
        return search.run(listOf(affine), NEAR, strict = false)
    }

    /**
     * The eyes in the order of sectors 9, 27 and 63. Sector 9 is opposite the longest side, and
     * the turn from sector 9 through 27 to 63 is clockwise in an image whose y axis points down.
     */
    internal fun orderEyes(eyes: List<Point>): Array<Point> {
        fun d2(a: Point, b: Point) = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
        val sides = listOf(Triple(0, 1, 2), Triple(0, 2, 1), Triple(1, 2, 0))
        val (a, b, right) = sides.maxBy { (i, j, _) -> d2(eyes[i], eyes[j]) }
        val p9 = eyes[right]
        var p27 = eyes[a]
        var p63 = eyes[b]
        val cross = (p27.x - p9.x) * (p63.y - p9.y) - (p27.y - p9.y) * (p63.x - p9.x)
        if (cross < 0) p27 = p63.also { p63 = p27 }
        return arrayOf(p9, p27, p63)
    }
}

/**
 * Integer offsets (centre x, centre y, scale) around an estimate, nearest first. The centre moves
 * in quarter modules and the scale in steps of 0.8%; the decodable window is about one percent of
 * scale wide.
 */
private class Neighbourhood(centre: Int, scale: Int) {
    val offsets: List<IntArray> = buildList {
        for (i in -centre..centre) for (j in -centre..centre) for (k in -scale..scale) add(intArrayOf(i, j, k))
    }.sortedBy { (i, j, k) -> i * i + j * j + k * k }
}

private val WIDE = Neighbourhood(3, 4)
private val NEAR = Neighbourhood(1, 2)
private val TIGHT = Neighbourhood(1, 1)

private const val CENTRE_STEP = 0.25 * MODULE
private const val SCALE_STEP = 0.008

/** Perspective terms (p, q) tried for a tilted code, smallest tilt first. */
private val PERSPECTIVES: List<Pair<Double, Double>> = buildList {
    for (i in -5..5) for (j in -5..5) if (i != 0 || j != 0) add(Pair(i * 0.04, j * 0.04))
}.sortedBy { (p, q) -> abs(p) + abs(q) }

/** Full reads per search pass, which bounds the cost of a frame without a code. */
private const val MAX_ATTEMPTS = 24

/**
 * A format ring this close to one colour is ignored by strict screening when it matches the
 * codeword nearest an all-dark or all-light word, since flat image regions match those.
 */
private const val MIN_RING_MINORITY = 8

private val FLAT_CODEWORDS: Set<Int> = listOf(0, (1 shl 18) - 1)
    .map { Format.nearest(it) }
    .filter { Format.distance(it) <= Format.MAX_ERRORS }
    .mapTo(HashSet()) { Format.index(it) }

private class Attempt(val geometry: Int, val method: Threshold, val format: Int)

/**
 * Tries candidate geometries in passes. Each pass screens every candidate on the format ring alone,
 * which costs a sixteenth of a full read, then fully reads the first few that pass.
 *
 * Random grids almost always land within correction range of some format codeword, so a single
 * copy proves little. Strict screening requires both copies to agree; lenient screening takes the
 * nearer copy, which only the last, small pass uses.
 *
 * Passing the screen says little about which geometry is right: the format ring sits near the
 * centre and passes over a wide range of geometries whose outer rings no longer read. So the order
 * is geometric. Every base's own geometry goes first, then the nearest adjustments, and bases in
 * their given order, which for perspectives is growing tilt.
 */
private class Search(image: LumaImage, private val radius: Double) {
    private val sampler = Sampler(image)
    private val ring = FloatArray(SECTORS)
    private val ringCells = IntArray(SECTORS)
    private val grid = FloatArray(SECTORS * RINGS)
    private val cells = IntArray(SECTORS)
    private val scratch = FloatArray(SECTORS)

    fun run(bases: List<Homography>, neighbourhood: Neighbourhood, strict: Boolean): WxCode? {
        val step = CENTRE_STEP * radius
        val geometries = ArrayList<Homography>(bases.size * neighbourhood.offsets.size)
        for (base in bases) for ((i, j, k) in neighbourhood.offsets) {
            geometries += base.adjusted(i * step, j * step, 1 + k * SCALE_STEP)
        }

        val attempts = ArrayList<Attempt>()
        for ((g, h) in geometries.withIndex()) {
            sampler.sample(h, FORMAT_RING, 1, ring)
            for (method in Threshold.entries) {
                binarize(ring, FORMAT_RING, 1, method, ringCells, scratch)
                val format = screen(ringCells, strict)
                if (format >= 0) attempts += Attempt(g, method, format)
            }
        }
        if (attempts.isEmpty()) return null

        // Geometries were built base by base, so geometry % adjustments is the adjustment's rank.
        val adjustments = neighbourhood.offsets.size
        val ranked = attempts.sortedWith(compareBy<Attempt>({ it.geometry % adjustments }, { it.geometry / adjustments }))
        var sampled = -1
        for (attempt in ranked.take(MAX_ATTEMPTS)) {
            val h = geometries[attempt.geometry]
            if (sampled != attempt.geometry) {
                sampler.sample(h, 0, RINGS, grid)
                sampled = attempt.geometry
            }
            binarize(grid, 0, RINGS, attempt.method, cells, scratch)
            val format = Format.ofIndex(attempt.format)
            val text = readPayload(cells, format) ?: continue
            return WxCode(text, format.version, format.level, format.mask, h)
        }
        return null
    }

    /** The format index the ring supports, or -1. */
    private fun screen(ringCells: IntArray, strict: Boolean): Int {
        val words = Format.words(ringCells)
        val first = Format.nearest((words ushr 32).toInt())
        val second = Format.nearest(words.toInt())
        if (!strict) {
            val best = minOf(first, second)
            return if (Format.distance(best) <= Format.MAX_ERRORS) Format.index(best) else -1
        }
        if (Format.distance(first) > Format.MAX_ERRORS || Format.distance(second) > Format.MAX_ERRORS) return -1
        val index = Format.index(first)
        if (index != Format.index(second)) return -1
        if (index in FLAT_CODEWORDS) {
            val dark = ringCells.count { (it ushr FORMAT_RING) and 1 != 0 }
            if (minOf(dark, SECTORS - dark) < MIN_RING_MINORITY) return -1
        }
        return index
    }
}
