package io.github.lcebot.wxcode

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Builds mini program codes that WeChat's scanner, and [WxDecoder], read. */
object WxEncoder {

    /** Versions the encoder picks from; version 3 holds no more than version 1. */
    private const val MAX_VERSION = 2

    /**
     * [text] in the smallest version that holds it at [level]. The grid reproduces those of codes
     * WeChat issues bit for bit, given the same version, level and mask; the mask is chosen to
     * balance dark and light cells.
     *
     * @throws IllegalArgumentException when [text] does not fit any version.
     */
    fun encode(text: String, level: EcLevel = EcLevel.M): WxSymbol {
        for (version in 0..MAX_VERSION) {
            val layout = BlockLayout.of(version, level)
            val data = Segments.encode(text, layout.dataCodewords) ?: continue
            val codewords = protect(data, layout)
            val mask = (0 until 32).minBy { imbalance(codewords, Format(version, level, it)) }
            return encode(codewords, Format(version, level, mask))
        }
        throw IllegalArgumentException("text does not fit a mini program code at level $level")
    }

    /** The grid for given [text], version, [level] and [mask], as a code WeChat issued would hold it. */
    internal fun encode(text: String, version: Int, level: EcLevel, mask: Int): WxSymbol {
        val layout = BlockLayout.of(version, level)
        val data = requireNotNull(Segments.encode(text, layout.dataCodewords)) { "text does not fit" }
        return encode(protect(data, layout), Format(version, level, mask))
    }

    private fun encode(codewords: IntArray, format: Format): WxSymbol {
        val cells = IntArray(SECTORS)
        writeCodewords(codewords, format, cells)

        val word = FORMAT_CODEWORDS[format.index]
        for (k in 0 until 18) {
            val bit = ((word ushr (17 - k)) and 1) shl FORMAT_RING
            cells[2 * k + 1] = cells[2 * k + 1] or bit
            cells[2 * k + 37] = cells[2 * k + 37] or bit
        }
        for (s in MARKER_SECTORS) cells[s] = cells[s] or MARKER_RINGS

        // Ring 15 repeats the masked ring 14 beside it, as libQBar's grid builder does.
        val ring15 = RING15_CELLS[format.version]
        for (s in 0 until SECTORS) cells[s] = cells[s] or ((cells[s] shl 1) and ring15[s])

        return WxSymbol(format.version, format.level, format.mask, cells)
    }

    /** Data codewords split into blocks, each followed by its EC codewords, interleaved. */
    private fun protect(data: IntArray, layout: BlockLayout): IntArray {
        var next = 0
        val blocks = Array(layout.dataPerBlock.size) { b ->
            data.copyOfRange(next, next + layout.dataPerBlock[b]).also { next += it.size }
        }
        val ec = Array(blocks.size) { ReedSolomon.encode(blocks[it], layout.ecPerBlock) }
        return layout.interleave(blocks, ec)
    }

    /** How far the masked data cells are from half dark. */
    private fun imbalance(codewords: IntArray, format: Format): Int {
        val cells = IntArray(SECTORS)
        writeCodewords(codewords, format, cells)
        val dark = cells.sumOf { Integer.bitCount(it) }
        return abs(2 * dark - DATA_CELLS[format.version].sumOf { Integer.bitCount(it) })
    }
}

/**
 * A mini program code's grid, ready to draw. Every mark is either a [dashes] entry, an eye or the
 * badge; [WxStyle] gives their sizes. Positions are in code coordinates.
 */
class WxSymbol internal constructor(
    val version: Int,
    val level: EcLevel,
    val mask: Int,
    private val cells: IntArray,
) {
    fun isDark(sector: Int, ring: Int): Boolean = (cells[sector] ushr ring) and 1 != 0

    /**
     * Dark cells that follow each other outwards along a sector join into one radial dash, as in
     * codes WeChat issues. Each dash is a segment to stroke with width [WxStyle.DASH_WIDTH] and
     * round caps; together with the caps it spans its cells end to end.
     */
    val dashes: List<Dash> by lazy {
        val reach = (0.5 * MODULE) - WxStyle.DASH_WIDTH / 2
        buildList {
            for (s in 0 until SECTORS) {
                val angle = sectorRadians(s)
                val dx = cos(angle)
                val dy = sin(angle)
                var r = 0
                while (r < RINGS) {
                    if (!isDark(s, r)) {
                        r++
                        continue
                    }
                    var end = r
                    while (end + 1 < RINGS && isDark(s, end + 1)) end++
                    val inner = ringRadius(r) - reach
                    val outer = ringRadius(end) + reach
                    add(Dash(Point(inner * dx, inner * dy), Point(outer * dx, outer * dy)))
                    r = end + 1
                }
            }
        }
    }
}

/** A straight radial dash between two points in code coordinates. */
data class Dash(val from: Point, val to: Point)
