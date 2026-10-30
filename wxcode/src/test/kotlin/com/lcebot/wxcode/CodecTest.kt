package com.lcebot.wxcode

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodecTest {

    @Test
    fun reedSolomonCorrectsUpToHalfItsCodewords() {
        val random = Random(1)
        for (ec in listOf(10, 13, 17, 24)) {
            repeat(50) {
                val data = IntArray(30) { random.nextInt(256) }
                val block = data + ReedSolomon.encode(data, ec)
                val damaged = block.copyOf()
                val errors = random.nextInt(ec / 2 + 1)
                for (i in (block.indices).shuffled(random).take(errors)) damaged[i] = damaged[i] xor (1 + random.nextInt(255))
                assertTrue(ReedSolomon.correct(damaged, ec), "ec $ec with $errors errors")
                assertContentEquals(block, damaged)
            }
        }
    }

    @Test
    fun reedSolomonRejectsTooManyErrors() {
        val random = Random(2)
        var accepted = 0
        repeat(200) {
            val data = IntArray(30) { random.nextInt(256) }
            val block = data + ReedSolomon.encode(data, 10)
            for (i in block.indices.shuffled(random).take(9)) block[i] = block[i] xor (1 + random.nextInt(255))
            if (ReedSolomon.correct(block, 10)) accepted++
        }
        assertTrue(accepted < 5, "accepted $accepted blocks with 9 errors")
    }

    /**
     * The format code's minimum distance is 6, so two flipped bits always lead back to the right
     * codeword. Three may land as close to another one, and then the lower index wins.
     */
    @Test
    fun formatCodewordsCorrectFlippedBits() {
        val random = Random(3)
        fun flip(word: Int, bits: Int) = (0 until 18).shuffled(random).take(bits).fold(word) { w, b -> w xor (1 shl b) }
        for (index in FORMAT_CODEWORDS.indices) {
            val word = FORMAT_CODEWORDS[index]
            val two = Format.nearest(flip(word, 2))
            assertEquals(index, Format.index(two))
            assertEquals(2, Format.distance(two))

            val three = Format.nearest(flip(word, 3))
            assertEquals(3, Format.distance(three))
            assertTrue(Format.index(three) <= index)
        }
    }

    @Test
    fun formatFieldUsesQrLevelEncoding() {
        assertEquals(EcLevel.M, Format.ofIndex(0b0_00_00000).level)
        assertEquals(EcLevel.L, Format.ofIndex(0b0_01_00000).level)
        assertEquals(EcLevel.H, Format.ofIndex(0b0_10_00000).level)
        assertEquals(EcLevel.Q, Format.ofIndex(0b0_11_00000).level)
        for (level in EcLevel.entries) assertEquals(level, Format.ofIndex(Format(1, level, 5).index).level)
    }

    @Test
    fun encodedTextReadsBack() {
        val texts = listOf("A", "EUH YM11\$1F 8/4*BRXG8Q.7D3MH-T/YW6.79ZJ", "12345", "mixed Case 文字", "X".repeat(50))
        for (level in EcLevel.entries) for (text in texts) {
            val symbol = runCatching { WxEncoder.encode(text, level) }.getOrNull() ?: continue
            val cells = IntArray(SECTORS) { s -> (0 until RINGS).fold(0) { m, r -> if (symbol.isDark(s, r)) m or (1 shl r) else m } }
            val format = assertNotNull(Format.read(cells))
            assertEquals(symbol.version, format.version)
            assertEquals(level, format.level)
            assertEquals(text, readPayload(cells, format))
        }
        assertNotNull(WxEncoder.encode("EUH YM11\$1F 8/4*BRXG8Q.7D3MH-T/YW6.79ZJ"))
    }

    @Test
    fun textTooLongIsRejected() {
        assertFailsWith<IllegalArgumentException> { WxEncoder.encode("A".repeat(200)) }
    }

    @Test
    fun dashesStayWithinTheirCells() {
        val symbol = WxEncoder.encode("EUH YM11\$1F 8/4*BRXG8Q.7D3MH-T/YW6.79ZJ")
        assertFalse(symbol.dashes.isEmpty())
        for (dash in symbol.dashes) {
            val inner = kotlin.math.hypot(dash.from.x, dash.from.y)
            val outer = kotlin.math.hypot(dash.to.x, dash.to.y)
            assertTrue(inner <= outer)
            assertTrue(inner - WxStyle.DASH_WIDTH / 2 >= ringRadius(0) - 0.5 * MODULE - 1e-9)
            assertTrue(outer + WxStyle.DASH_WIDTH / 2 <= ringRadius(RINGS - 1) + 0.5 * MODULE + 1e-9)
        }
    }
}
