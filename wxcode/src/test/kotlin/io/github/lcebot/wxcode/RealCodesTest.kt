package io.github.lcebot.wxcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Grids sampled from mini program codes WeChat issued, each with the content WeChat's own reader
 * gets from it. Cells other than data and format cells carry whatever the image showed there.
 */
class RealCodesTest {

    private class RealCode(val name: String, val text: String, val format: Format, val cells: IntArray)

    private val codes: List<RealCode> = javaClass.getResource("/real_codes.txt")!!.readText()
        .lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val (name, text, version, level, mask, cells) = line.split('|')
            RealCode(
                name, text,
                Format(version.toInt(), EcLevel.entries[level.toInt()], mask.toInt()),
                cells.split(' ').map { it.toInt(16) }.toIntArray(),
            )
        }
        .toList()

    @Test
    fun fixturesArePresent() {
        assertTrue(codes.size >= 10)
    }

    @Test
    fun formatAndContentRead() {
        for (code in codes) {
            val format = assertNotNull(Format.read(code.cells), code.name)
            assertEquals(code.format.index, format.index, code.name)
            assertEquals(code.text, readPayload(code.cells, format), code.name)
        }
    }

    @Test
    fun encoderReproducesDataAndFormatCells() {
        val formatRing = 1 shl FORMAT_RING
        for (code in codes) {
            val f = code.format
            val symbol = WxEncoder.encode(code.text, f.version, f.level, f.mask)
            val layout = DATA_CELLS[f.version]
            for (s in 0 until SECTORS) {
                val checked = layout[s] or (if (s % 2 == 1) formatRing else 0)
                val encoded = (0 until RINGS).fold(0) { m, r -> if (symbol.isDark(s, r)) m or (1 shl r) else m }
                assertEquals(code.cells[s] and checked, encoded and checked, "${code.name} sector $s")
            }
        }
    }

    /**
     * Ring 15 repeats ring 14 in codes WeChat issued. One cell of web_6, a 2017 code, is the only
     * exception among them, so a code may differ in one cell.
     */
    @Test
    fun ring15RepeatsRing14() {
        for (code in codes) {
            val ring15 = RING15_CELLS[code.format.version]
            val differing = (0 until SECTORS).count { s ->
                ring15[s] != 0 && (code.cells[s] ushr 15 and 1) != (code.cells[s] ushr 14 and 1)
            }
            assertTrue(differing <= 1, "${code.name}: $differing ring 15 cells differ from ring 14")

            val f = code.format
            val symbol = WxEncoder.encode(code.text, f.version, f.level, f.mask)
            for (s in 0 until SECTORS) {
                if (ring15[s] != 0) assertEquals(symbol.isDark(s, 14), symbol.isDark(s, 15), "${code.name} sector $s")
            }
        }
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
