package io.github.lcebot.wxcode

import kotlin.test.Test
import kotlin.test.assertEquals

/** Expected reports come from the reference implementation of libQBar's conversion. */
class WxTextTest {

    @Test
    fun convertsARealPayload() {
        assertEquals(
            "3Rl;w~BL'P3-.a\$qDM\$f-:io@2'8/L-X~&rl~9(cvmF",
            WxText.forWeChat("%XPP0NGCPMUI8VH\$XP+-.Z1:+0\$U03*WR0CST:/5X40N J6 6"),
        )
    }

    @Test
    fun keepsLeadingZeros() {
        assertEquals("00?s:Q", WxText.forWeChat("00AB:CD"))
    }

    @Test
    fun convertsSingleDigits() {
        assertEquals("z", WxText.forWeChat("Z"))
    }

    @Test
    fun leavesOtherTextAsItIs() {
        assertEquals("https://example.com/a", WxText.forWeChat("https://example.com/a"))
        assertEquals("", WxText.forWeChat(""))
    }
}
