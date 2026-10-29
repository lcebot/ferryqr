package com.lcebot.ferryqr.dispatch

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.lcebot.ferryqr.helper.WeChatHelper

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
 * optional LSPosed helper, the scanner is then shown [openScanner]'s text in place of the camera.
 */
internal object WeChat {
    const val PACKAGE = "com.tencent.mm"
    /**
     * Profile, group and official account codes, the international profile host, mini program codes
     * that carry a plain link, website and web client login codes, and WeChat Pay merchant codes.
     */
    private val CODES = Regex(
        "^(?:https?://weixin\\.qq\\.com/.*|https?://u\\.wechat\\.com/.*|weixin://.*|wxp\\s*:.*" +
            "|https?://mp\\.weixin\\.qq\\.com/a/.*" +
            "|https://open\\.weixin\\.qq\\.com/connect/confirm.*|https://login\\.weixin\\.qq\\.com/l/.*" +
            "|https://wx\\.tenpay\\.com/f2f.*|https://payapp\\.weixin\\.qq\\.com/.*" +
            "|https://payapp\\.wechatpay\\.cn/sjt/qr/.*|https://wxpay\\.wxutil\\.com/mch/pay/.*)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun claims(text: String) = CODES.matches(text) || text.contains("wechat/oauth2")

    fun openScanner(activity: Activity, text: String): Boolean {
        val launched = Launch.start(
            activity,
            Intent(Intent.ACTION_VIEW)
                .setComponent(ComponentName(PACKAGE, "com.tencent.mm.ui.LauncherUI"))
                .putExtra("LauncherUI.From.Scaner.Shortcut", true)
                .addFlags(NEW_TASK_CLEAR_TOP),
        )
        if (launched) WeChatHelper.handoff(activity, text)
        return launched
    }
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
