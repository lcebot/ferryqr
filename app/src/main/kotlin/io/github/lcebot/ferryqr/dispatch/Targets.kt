package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.content.Context
import androidx.annotation.StringRes
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.openin.OpenInModule

/**
 * An app content can be handed to. Result cards, the create page and automatic opening all ask
 * the same [Targets], so an app is offered for the same content everywhere.
 *
 * A [general] app opens any link or text, and is offered for anything. Any other app is only
 * offered for its own codes, since it is no general scanner and would show an error or a blank
 * page for anything else. An app reached [viaModule] takes content only with the LSPosed module;
 * without it, handing over just opens its scanner.
 */
internal class Target(
    val pkg: String,
    @StringRes val name: Int,
    val general: Boolean,
    val viaModule: Boolean,
    /** Whether its scanner can read a picture from the gallery, which without the module is the way in. */
    val album: Boolean,
    private val codes: (String) -> Boolean,
    private val handoff: (Activity, String) -> Boolean,
) {
    /** Whether [text] is one of this app's own codes, which only its scanner opens properly. */
    fun claims(text: String) = codes(text)

    /** Whether handing content over opens it, rather than only the scanner. */
    val ready: Boolean get() = !viaModule || OpenInModule.ready(pkg).value

    fun installed(context: Context) = Launch.isInstalled(context, pkg)

    /** Hands [text] over; false when the app could not be opened. */
    fun open(activity: Activity, text: String): Boolean = handoff(activity, text)
}

internal object Targets {
    val ALIPAY = Target(
        Alipay.PACKAGE, R.string.app_alipay, general = true, viaModule = false, album = false,
        codes = Alipay::claims, handoff = Alipay::handoff,
    )
    val WECHAT = Target(
        WeChat.PACKAGE, R.string.app_wechat, general = true, viaModule = true, album = true,
        codes = WeChat::claims, handoff = { activity, text -> WeChat.openScanner(activity, text) },
    )
    val DIDI = Target(
        Didi.PACKAGE, R.string.app_didi, general = false, viaModule = true, album = false,
        codes = Didi::claims, handoff = Didi::openScanner,
    )
    val MEITUAN = Target(
        Meituan.PACKAGE, R.string.app_meituan, general = false, viaModule = true, album = false,
        codes = Meituan::claims, handoff = Meituan::openScanner,
    )

    /** The order apps are offered in, after the app whose code the content is. */
    val all = listOf(ALIPAY, WECHAT, DIDI, MEITUAN)

    fun of(pkg: String): Target? = all.firstOrNull { it.pkg == pkg }

    /** The app whose own code [text] is, if any. */
    fun claimant(text: String): Target? = all.firstOrNull { it.claims(text) }

    /** The apps to offer for [text]: the one it belongs to first, then the general ones. */
    fun forText(text: String): List<Target> {
        val claimant = claimant(text)
        return listOfNotNull(claimant) + all.filter { it.general && it !== claimant }
    }
}
