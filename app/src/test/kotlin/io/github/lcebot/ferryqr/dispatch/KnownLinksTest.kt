package io.github.lcebot.ferryqr.dispatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The forms come from each app's own scanner and links, so every one of them leads somewhere in
 * that app, and a vehicle's number reads as printed on it.
 */
class KnownLinksTest {

    @Test
    fun weChatClaimsWhatItsScannerDispatches() {
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
            "https://weixin.qq.com/g/abc",
            "https://u.wechat.com/abc",
            "https://example.com/wechat/oauth2/authorize",
        ).forEach { assertTrue(Targets.WECHAT.claims(it), it) }
        listOf("https://payapp.wechatpay.cn/other", "https://qr.alipay.com/abc").forEach {
            assertFalse(Targets.WECHAT.claims(it), it)
        }
    }

    @Test
    fun eachAppClaimsItsOwnLinks() {
        assertEquals(Targets.ALIPAY, Targets.claimant("https://qr.alipay.com/fkx123"))
        assertEquals(Targets.ALIPAY, Targets.claimant("https://render.alipay.com/p/s/abc"))
        assertEquals(Targets.DIDI, Targets.claimant("https://z.didi.cn/htw?id=4E3D1B2A&p=2"))
        assertEquals(Targets.DIDI, Targets.claimant("https://dc.tt/anything"))
        assertEquals(Targets.MEITUAN, Targets.claimant("https://rms.meituan.com/dc/o/459136/2KKIfCBn7b9"))
        assertEquals(Targets.MEITUAN, Targets.claimant("https://m.dianping.com/shop/1"))
        assertEquals(Targets.MEITUAN, Targets.claimant("http://t8.pub/1145141919"))
        assertNull(Targets.claimant("https://example.com/"))
    }

    @Test
    fun meituanKeepsItsBikePrefixesExact() {
        assertNull(Targets.claimant("HTTP://T8.PUB/1145141919"))
        assertNull(Targets.claimant("https://meituan.com.example.com/"))
        assertNull(Targets.claimant("https://user@meituan.com/"))
    }

    @Test
    fun aWeChatSignInPathWinsOverTheHostItSitsOn() {
        assertEquals(Targets.WECHAT, Targets.claimant("https://i.meituan.com/wechat/oauth2/callback"))
    }

    @Test
    fun readsVehicleNumbers() {
        assertEquals(KnownLink.DIDI_BIKE, KnownLink.of("https://z.didi.cn/htw?id=4E3D1B2A&p=2"))
        assertEquals("1312627498", KnownLink.DIDI_BIKE.number("https://z.didi.cn/htw?id=4E3D1B2A&p=2"))
        assertEquals("12345678", KnownLink.DIDI_EBIKE.number("https://dc.tt/hm?vehicleId=12345678&type=code"))
        assertEquals("1145141919", KnownLink.MEITUAN_BIKE_DOWNLOAD.number("http://www.mobike.com/download/app.html?b=1145141919_1"))
        assertEquals(KnownLink.MEITUAN_EBIKE_D, KnownLink.of("http://t8.pub/D1234567"))
        assertEquals("D1234567", KnownLink.MEITUAN_EBIKE_D.number("http://t8.pub/D1234567"))
        assertEquals(KnownLink.MEITUAN_EBIKE, KnownLink.of("http://t9.pub/8661234"))
        assertEquals(KnownLink.MEITUAN_BIKE, KnownLink.of("http://t8.pub/1145141919"))
    }

    @Test
    fun namesWithoutAnOwnerStayUnclaimed() {
        assertEquals(KnownLink.BLUEGOGO_BIKE, KnownLink.of("https://www.bluegogo.com/qrcode.html?no=123456"))
        assertNull(Targets.claimant("https://www.bluegogo.com/qrcode.html?no=123456"))
        assertEquals(KnownLink.MI_HOME_DEVICE, KnownLink.of("https://home.mi.com/do/home.html?f=xz&model=a"))
        assertEquals(KnownLink.XIAOMI_LOGIN, KnownLink.of("https://c3.account.xiaomi.com/longPolling/login?ticket=1"))
        assertNull(KnownLink.of("https://home.mi.com/do/home.html?f=other"))
    }
}
