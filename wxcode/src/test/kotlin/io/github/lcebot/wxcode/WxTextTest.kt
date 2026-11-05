package io.github.lcebot.wxcode

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real payload and WeChat's text for it come from the same code, read by FerryQR and by
 * WeChat's own scanner; the other cases come from the reference implementation of libQBar's codec.
 */
class WxTextTest {

    @Test
    fun matchesWeChatOnARealCode() {
        assertEquals(
            "llXZYz_50LFb)l8ZZXy6zl947c70008&type=invite",
            WxText.forWeChat("%XPP0NGCPMUI8VH\$XP+-.Z1:+0\$U03*WR0CST:/5X40N J6 6"),
        )
    }

    @Test
    fun keepsLeadingZeros() {
        assertEquals("0014&bw", WxText.forWeChat("00AB:CD"))
        assertEquals("0", WxText.forWeChat("0"))
    }

    @Test
    fun convertsShortPayloads() {
        assertEquals("z", WxText.forWeChat("Z"))
        assertEquals("5E", WxText.forWeChat("A0"))
        assertEquals("3l/JB;Iu:v", WxText.forWeChat("HELLO WORLD"))
    }

    @Test
    fun leavesOtherTextAsItIs() {
        assertEquals("https://example.com/a", WxText.forWeChat("https://example.com/a"))
        assertEquals("", WxText.forWeChat(""))
    }
}
