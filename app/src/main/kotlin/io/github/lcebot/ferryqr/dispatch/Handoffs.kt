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

/** DiDi's scanner sends its bike and e-bike codes and short links, listed in [KnownLink], to the right service. */
internal object Didi {
    const val PACKAGE = OpenInApps.DIDI
    private const val SCANNER = "OneTravel://router/page/open_scan"

    fun openScanner(activity: Activity, text: String) = openScannerWithModule(activity, PACKAGE, SCANNER, text)
}

/** Meituan's scan screen opens its own links, listed in [KnownLink], in its own pages. */
internal object Meituan {
    const val PACKAGE = OpenInApps.MEITUAN
    private const val SCANNER = "imeituan://www.meituan.com/scanQRCode"

    fun openScanner(activity: Activity, text: String) = openScannerWithModule(activity, PACKAGE, SCANNER, text)
}

/** Xiaomi apps reached through private intents that no generic link resolution can find, for the links [KnownLink] lists. */
internal object Xiaomi {
    fun claim(activity: Activity, url: String): Boolean = when (KnownLink.of(url)) {
        KnownLink.XIAOMI_LOGIN -> Launch.start(
            activity,
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setClassName("com.xiaomi.account", "com.xiaomi.account.ui.AccountWebActivity"),
        )
        KnownLink.MI_HOME_DEVICE -> Launch.start(activity, miHome(url))
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
