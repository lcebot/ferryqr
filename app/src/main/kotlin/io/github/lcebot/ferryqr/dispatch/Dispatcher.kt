package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.content.Context
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
import io.github.lcebot.ferryqr.generate.MiniCodeArt

/**
 * What [Dispatcher.autoLaunch] works from: the apps the content goes to, in the order [Ferries.rank]
 * gives, and the picture a mini program code is handed over as. Working them out asks the package
 * manager and draws, so [Dispatcher.plan] runs off the main thread, and only starting an app is left
 * for the main thread. [ready] holds the packages of the apps that took content through the module
 * when the plan was made. The launch goes by it as well, so a code goes the way its card was built for.
 */
class LaunchPlan internal constructor(
    internal val ranked: List<Ferry>,
    internal val picture: Bitmap?,
    internal val ready: Set<String>,
)

object Dispatcher {

    /** [ready] holds the packages of the apps that take content through the module. */
    fun plan(context: Context, payload: Payload, ready: Set<String>): LaunchPlan = LaunchPlan(
        ranked = if (payload is WebPayload || payload is SchemePayload) Ferries.rank(context, payload, ready) else emptyList(),
        picture = (payload as? MiniProgramPayload)?.let { MiniCodeArt.draw(it.raw) },
        ready = ready,
    )

    /**
     * Hands the payload straight to the app that owns it, without showing anything, as [plan] has
     * worked out. Returns false when nothing claimed it, in which case the caller shows a result
     * card instead.
     */
    fun autoLaunch(activity: Activity, payload: Payload, origin: Origin, plan: LaunchPlan): Boolean = when (payload) {
        // A link that several apps besides the browsers open goes to the system, which asks which
        // to use, unless it belongs to a general app.
        is WebPayload -> ferry(activity, payload, origin, plan) {
            ActiveFerries.claimant(payload)?.general != true && Launch.nonBrowser(it, payload.url)
        }
        // The system also takes an app link that several apps open, or an intent URI's fallback page.
        is SchemePayload -> ferry(activity, payload, origin, plan) { Launch.scheme(it, payload.uri, payload.scheme) }
        is FidoPayload -> Launch.fido(activity, payload.raw)
        is EsimPayload -> Launch.esim(activity, payload.raw)
        is DppPayload -> Launch.dpp(activity, payload.raw)
        // Like any app link, it goes to the authenticator that registers the scheme.
        is OtpPayload -> Launch.scheme(activity, payload.raw, "otpauth")
        is MiniProgramPayload -> plan.picture?.let { picture ->
            toOwner(activity, ActiveFerries.WECHAT, origin, plan.ready) { WeChat.openMiniProgram(it, payload.raw, picture) }
        } == true
        else -> false
    }

    /**
     * Hands [payload] to the first of the [plan]'s apps, in the order [Ferries.rank] gives, that
     * opens it. Only then does [system] get it, for the cases no one app stands for, and last an app
     * that would only open its scanner, which [toOwner] limits to a code the camera still sees.
     */
    private fun ferry(activity: Activity, payload: Payload, origin: Origin, plan: LaunchPlan, system: (Activity) -> Boolean): Boolean {
        val ready = plan.ready
        return plan.ranked.any { it.isReady(ready) && it.open(activity, payload.raw) } || system(activity) ||
            plan.ranked.any { ferry -> !ferry.isReady(ready) && toOwner(activity, ferry, origin, ready) { ferry.open(it, payload.raw) } }
    }

    /**
     * Hands a code to [owner], the app it belongs to, through [open], as from a real scan. Without
     * the module in that app, FerryQR still knows where the code belongs but cannot take it there,
     * so the card shows it with whatever can be done instead. One case still goes straight on: a
     * code the camera still sees. The app's scanner opens for a second scan, which a toast asks
     * for, since the scanner alone would not say why it is there. A handoff through the module
     * announces itself once the app takes it. [ready] holds the packages of the apps that take
     * content through the module.
     */
    private fun toOwner(activity: Activity, owner: Ferry, origin: Origin, ready: Set<String>, open: (Activity) -> Boolean): Boolean {
        if (owner.isReady(ready)) return open(activity)
        if (origin != Origin.CAMERA || !open(activity)) return false
        val app = activity.applicationContext
        // The application context, since the scanner closes as the other app opens.
        Toast.makeText(app, R.string.scan_again_prompt, Toast.LENGTH_LONG).show()
        return true
    }
}
