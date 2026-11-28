package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.BoardingPassPayload
import io.github.lcebot.ferryqr.content.ContactPayload
import io.github.lcebot.ferryqr.content.DppPayload
import io.github.lcebot.ferryqr.content.EsimPayload
import io.github.lcebot.ferryqr.content.EventPayload
import io.github.lcebot.ferryqr.content.FidoPayload
import io.github.lcebot.ferryqr.content.MiniProgramPayload
import io.github.lcebot.ferryqr.content.OtpPayload
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.content.SchemePayload
import io.github.lcebot.ferryqr.content.TextPayload
import io.github.lcebot.ferryqr.content.WebPayload
import io.github.lcebot.ferryqr.content.WifiPayload
import io.github.lcebot.ferryqr.openin.OpenInApp
import io.github.lcebot.ferryqr.openin.OpenInApps

/**
 * A way a scanned code reaches another app. An [ActiveFerry] is one FerryQR made and tried by hand
 * for an app, listed in [ActiveFerries] and on the ferry page. A [PassiveFerry] is one the app
 * registers itself, which the system finds for FerryQR. Result cards, the create page and automatic
 * opening all follow [Ferries.rank], so a code goes the same way everywhere.
 */
internal sealed interface Ferry {
    val pkg: String

    /**
     * Whether handing content over opens it, rather than only the app's scanner, by the apps that
     * take content through the module in [env].
     */
    fun isReady(env: FerryEnv): Boolean

    /** Whether the app's scanner can read a picture from the gallery, which without the module is the way in. */
    val album: Boolean

    fun installed(env: FerryEnv): Boolean = env.installed(pkg)

    /**
     * What the app is called on buttons and in messages. Naming an app the system finds asks the
     * package manager, so it is looked up off the main thread.
     */
    fun label(context: Context): String

    /** Hands [text] over; false when the app could not be opened. */
    fun open(activity: Activity, text: String): Boolean
}

/**
 * How an app takes content, given whether the module runs in it and where the content came from.
 * Result cards name it on the app's action and in a note, a scan launches by it, and the create page
 * draws the app's button by it.
 */
internal sealed interface Reach {
    /** The app opens the content itself. */
    data object Opens : Reach

    /** Only the app's scanner opens, which can still see the code in front of the camera. */
    data object Scanner : Reach

    /** Only the app's scanner opens, whose album can pick the image the code is in. */
    data object Album : Reach

    /** No code is left for the app's scanner, so only the module takes the content there. */
    data object NeedsModule : Reach

    data object NotInstalled : Reach
}

/** How this app takes content from [origin], with the readiness [env] holds. */
internal fun Ferry.reach(env: FerryEnv, origin: Origin): Reach = when {
    !installed(env) -> Reach.NotInstalled
    isReady(env) -> Reach.Opens
    origin == Origin.CAMERA -> Reach.Scanner
    origin == Origin.IMAGE && album -> Reach.Album
    else -> Reach.NeedsModule
}

/** How an [ActiveFerry] reaches its app, which decides the tab the ferry page lists it under. */
internal sealed interface Route {
    /** Through the LSPosed module, in the way [app] describes. */
    class Module(val app: OpenInApp) : Route

    /** Through an entry the app takes content from other apps by, official or private, with no module. */
    data object Entry : Route
}

/**
 * An app FerryQR reaches its own way. A [general] app opens any link or text, and is offered for
 * anything. Any other app is only offered for its own codes, since it is no general scanner and
 * would show an error or a blank page for anything else. Through the module, an app takes content
 * only once the module runs in it; without it, handing over just opens its scanner. [summary] says
 * which codes an app reached through an entry opens, for the ferry page.
 *
 * An [exclusive] app's codes open in that app alone, so a card says when it is not installed. Most
 * apps' codes are not: DiDi and Meituan, for one, made theirs work in the WeChat and Alipay
 * scanners too. Xiaomi's sign-in and device codes have no such way, nor do those of any app that
 * leaves the big scanners out.
 */
internal class ActiveFerry(
    override val pkg: String,
    @StringRes val name: Int,
    val route: Route,
    val general: Boolean,
    override val album: Boolean,
    @StringRes val summary: Int? = null,
    val exclusive: Boolean = false,
    private val handoff: (Activity, String) -> Boolean,
) : Ferry {
    override fun isReady(env: FerryEnv): Boolean = route !is Route.Module || pkg in env.ready

    override fun label(context: Context): String = context.getString(name)

    override fun open(activity: Activity, text: String): Boolean = handoff(activity, text)
}

/** An app the system opens a code in by itself, through the [intent] the app registered for. */
internal class PassiveFerry(override val pkg: String, private val intent: Intent) : Ferry {
    override fun isReady(env: FerryEnv): Boolean = true
    override val album: Boolean get() = false

    override fun label(context: Context): String = InstalledApps.labelOrPackage(context, pkg)

    override fun open(activity: Activity, text: String): Boolean = Launch.start(activity, Intent(intent).setPackage(pkg))
}

internal object ActiveFerries {
    val ALIPAY = ActiveFerry(
        Alipay.PACKAGE, R.string.app_alipay, Route.Entry, general = true, album = false,
        summary = R.string.entry_alipay, handoff = Alipay::handoff,
    )
    val WECHAT = ActiveFerry(
        WeChat.PACKAGE, R.string.app_wechat, Route.Module(OpenInApps.WECHAT_APP), general = true, album = true,
        handoff = { activity, text -> WeChat.openScanner(activity, text) },
    )
    val DIDI = ActiveFerry(
        Didi.PACKAGE, R.string.app_didi, Route.Module(OpenInApps.DIDI_APP), general = false, album = false,
        handoff = Didi::openScanner,
    )
    val MEITUAN = ActiveFerry(
        Meituan.PACKAGE, R.string.app_meituan, Route.Module(OpenInApps.MEITUAN_APP), general = false, album = false,
        handoff = Meituan::openScanner,
    )
    val XIAOMI_ACCOUNT = ActiveFerry(
        Xiaomi.ACCOUNT, R.string.app_xiaomi_account, Route.Entry, general = false, album = false,
        summary = R.string.entry_xiaomi_account, exclusive = true, handoff = Xiaomi::signIn,
    )
    val MI_HOME = ActiveFerry(
        Xiaomi.MI_HOME, R.string.app_mi_home, Route.Entry, general = false, album = false,
        summary = R.string.entry_mi_home, exclusive = true, handoff = Xiaomi::addDevice,
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
        is FidoPayload, is BoardingPassPayload, is OtpPayload, is EsimPayload, is DppPayload, is WifiPayload,
        is ContactPayload, is EventPayload, is TextPayload -> null
    }
}

internal object PassiveFerries {
    /**
     * The app the system opens [payload] in without asking, such as one with a verified App Link
     * for a web link, or the app that registers a link's scheme. Null when nothing registers it, or
     * when several apps do and the system would ask which to use.
     */
    fun of(env: FerryEnv, payload: Payload): PassiveFerry? {
        val intent = when (payload) {
            is WebPayload -> Intents.link(payload.url)
            is SchemePayload -> Intents.scheme(payload.uri, payload.scheme)
            is OtpPayload -> Intents.link(payload.raw)
            is FidoPayload, is BoardingPassPayload, is EsimPayload, is DppPayload, is WifiPayload, is ContactPayload,
            is EventPayload, is TextPayload, is MiniProgramPayload -> null
        } ?: return null
        return env.handler(intent)?.let { PassiveFerry(it, intent) }
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
     * way opens any link. Readiness is the one [env] holds. Ranking asks the package manager, so it is
     * asked through [FerryEnv.rank], which works it out once for a card or a plan.
     */
    fun rank(env: FerryEnv, payload: Payload): List<Ferry> {
        val owner = ActiveFerries.claimant(payload)
        val ownerReady = owner?.isReady(env) == true
        val passive = if (payload is WebPayload && owner?.general == true) null else PassiveFerries.of(env, payload)
        val found: Ferry? = passive?.let { p ->
            ActiveFerries.of(p.pkg)?.takeIf { payload is WebPayload && it.general && it.isReady(env) } ?: p
        }
        return listOfNotNull(
            owner?.takeIf { ownerReady },
            found?.takeIf { owner == null || it.pkg != owner.pkg || !ownerReady },
            owner?.takeIf { !ownerReady && it.pkg != found?.pkg },
        )
    }

    /**
     * The general apps offered for a web link besides the [ranked] ones, since they open any link.
     * Other content goes only to its own apps, which the general ones would show as plain text or
     * not at all.
     */
    fun general(payload: Payload, ranked: List<Ferry>): List<ActiveFerry> =
        if (payload !is WebPayload) {
            emptyList()
        } else {
            ActiveFerries.all.filter { ferry -> ferry.general && ranked.none { it.pkg == ferry.pkg } }
        }

    /** Every app offered for [payload]: the [rank]ed ones, then the [general] ones. */
    fun offered(env: FerryEnv, payload: Payload): List<Ferry> =
        env.rank(payload).let { it + general(payload, it) }
}
