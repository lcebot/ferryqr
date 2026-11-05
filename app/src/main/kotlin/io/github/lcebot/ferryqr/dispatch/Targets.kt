package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.content.Context
import androidx.annotation.StringRes
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.MiniProgramPayload
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.content.SchemePayload
import io.github.lcebot.ferryqr.content.WebPayload
import io.github.lcebot.ferryqr.openin.OpenInModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    private val handoff: (Activity, String) -> Boolean,
) {
    /** Whether [text] is one of this app's own codes, which only its scanner opens properly. */
    fun claims(text: String) = KnownLink.of(text)?.owner == pkg

    /**
     * Whether handing content over opens it, rather than only the scanner, for screens that redraw
     * once the framework tells; [ready] is its value now.
     */
    val readyFlow: StateFlow<Boolean> by lazy { if (viaModule) OpenInModule.ready(pkg) else MutableStateFlow(true) }

    val ready: Boolean get() = readyFlow.value

    fun installed(context: Context) = Launch.isInstalled(context, pkg)

    /** Hands [text] over; false when the app could not be opened. */
    fun open(activity: Activity, text: String): Boolean = handoff(activity, text)
}

internal object Targets {
    val ALIPAY = Target(Alipay.PACKAGE, R.string.app_alipay, general = true, viaModule = false, album = false, Alipay::handoff)
    val WECHAT = Target(WeChat.PACKAGE, R.string.app_wechat, general = true, viaModule = true, album = true) { activity, text ->
        WeChat.openScanner(activity, text)
    }
    val DIDI = Target(Didi.PACKAGE, R.string.app_didi, general = false, viaModule = true, album = false, Didi::openScanner)
    val MEITUAN = Target(Meituan.PACKAGE, R.string.app_meituan, general = false, viaModule = true, album = false, Meituan::openScanner)

    /** The order apps are offered in, after the app whose code the content is. */
    val all = listOf(ALIPAY, WECHAT, DIDI, MEITUAN)

    fun of(pkg: String): Target? = all.firstOrNull { it.pkg == pkg }

    /** The app whose own code [text] is, if any. */
    fun claimant(text: String): Target? = KnownLink.of(text)?.owner?.let(::of)

    /**
     * The app whose own code [payload] is, if any. A link is judged as it opens, so one printed
     * without its scheme still belongs to its app; a mini program code always belongs to WeChat.
     */
    fun claimant(payload: Payload): Target? = when (payload) {
        is WebPayload -> claimant(payload.url)
        is SchemePayload -> claimant(payload.raw)
        is MiniProgramPayload -> WECHAT
        else -> null
    }

    /**
     * The apps offered for [payload], the one it belongs to first. A web link also goes to the
     * general apps, which open any link; other content goes only to its own app, since the general
     * apps would show it as plain text or not at all.
     */
    fun offeredFor(payload: Payload): List<Target> {
        val owner = claimant(payload)
        val general = if (payload is WebPayload) all.filter { it.general && it !== owner } else emptyList()
        return listOfNotNull(owner) + general
    }
}
