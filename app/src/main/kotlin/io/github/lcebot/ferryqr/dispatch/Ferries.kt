package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.MiniProgramPayload
import io.github.lcebot.ferryqr.content.OtpPayload
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.content.SchemePayload
import io.github.lcebot.ferryqr.content.WebPayload
import io.github.lcebot.ferryqr.openin.OpenInApps
import io.github.lcebot.ferryqr.openin.OpenInModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A way a scanned code reaches another app. An [ActiveFerry] is one FerryQR made and tried by hand
 * for an app, listed in [ActiveFerries] and on the ferry page. A [PassiveFerry] is one the app
 * registers itself, which the system finds for FerryQR. Result cards, the create page and automatic
 * opening all follow [Ferries.rank], so a code goes the same way everywhere.
 */
internal sealed interface Ferry {
    val pkg: String

    /** Whether handing content over opens it, rather than only the app's scanner. */
    val ready: Boolean

    /** Whether the app's scanner can read a picture from the gallery, which without the module is the way in. */
    val album: Boolean

    fun installed(context: Context) = Launch.isInstalled(context, pkg)

    /** What the app is called on buttons and in messages. */
    fun label(context: Context): String

    /** Hands [text] over; false when the app could not be opened. */
    fun open(activity: Activity, text: String): Boolean
}

/** How an [ActiveFerry] reaches its app, which decides the tab the ferry page lists it under. */
internal enum class Route {
    /** Through the LSPosed module, as [OpenInApps] describes for the app. */
    MODULE,

    /** Through an entry the app takes content from other apps by, official or private, with no module. */
    ENTRY,
}

/**
 * An app FerryQR reaches its own way. A [general] app opens any link or text, and is offered for
 * anything. Any other app is only offered for its own codes, since it is no general scanner and
 * would show an error or a blank page for anything else. Through the module, an app takes content
 * only once the module runs in it; without it, handing over just opens its scanner. [summary] says
 * which codes an app reached through an entry opens, for the ferry page.
 */
internal class ActiveFerry(
    override val pkg: String,
    @StringRes val name: Int,
    val route: Route,
    val general: Boolean,
    override val album: Boolean,
    @StringRes val summary: Int? = null,
    private val handoff: (Activity, String) -> Boolean,
) : Ferry {
    /** Whether [text] is one of this app's own codes, which only its scanner opens properly. */
    fun claims(text: String) = KnownLink.of(text)?.owner == pkg

    /** [ready] for screens that redraw once the framework reports the module. */
    val readyFlow: StateFlow<Boolean> by lazy { if (route == Route.MODULE) OpenInModule.ready(pkg) else MutableStateFlow(true) }

    override val ready: Boolean get() = readyFlow.value

    override fun label(context: Context): String = context.getString(name)

    override fun open(activity: Activity, text: String): Boolean = handoff(activity, text)
}

/** An app the system opens a code in by itself, through the [intent] the app registered for. */
internal class PassiveFerry(override val pkg: String, private val intent: Intent) : Ferry {
    override val ready: Boolean get() = true
    override val album: Boolean get() = false

    override fun label(context: Context): String = Launch.appLabel(context, pkg) ?: pkg

    override fun open(activity: Activity, text: String): Boolean = Launch.start(activity, Intent(intent).setPackage(pkg))
}

internal object ActiveFerries {
    val ALIPAY = ActiveFerry(
        Alipay.PACKAGE, R.string.app_alipay, Route.ENTRY, general = true, album = false, R.string.entry_alipay, Alipay::handoff,
    )
    val WECHAT = ActiveFerry(WeChat.PACKAGE, R.string.app_wechat, Route.MODULE, general = true, album = true) { activity, text ->
        WeChat.openScanner(activity, text)
    }
    val DIDI = ActiveFerry(Didi.PACKAGE, R.string.app_didi, Route.MODULE, general = false, album = false, handoff = Didi::openScanner)
    val MEITUAN = ActiveFerry(
        Meituan.PACKAGE, R.string.app_meituan, Route.MODULE, general = false, album = false, handoff = Meituan::openScanner,
    )
    val XIAOMI_ACCOUNT = ActiveFerry(
        Xiaomi.ACCOUNT, R.string.app_xiaomi_account, Route.ENTRY, general = false, album = false,
        R.string.entry_xiaomi_account, Xiaomi::signIn,
    )
    val MI_HOME = ActiveFerry(
        Xiaomi.MI_HOME, R.string.app_mi_home, Route.ENTRY, general = false, album = false,
        R.string.entry_mi_home, Xiaomi::addDevice,
    )

    /** The order apps are offered and listed in, after the app whose code the content is. */
    val all = listOf(ALIPAY, WECHAT, DIDI, MEITUAN, XIAOMI_ACCOUNT, MI_HOME)

    fun of(pkg: String): ActiveFerry? = all.firstOrNull { it.pkg == pkg }

    /** The app whose own code [text] is, if any. */
    fun claimant(text: String): ActiveFerry? = KnownLink.of(text)?.owner?.let(::of)

    /**
     * The app whose own code [payload] is, if any. A link is judged as it opens, so one printed
     * without its scheme still belongs to its app; a mini program code always belongs to WeChat.
     */
    fun claimant(payload: Payload): ActiveFerry? = when (payload) {
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
    fun offeredFor(payload: Payload): List<ActiveFerry> {
        val owner = claimant(payload)
        val general = if (payload is WebPayload) all.filter { it.general && it !== owner } else emptyList()
        return listOfNotNull(owner) + general
    }
}

internal object PassiveFerries {
    /**
     * The app the system opens [payload] in without asking, such as one with a verified App Link
     * for a web link, or the app that registers a link's scheme. Null when nothing registers it, or
     * when several apps do and the system would ask which to use.
     */
    fun of(context: Context, payload: Payload): PassiveFerry? {
        val intent = when (payload) {
            is WebPayload -> Launch.linkIntent(payload.url)
            is SchemePayload -> Launch.schemeIntent(payload.uri, payload.scheme)
            is OtpPayload -> Launch.linkIntent(payload.raw)
            else -> null
        } ?: return null
        return Launch.handler(context, intent)?.let { PassiveFerry(it, intent) }
    }
}

internal object Ferries {
    /**
     * The apps [payload] goes to, best first. Each active ferry was tried by hand, so the app the
     * content belongs to leads whenever its way takes the content there. The app the system opens
     * the content in follows, and takes the place of the same app's way that would only open its
     * scanner, which comes last.
     *
     * A general app's links are never left to the system: one on another app's domain, such as
     * WeChat's sign-in on a Meituan page, still belongs to it. A link that is no app's own but goes
     * to a general app by the system's choice goes that app's own way while it is ready, since that
     * way opens any link.
     */
    fun rank(context: Context, payload: Payload): List<Ferry> {
        val owner = ActiveFerries.claimant(payload)
        val passive = if (payload is WebPayload && owner?.general == true) null else PassiveFerries.of(context, payload)
        val found: Ferry? = passive?.let { p ->
            ActiveFerries.of(p.pkg)?.takeIf { payload is WebPayload && it.general && it.ready } ?: p
        }
        return listOfNotNull(
            owner?.takeIf { it.ready },
            found?.takeIf { owner == null || it.pkg != owner.pkg || !owner.ready },
            owner?.takeIf { !it.ready && it.pkg != found?.pkg },
        )
    }
}
