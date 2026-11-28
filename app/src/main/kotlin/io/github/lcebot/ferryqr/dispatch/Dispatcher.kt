package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.graphics.Bitmap
import android.widget.Toast
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.DppPayload
import io.github.lcebot.ferryqr.content.EsimPayload
import io.github.lcebot.ferryqr.content.FidoPayload
import io.github.lcebot.ferryqr.content.MiniProgramPayload
import io.github.lcebot.ferryqr.content.OtpPayload
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.content.SchemePayload
import io.github.lcebot.ferryqr.content.WebPayload
import kotlin.coroutines.cancellation.CancellationException

/**
 * What [Dispatcher.autoLaunch] works from: the apps the content goes to, in the order [Ferries.rank]
 * gives, each with how it takes the content; how WeChat takes a mini program code and the picture
 * the code is handed over as; and the apps that take a passkey request or an eSIM code. Working them
 * out asks the package manager and draws, so [Dispatcher.plan] runs off the main thread, and only
 * starting an app is left for the main thread. The card for the same code is built from the same
 * [FerryEnv], so a code goes the way its card was built for, and the card takes the ranking and the
 * answers about the apps from the plan.
 */
class LaunchPlan internal constructor(
    internal val ranked: List<Pair<Ferry, Reach>>,
    internal val wechat: Reach?,
    internal val picture: Bitmap?,
    internal val apps: List<AppCandidate>,
)

object Dispatcher {

    /**
     * The plan for [payload] from [origin], worked out from [env]. The picture is the drawing the
     * card shows too. Scanned content is untrusted, and content that planning fails on, however
     * malformed, is planned for no app at all, which leaves it to the system or its card.
     */
    fun plan(env: FerryEnv, payload: Payload, origin: Origin): LaunchPlan = try {
        LaunchPlan(
            ranked = if (payload is WebPayload || payload is SchemePayload) {
                env.rank(payload).map { it to it.reach(env, origin) }
            } else {
                emptyList()
            },
            wechat = if (payload is MiniProgramPayload) ActiveFerries.WECHAT.reach(env, origin) else null,
            picture = (payload as? MiniProgramPayload)?.let { Cards.miniCodeDrawing(it.raw) },
            apps = when (payload) {
                is FidoPayload -> env.apps(Launch.fidoIntents(payload.raw))
                is EsimPayload -> env.apps(Launch.esimIntents(payload.raw))
                else -> emptyList()
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LaunchPlan(ranked = emptyList(), wechat = null, picture = null, apps = emptyList())
    }

    /**
     * Hands the payload straight to the app that owns it, without showing anything, as [plan] has
     * worked out. Returns false when nothing claimed it, in which case the caller shows a result
     * card instead.
     */
    fun autoLaunch(activity: Activity, payload: Payload, plan: LaunchPlan): Boolean = when (payload) {
        // A link that several apps besides the browsers open goes to the system, which asks which
        // to use, unless it belongs to a general app.
        is WebPayload -> ferry(activity, payload, plan) {
            ActiveFerries.claimant(payload)?.general != true && Launch.nonBrowser(it, payload.url)
        }
        // The system also takes an app link that several apps open, or an intent URI's fallback page.
        is SchemePayload -> ferry(activity, payload, plan) { Launch.scheme(it, payload.uri, payload.scheme) }
        // Only the one app that takes it gets it; among several, the card lets the user pick.
        is FidoPayload, is EsimPayload -> plan.apps.singleOrNull()?.let { Launch.start(activity, it.intent) } == true
        is DppPayload -> Launch.dpp(activity, payload.raw)
        // Like any app link, it goes to the authenticator that registers the scheme.
        is OtpPayload -> Launch.scheme(activity, payload.raw, "otpauth")
        is MiniProgramPayload -> {
            val picture = plan.picture
            val wechat = plan.wechat
            picture != null && wechat != null &&
                toOwner(activity, wechat) { WeChat.openMiniProgram(it, payload.raw, picture) }
        }
        else -> false
    }

    /**
     * Hands [payload] to the first of the [plan]'s apps, in the order [Ferries.rank] gives, that
     * opens it. Only then does [system] get it, for the cases no one app stands for, and last an app
     * that would only open its scanner, which [toOwner] limits to a code the camera still sees.
     */
    private fun ferry(activity: Activity, payload: Payload, plan: LaunchPlan, system: (Activity) -> Boolean): Boolean =
        plan.ranked.any { (ferry, reach) -> reach == Reach.Opens && ferry.open(activity, payload.raw) } || system(activity) ||
            plan.ranked.any { (ferry, reach) -> reach != Reach.Opens && toOwner(activity, reach) { ferry.open(it, payload.raw) } }

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
