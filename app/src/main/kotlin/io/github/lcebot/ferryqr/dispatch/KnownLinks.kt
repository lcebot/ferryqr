package io.github.lcebot.ferryqr.dispatch

import androidx.annotation.StringRes
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.openin.OpenInApps

private const val ALIPAY = Alipay.PACKAGE

/** How a vehicle's number is read from the first group of a link's pattern. */
internal enum class Numbering { NONE, AS_PRINTED, HEX }

/**
 * Every form of link FerryQR knows, in one place: which app it belongs to, if any, which only that
 * app's own scanner opens properly, and what it is called. The first entry that matches decides,
 * so a specific form comes before the host it shares. An entry without an [owner] is only named.
 *
 * [caseSensitive] keeps a form that its app recognises by exact prefix, case included.
 */
internal enum class KnownLink(
    pattern: String,
    val owner: String?,
    @StringRes val title: Int,
    val numbering: Numbering = Numbering.NONE,
    caseSensitive: Boolean = false,
) {
    // Alipay: payment and collection codes on qr, which print personal and merchant codes alike,
    // the short links its mini programs share on ur, campaign pages such as red packet codes on
    // render, and the scanner landing page on ds.
    ALIPAY_CODE("https?://qr\\.alipay\\.com/.*", ALIPAY, R.string.title_alipay_code),
    ALIPAY_MINI_PROGRAM("https?://ur\\.alipay\\.com/.*", ALIPAY, R.string.title_alipay_mini_program),
    ALIPAY_CAMPAIGN("https?://render\\.alipay\\.com/p/.*", ALIPAY, R.string.title_alipay_campaign),
    ALIPAY_PAGE("https?://ds\\.alipay\\.com/.*", ALIPAY, R.string.app_alipay),

    // WeChat, by the forms its own scanner dispatches on and the hosts it resolves through its servers.
    WECHAT_PAY_CODE("wxp\\s*:.*|https://wx\\.tenpay\\.com/f2f.*|https://payapp\\.wechatpay\\.cn/qr/.*", OpenInApps.WECHAT, R.string.title_wechat_pay_code),
    WECHAT_MERCHANT_CODE(
        "https://payapp\\.wechatpay\\.cn/sjt/qr/.*|https://wxpay\\.wxutil\\.com/mch/pay/.*",
        OpenInApps.WECHAT, R.string.title_wechat_merchant_code,
    ),
    WECHAT_PAY_COMPUTER("weixin://wxpay/pcpayurl.*", OpenInApps.WECHAT, R.string.title_wechat_pay_computer),
    WECHAT_PAY("weixin://wxpay/.*|https://payapp\\.weixin\\.qq\\.com/.*", OpenInApps.WECHAT, R.string.title_wechat_pay),
    WECHAT_RED_PACKET("(?:wxhb|wedropf2fhb)://.*", OpenInApps.WECHAT, R.string.title_wechat_red_packet),
    WECHAT_CONTACT("https?://u\\.wechat\\.com/.*|weixin://qr/.*", OpenInApps.WECHAT, R.string.title_wechat_contact),
    WECHAT_GROUP("https?://weixin\\.qq\\.com/g/.*", OpenInApps.WECHAT, R.string.title_wechat_group),
    WECHAT_WEB_LOGIN("https://login\\.weixin\\.qq\\.com/l/.*", OpenInApps.WECHAT, R.string.title_wechat_web_login),
    WECHAT_SIGN_IN("https://open\\.weixin\\.qq\\.com/connect/confirm.*", OpenInApps.WECHAT, R.string.title_wechat_sign_in),
    // Sign-in pages of other sites carry WeChat's sign-in path anywhere in the link.
    WECHAT_OAUTH(".*wechat/oauth2.*", OpenInApps.WECHAT, R.string.title_wechat_sign_in, caseSensitive = true),
    // A mini program code that carries a plain link.
    WECHAT_MINI_PROGRAM("https?://mp\\.weixin\\.qq\\.com/a/.*", OpenInApps.WECHAT, R.string.title_wechat_mini_program),
    WECHAT_OTHER("weixin://.*|https?://weixin\\.qq\\.com/.*", OpenInApps.WECHAT, R.string.app_wechat),

    // DiDi prints a bike's decimal number in its link as hexadecimal; its e-bikes carry theirs as is.
    DIDI_BIKE("https?://(?:z\\.didi\\.cn|dc\\.tt)/htw\\?(?:.*&)?id=([0-9a-f]{1,15})(?:&.*)?", OpenInApps.DIDI, R.string.title_didi_bike, Numbering.HEX),
    DIDI_EBIKE("https?://(?:z\\.didi\\.cn|dc\\.tt)/hm\\?(?:.*&)?vehicleId=(\\w+).*", OpenInApps.DIDI, R.string.title_didi_ebike, Numbering.AS_PRINTED),
    DIDI_OTHER("https?://(?:z\\.didi\\.cn|dc\\.tt)/.*", OpenInApps.DIDI, R.string.app_didi),
    BLUEGOGO_BIKE("https?://www\\.bluegogo\\.com/qrcode\\.html\\?(?:.*&)?no=(\\w+).*", null, R.string.title_bluegogo_bike, Numbering.AS_PRINTED),

    // Meituan's bikes, which its scanner recognises by exact prefix: t9 and a D on t8 mark electric ones.
    MEITUAN_BIKE_DOWNLOAD(
        "http://www\\.mobike\\.com/download/app\\.html\\?b=([^_&#]+).*",
        OpenInApps.MEITUAN, R.string.title_meituan_bike, Numbering.AS_PRINTED, caseSensitive = true,
    ),
    MEITUAN_EBIKE_D("http://t8\\.pub/(D\\w+).*", OpenInApps.MEITUAN, R.string.title_meituan_ebike, Numbering.AS_PRINTED, caseSensitive = true),
    MEITUAN_EBIKE("http://t9\\.pub/(\\w+).*", OpenInApps.MEITUAN, R.string.title_meituan_ebike, Numbering.AS_PRINTED, caseSensitive = true),
    MEITUAN_BIKE("http://t8\\.pub/(\\w+).*", OpenInApps.MEITUAN, R.string.title_meituan_bike, Numbering.AS_PRINTED, caseSensitive = true),
    MEITUAN_BIKE_LINK("http://t[89]\\.pub.*", OpenInApps.MEITUAN, R.string.app_meituan, caseSensitive = true),
    // Meituan, Dianping, Maoyan and Mobike, among the domains Meituan's scan screen trusts, such as
    // ordering links on rms.meituan.com. Meituan opens any other link in its pages too, but it is no
    // more Meituan's than the browser's.
    DIANPING("https?://(?:[^/?#@]+\\.)?(?:dianping\\.com|dpurl\\.cn)(?:[:/?#].*)?", OpenInApps.MEITUAN, R.string.title_dianping),
    MAOYAN("https?://(?:[^/?#@]+\\.)?maoyan\\.com(?:[:/?#].*)?", OpenInApps.MEITUAN, R.string.title_maoyan),
    MEITUAN_OTHER("https?://(?:[^/?#@]+\\.)?(?:meituan\\.com|meituan\\.net|mobike\\.com)(?:[:/?#].*)?", OpenInApps.MEITUAN, R.string.app_meituan),

    // Xiaomi apps reached through private intents that no generic link resolution can find.
    XIAOMI_LOGIN("https://[^/]+\\.account\\.xiaomi\\.com/longPolling/login\\?.*", null, R.string.title_xiaomi_login, caseSensitive = true),
    MI_HOME_DEVICE(
        "https://home\\.mi\\.com/do/home\\.html\\?(?:f=xz|f=fnetsuoiea|a=).*",
        null, R.string.title_mi_home_device, caseSensitive = true,
    ),
    ;

    private val regex = Regex(
        "^(?:$pattern)$",
        if (caseSensitive) setOf(RegexOption.DOT_MATCHES_ALL) else setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** The number printed on the vehicle [uri] is the code of, for a vehicle's link. */
    fun number(uri: String): String? {
        val group = regex.matchEntire(uri)?.groupValues?.getOrNull(1) ?: return null
        return when (numbering) {
            Numbering.NONE -> null
            Numbering.AS_PRINTED -> group
            Numbering.HEX -> group.toLongOrNull(16)?.toString()
        }
    }

    companion object {
        /** The form [uri] has, or null for a link FerryQR does not know. */
        fun of(uri: String): KnownLink? = entries.firstOrNull { it.regex.matches(uri) }
    }
}
