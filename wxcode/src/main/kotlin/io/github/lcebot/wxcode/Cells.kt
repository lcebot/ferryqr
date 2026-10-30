package io.github.lcebot.wxcode

/*
 * A grid is held as one IntArray of SECTORS ring masks: bit r of cells[s] is set when the cell in
 * sector s, ring r is dark. Data cells are read sector by sector and, within a sector, ring by
 * ring outwards, eight to a codeword with the most significant bit first.
 */

/** The unmasked codewords held in the data cells of [cells]. */
internal fun readCodewords(cells: IntArray, format: Format): IntArray {
    val layout = DATA_CELLS[format.version]
    val mask = MASK_PATTERNS[format.mask]
    val out = IntArray(BlockLayout.of(format.version, format.level).totalCodewords)
    var bit = 0
    for (s in 0 until SECTORS) {
        val value = cells[s] xor mask[s]
        var rings = layout[s]
        while (rings != 0) {
            val r = Integer.numberOfTrailingZeros(rings)
            rings = rings and (rings - 1)
            if ((value ushr r) and 1 != 0) out[bit ushr 3] = out[bit ushr 3] or (0x80 ushr (bit and 7))
            bit++
        }
    }
    return out
}

/** Writes [codewords] masked into the data cells of [cells], the inverse of [readCodewords]. */
internal fun writeCodewords(codewords: IntArray, format: Format, cells: IntArray) {
    val layout = DATA_CELLS[format.version]
    val mask = MASK_PATTERNS[format.mask]
    var bit = 0
    for (s in 0 until SECTORS) {
        var rings = layout[s]
        var value = cells[s] and layout[s].inv()
        while (rings != 0) {
            val r = Integer.numberOfTrailingZeros(rings)
            rings = rings and (rings - 1)
            if ((codewords[bit ushr 3] ushr (7 - (bit and 7))) and 1 != 0) value = value or (1 shl r)
            bit++
        }
        cells[s] = value xor (mask[s] and layout[s])
    }
}

/** The text a grid holds under [format], or null when error correction or parsing fails. */
internal fun readPayload(cells: IntArray, format: Format): String? {
    val layout = BlockLayout.of(format.version, format.level)
    val blocks = layout.deinterleave(readCodewords(cells, format))
    val data = IntArray(layout.dataCodewords)
    var next = 0
    for ((b, block) in blocks.withIndex()) {
        if (!ReedSolomon.correct(block, layout.ecPerBlock)) return null
        System.arraycopy(block, 0, data, next, layout.dataPerBlock[b])
        next += layout.dataPerBlock[b]
    }
    return Segments.parse(data)
}
