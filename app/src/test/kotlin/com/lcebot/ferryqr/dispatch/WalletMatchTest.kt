package com.lcebot.ferryqr.dispatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletMatchTest {

    @Test
    fun weChatCodes() {
        listOf(
            "https://payapp.wechatpay.cn/sjt/qr/AbC123xyz?q=app",
            "wxp://f2f0abcdef",
            "https://wx.tenpay.com/f2f?t=abc",
            "http://weixin.qq.com/r/abc",
            "weixin://dl/scan",
            "https://open.weixin.qq.com/connect/oauth2/authorize?redirect=https://x/wechat/oauth2",
        ).forEach { assertTrue(it, WeChat.claims(it)) }
    }

    @Test
    fun otherWeChatPayPagesStayLinks() {
        assertFalse(WeChat.claims("https://pay.wechatpay.cn/doc/v3/merchant"))
        assertFalse(WeChat.claims("https://payapp.wechatpay.cn/other/path"))
    }

    @Test
    fun alipayCodes() {
        assertTrue(Alipay.claims("https://qr.alipay.com/fkx12345"))
        assertTrue(Alipay.claims("HTTPS://QR.ALIPAY.COM/abc"))
        assertFalse(Alipay.claims("https://www.alipay.com/"))
        assertFalse(WeChat.claims("https://qr.alipay.com/fkx12345"))
    }
}
