package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.graphics.Bitmap
import android.widget.Toast
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where one content goes from one origin, worked out from one [FerryEnv]: the apps it goes to, in the
 * order [Ferries.rank] gives, each with how it takes the content; the [apps] that take a passkey
 * request or an eSIM code; the [picture] a mini program code is handed to WeChat as; and whether
 * [Dispatcher.autoLaunch] tries to hand the content over before its card shows, as [launches] says.
 * Working these out asks the package manager and draws, so [Dispatcher.plan] runs off the main
 * thread, and only starting an app is left for the main thread. The card for the same content is
 * built from the same routing, so it offers the apps the content was routed to and says what
 * happened when one of them did not take it.
 */
class Routing internal constructor(
    internal val payload: Payload,
    internal val ranked: List<Pair<Ferry, Reach>>,
    internal val apps: List<AppCandidate>,
    internal val picture: Bitmap?,
    /**
     * Whether [Dispatcher.autoLaunch] tries the content's way to an app: for content the scanner read
     * and hands on, unless it is a passkey request or an eSIM code that not exactly one app takes,
     * which only the card offers.
     */
    internal val launches: Boolean,
)

object Dispatcher {

    /**
     * The routing of [source], worked out from [env]. The picture is the drawing the card shows too.
     * Scanned content is untrusted, and content that planning fails on, however malformed, is ranked
     * for no app: it still goes the ways that need no ranking, through the system, and is otherwise
     * left to its card.
     */
    fun plan(env: FerryEnv, source: CardSource.Read): Routing {
        val payload = source.content.payload
        return try {
            val apps = when (payload) {
                is FidoPayload -> env.apps(Intents.fido(payload.raw))
                is EsimPayload -> env.apps(Intents.esim(payload.raw))
                is WebPayload, is SchemePayload, is BoardingPassPayload, is OtpPayload, is DppPayload, is WifiPayload,
                is ContactPayload, is EventPayload, is TextPayload, is MiniProgramPayload -> emptyList()
            }
            // Among several apps the card lets the user pick, and with none there is nothing to try.
            val launches = source.routed && (payload !is FidoPayload && payload !is EsimPayload || apps.size == 1)
            Routing(
                payload = payload,
                ranked = env.rank(payload).map { it to it.reach(env, source.origin) },
                apps = apps,
                picture = (payload as? MiniProgramPayload)?.let { Cards.miniCodeDrawing(it.raw) },
                launches = launches,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // With no app found for a passkey request or an eSIM code, only the card offers it.
            val launches = source.routed && payload !is FidoPayload && payload !is EsimPayload
            Routing(payload, ranked = emptyList(), apps = emptyList(), picture = null, launches = launches)
        }
    }

    /**
     * Hands the content straight to the app that owns it, without showing anything, as [routing]
     * has worked out. Returns false when nothing claimed it, in which case the caller shows a result
     * card instead.
     */
    fun autoLaunch(activity: Activity, routing: Routing): Boolean = routing.launches && when (val payload = routing.payload) {
        // A link that several apps besides the browsers open goes to the system, which asks which
        // to use, unless it belongs to a general app.
        is WebPayload -> ferry(activity, payload, routing) {
            ActiveFerries.claimant(payload)?.general != true && Launch.nonBrowser(it, payload.url)
        }
        // The system also takes an app link that several apps open, or an intent URI's fallback page.
        is SchemePayload -> ferry(activity, payload, routing) { Launch.scheme(it, payload.uri, payload.scheme) }
        // Only the one app that takes it gets it, which is the only case [plan] launches.
        is FidoPayload, is EsimPayload -> routing.apps.singleOrNull()?.let { Launch.start(activity, it.intent) } == true
        is DppPayload -> Launch.dpp(activity, payload.raw)
        // Like any app link, it goes to the authenticator that registers the scheme.
        is OtpPayload -> Launch.scheme(activity, payload.raw, "otpauth")
        // WeChat is the one app a mini program code goes to.
        is MiniProgramPayload -> {
            val picture = routing.picture
            val wechat = routing.ranked.firstOrNull { (ferry, _) -> ferry == ActiveFerries.WECHAT }
            picture != null && wechat != null &&
                toOwner(activity, wechat.second) { WeChat.openMiniProgram(it, payload.raw, picture) }
        }
        is BoardingPassPayload, is WifiPayload, is ContactPayload, is EventPayload, is TextPayload -> false
    }

    /**
     * Hands [payload] to the first of the [routing]'s apps, in the order [Ferries.rank] gives, that
     * opens it. Only then does [system] get it, for the cases no one app stands for, and last an app
     * that would only open its scanner, which [toOwner] limits to a code the camera still sees.
     */
    private fun ferry(activity: Activity, payload: Payload, routing: Routing, system: (Activity) -> Boolean): Boolean =
        routing.ranked.any { (ferry, reach) -> reach == Reach.Opens && ferry.open(activity, payload.raw) } || system(activity) ||
            routing.ranked.any { (ferry, reach) -> reach != Reach.Opens && toOwner(activity, reach) { ferry.open(it, payload.raw) } }

    /**
     * Hands a code through [open] to the app it belongs to, as from a real scan, by how that app
     * takes it. Without the module in that app, FerryQR still knows where the code belongs but cannot
     * take it there, so the card shows it with whatever can be done instead. One case still goes
     * straight on: a code the camera still sees. The app's scanner opens for a second scan, which a
     * toast asks for, since the scanner alone would not say why it is there. A handoff through the
     * module announces itself once the app takes it.
     */
    private fun toOwner(activity: Activity, reach: Reach, open: (Activity) -> Boolean): Boolean = when (reach) {
        Reach.Opens -> open(activity)
        Reach.Scanner -> open(activity).also { opened ->
            // The application context, since the scanner closes as the other app opens.
            if (opened) Toast.makeText(activity.applicationContext, R.string.scan_again_prompt, Toast.LENGTH_LONG).show()
        }
        Reach.Album, Reach.NeedsModule, Reach.NotInstalled -> false
    }
}
