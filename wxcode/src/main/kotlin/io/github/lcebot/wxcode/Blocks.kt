package io.github.lcebot.wxcode

/**
 * Error correction levels in order of strength. The format information stores them as QR does,
 * which is not this order: M = 0, L = 1, H = 2, Q = 3. Codes WeChat issues use M.
 */
enum class EcLevel(internal val formatBits: Int) {
    L(1), M(0), Q(3), H(2);

    internal companion object {
        private val byFormatBits = entries.sortedBy { it.formatBits }

        fun ofFormatBits(bits: Int): EcLevel = byFormatBits[bits]
    }
}

/** The supported versions, 0 to 3. They share the grid and differ in which cells hold data. */
internal const val VERSIONS = 4

/** How a version and level split their codewords into Reed-Solomon blocks. */
internal class BlockLayout(val ecPerBlock: Int, val dataPerBlock: IntArray) {
    val dataCodewords = dataPerBlock.sum()
    val totalCodewords = dataCodewords + ecPerBlock * dataPerBlock.size

    /**
     * Splits the codewords read from the grid into blocks, each its data then its EC codewords.
     * Both parts are interleaved round-robin across blocks, as in QR.
     */
    fun deinterleave(codewords: IntArray): Array<IntArray> {
        val blocks = Array(dataPerBlock.size) { IntArray(dataPerBlock[it] + ecPerBlock) }
        var next = 0
        for (i in 0 until dataPerBlock.max()) {
            for (b in blocks.indices) if (i < dataPerBlock[b]) blocks[b][i] = codewords[next++]
        }
        for (i in 0 until ecPerBlock) {
            for (b in blocks.indices) blocks[b][dataPerBlock[b] + i] = codewords[next++]
        }
        return blocks
    }

    /** The inverse of [deinterleave], for blocks given as their data and their EC codewords. */
    fun interleave(data: Array<IntArray>, ec: Array<IntArray>): IntArray {
        val out = IntArray(totalCodewords)
        var next = 0
        for (i in 0 until dataPerBlock.max()) {
            for (b in data.indices) if (i < dataPerBlock[b]) out[next++] = data[b][i]
        }
        for (i in 0 until ecPerBlock) {
            for (b in ec.indices) out[next++] = ec[b][i]
        }
        return out
    }

    companion object {
        private fun layout(ec: Int, vararg data: Int) = BlockLayout(ec, data)

        private val V1 = arrayOf(layout(14, 38), layout(17, 35), layout(13, 13, 13), layout(16, 10, 10))

        /** Indexed by version, then by [EcLevel.ordinal]; version 3 reuses version 1's blocks. */
        private val LAYOUTS = arrayOf(
            arrayOf(layout(10, 28), layout(13, 25), layout(18, 20), layout(24, 14)),
            V1,
            arrayOf(layout(20, 46), layout(23, 43), layout(16, 17, 17), layout(21, 12, 12)),
            V1,
        )

        fun of(version: Int, level: EcLevel): BlockLayout = LAYOUTS[version][level.ordinal]
    }
}

/** The format information: which version, level and mask a code uses. */
internal class Format(val version: Int, val level: EcLevel, val mask: Int) {
    val index: Int get() = (version shl 7) or (level.formatBits shl 5) or mask

    companion object {
        fun ofIndex(index: Int) = Format((index shr 7) and 3, EcLevel.ofFormatBits((index shr 5) and 3), index and 31)

        /** Hamming distance up to which a word is corrected; the code's minimum distance is 6. */
        const val MAX_ERRORS = 3

        /**
         * The index of the codeword nearest to [word], packed with its distance as
         * `distance shl 16 or index`, the lower index winning ties. Comparing packed values
         * therefore orders matches by distance, then by index.
         */
        fun nearest(word: Int): Int {
            var best = Int.MAX_VALUE
            for (i in FORMAT_CODEWORDS.indices) {
                val d = Integer.bitCount(word xor FORMAT_CODEWORDS[i])
                val packed = (d shl 16) or i
                if (packed < best) best = packed
            }
            return best
        }

        fun distance(packed: Int) = packed ushr 16

        fun index(packed: Int) = packed and 0xFFFF

        /** The two 18-bit copies on the format ring, MSB first from the lowest odd sector. */
        fun words(cells: IntArray): Long {
            var first = 0
            var second = 0
            for (k in 0 until 18) {
                first = (first shl 1) or ((cells[2 * k + 1] ushr FORMAT_RING) and 1)
                second = (second shl 1) or ((cells[2 * k + 37] ushr FORMAT_RING) and 1)
            }
            return (first.toLong() shl 32) or second.toLong()
        }

        /** The format a reader takes: whichever copy is nearer a codeword, if within range. */
        fun read(cells: IntArray): Format? {
            val words = words(cells)
            val best = minOf(nearest((words ushr 32).toInt()), nearest(words.toInt()))
            return if (distance(best) <= MAX_ERRORS) ofIndex(index(best)) else null
        }
    }
}
