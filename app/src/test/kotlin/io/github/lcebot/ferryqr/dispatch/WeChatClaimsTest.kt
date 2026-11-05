package io.github.lcebot.ferryqr.dispatch

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The prefixes come from the dispatch of WeChat's own scanner, so each one leads somewhere in WeChat. */
class WeChatClaimsTest {

    @Test
    fun claimsWhatWeChatDispatches() {
        listOf(
            "weixin://qr/abc@qr",
            "weixin://wxpay/bizpayurl?pr=abc",
            "wxp://f2f0abc",
            "wxp://wbf2fabc",
            "wxhb://f2f0abc",
            "wedropf2fhb://abc",
            "https://wx.tenpay.com/f2f?t=abc",
            "https://payapp.wechatpay.cn/qr/abc",
            "https://payapp.wechatpay.cn/sjt/qr/abc",
        ).forEach { assertTrue(WeChat.claims(it), it) }
    }

    @Test
    fun leavesOtherPayLinksAlone() {
        listOf(
            "https://payapp.wechatpay.cn/other",
            "https://qr.alipay.com/abc",
        ).forEach { assertFalse(WeChat.claims(it), it) }
    }
}
