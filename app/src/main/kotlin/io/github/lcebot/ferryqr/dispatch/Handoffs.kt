package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import io.github.lcebot.ferryqr.openin.OpenInApps
import io.github.lcebot.ferryqr.openin.OpenInModule
import io.github.lcebot.wxcode.WxText

private const val NEW_TASK_CLEAR_TOP = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

/** Alipay accepts the scanned content itself, so its own scanner flow continues seamlessly. */
internal object Alipay {
    const val PACKAGE = "com.eg.android.AlipayGphone"
    /**
     * Payment and collection codes on qr, the scanner landing page on ds, the short links Alipay
     * mini programs share on ur, and campaign pages such as red packet codes on render.
     */
    private val CODES = Regex(
        "^https?://(?:(?:qr|ds|ur)\\.alipay\\.com/|render\\.alipay\\.com/p/).*",
        RegexOption.IGNORE_CASE,
    )

    fun claims(text: String) = CODES.matches(text)

    fun handoff(activity: Activity, text: String): Boolean = Launch.start(
        activity,
        Intent(Intent.ACTION_VIEW, Uri.parse("alipayqr://platformapi/startapp?saId=10000007&qrcode=" + Uri.encode(text)))
            .addFlags(NEW_TASK_CLEAR_TOP),
    )
}

/**
 * WeChat has no public entry that takes scanned content, so its scanner is opened. With the
 * optional LSPosed module, the scanner then opens the content at once, as though it had read it.
 */
internal object WeChat {
    const val PACKAGE = OpenInApps.WECHAT
    /**
     * Profile, group and official account codes, the international profile host, mini program codes
     * that carry a plain link, website and web client login codes, WeChat Pay payment and merchant
     * codes, and face to face red packet codes. The prefixes follow what WeChat's own scanner
     * dispatches on, plus the hosts it resolves through its servers.
     */
    private val CODES = Regex(
        "^(?:https?://weixin\\.qq\\.com/.*|https?://u\\.wechat\\.com/.*|weixin://.*|wxp\\s*:.*" +
            "|wxhb://.*|wedropf2fhb://.*|https?://mp\\.weixin\\.qq\\.com/a/.*" +
            "|https://open\\.weixin\\.qq\\.com/connect/confirm.*|https://login\\.weixin\\.qq\\.com/l/.*" +
            "|https://wx\\.tenpay\\.com/f2f.*|https://payapp\\.weixin\\.qq\\.com/.*" +
            "|https://payapp\\.wechatpay\\.cn/(?:sjt/)?qr/.*|https://wxpay\\.wxutil\\.com/mch/pay/.*)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun claims(text: String) = CODES.matches(text) || text.contains("wechat/oauth2")

    fun openScanner(activity: Activity, text: String): Boolean =
        launchScanner(activity).also { if (it) OpenInModule.handoff(activity, PACKAGE, text) }

    /**
     * For a mini program code that was read: the module returns [payload] as WeChat's own decoder
     * reports it, so WeChat opens the mini program as after a scan of its own. [picture] is the code
     * drawn again, which the scanner is shown instead when the camera feed is chosen or the result
     * cannot be replaced.
     */
    fun openMiniProgram(activity: Activity, payload: String, picture: Bitmap): Boolean =
        launchScanner(activity).also {
            if (it) OpenInModule.handoff(activity, PACKAGE, WxText.forWeChat(payload), OpenInApps.MINI_PROGRAM_TYPE, picture)
        }

    /** For a located code that FerryQR cannot read: [image] is its region, for the scanner to read. */
    fun openScanner(activity: Activity, image: Bitmap): Boolean =
        launchScanner(activity).also { if (it) OpenInModule.handoff(activity, PACKAGE, image) }

    private fun launchScanner(activity: Activity): Boolean = Launch.start(
        activity,
        Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(PACKAGE, "com.tencent.mm.ui.LauncherUI"))
            .putExtra("LauncherUI.From.Scaner.Shortcut", true)
            .addFlags(NEW_TASK_CLEAR_TOP),
    )
}

/**
 * Opens the scanner of [pkg] at [scanner] and has the LSPosed module hand it [text]. Without the
 * module only the scanner opens, for the user to scan the code again.
 */
private fun openScannerWithModule(activity: Activity, pkg: String, scanner: String, text: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(scanner)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return Launch.start(activity, intent).also { if (it) OpenInModule.handoff(activity, pkg, text) }
}

/** Bike and e-bike codes, and Didi's short links, which its scanner sends to the right service. */
internal object Didi {
    const val PACKAGE = OpenInApps.DIDI
    private const val SCANNER = "OneTravel://router/page/open_scan"
    private val CODES = Regex("^https?://(?:z\\.didi\\.cn|dc\\.tt)/.*", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun claims(text: String) = CODES.matches(text)

    fun openScanner(activity: Activity, text: String) = openScannerWithModule(activity, PACKAGE, SCANNER, text)
}

/**
 * Meituan's own links, which its scan screen opens in its own pages: those on its domains, such as
 * ordering links on rms.meituan.com, and the bike codes it recognises by exact prefix, case
 * included. Meituan opens any other link in its pages too, but it is no more Meituan's than the
 * browser's.
 */
internal object Meituan {
    const val PACKAGE = OpenInApps.MEITUAN
    private const val SCANNER = "imeituan://www.meituan.com/scanQRCode"
    private val BIKES = Regex(
        "^http://(?:www\\.mobike\\.com/download/app\\.html\\?b=|t8\\.pub|t9\\.pub).*",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val HOST = Regex("^https?://([^/?#:@]+)", RegexOption.IGNORE_CASE)

    /** Meituan, Dianping, Maoyan and Mobike, among the domains Meituan's scan screen trusts. */
    private val DOMAINS = listOf("meituan.com", "meituan.net", "dianping.com", "dpurl.cn", "maoyan.com", "mobike.com")

    fun claims(text: String): Boolean {
        if (BIKES.matches(text)) return true
        val host = HOST.find(text)?.groupValues?.get(1)?.lowercase() ?: return false
        return DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    fun openScanner(activity: Activity, text: String) = openScannerWithModule(activity, PACKAGE, SCANNER, text)
}

/** Xiaomi apps reached through private intents that no generic link resolution can find. */
internal object Xiaomi {
    private val ACCOUNT_LOGIN = Regex("^https://[^/]+\\.account\\.xiaomi\\.com/longPolling/login\\?.*")
    private val MI_HOME_PREFIXES = listOf(
        "https://home.mi.com/do/home.html?f=xz",
        "https://home.mi.com/do/home.html?f=fnetsuoiea",
        "https://home.mi.com/do/home.html?a=",
    )

    fun claim(activity: Activity, url: String): Boolean = when {
        ACCOUNT_LOGIN.matches(url) -> Launch.start(
            activity,
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setClassName("com.xiaomi.account", "com.xiaomi.account.ui.AccountWebActivity"),
        )
        MI_HOME_PREFIXES.any { url.startsWith(it) } -> Launch.start(activity, miHome(url))
        else -> false
    }

    /** Mi Home reads the query parameters as extras; '+' and '/' must survive query decoding. */
    private fun miHome(url: String): Intent {
        var escaped = url.replace("+", "%2B")
        val queryStart = escaped.indexOf('?')
        if (queryStart >= 0) {
            escaped = escaped.substring(0, queryStart) + escaped.substring(queryStart).replace("/", "%2F")
        }
        val uri = Uri.parse(escaped)
        val intent = Intent("com.xiaomi.smarthome.mitvconnect").setPackage("com.xiaomi.smarthome")
        for (name in uri.queryParameterNames) intent.putExtra(name, uri.getQueryParameter(name))
        return intent
    }
}
