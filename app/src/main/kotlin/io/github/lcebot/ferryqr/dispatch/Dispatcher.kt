package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.widget.Toast
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.DppPayload
import io.github.lcebot.ferryqr.content.EsimPayload
import io.github.lcebot.ferryqr.content.FidoPayload
import io.github.lcebot.ferryqr.content.MiniProgramPayload
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.content.SchemePayload
import io.github.lcebot.ferryqr.content.WebPayload
import io.github.lcebot.ferryqr.generate.MiniCodeArt

object Dispatcher {

    /**
     * Hands the payload straight to the app that owns it, without showing anything. Returns false
     * when nothing claimed it, in which case the caller shows a result card instead.
     */
    fun autoLaunch(activity: Activity, payload: Payload, origin: Origin): Boolean = when (payload) {
        is WebPayload -> when (val owner = Targets.claimant(payload.url)) {
            null -> Xiaomi.claim(activity, payload.url) || Launch.nonBrowser(activity, payload.url)
            // An app that is no general scanner may declare App Links for some of its own links,
            // which reach the right page in it directly, without its scanner.
            else -> (!owner.general && Launch.nonBrowser(activity, payload.url)) ||
                toOwner(activity, owner, payload.raw, origin)
        }
        is SchemePayload -> Launch.scheme(activity, payload.uri, payload.scheme) ||
            Targets.claimant(payload.raw)?.let { toOwner(activity, it, payload.raw, origin) } == true
        is FidoPayload -> Launch.fido(activity, payload.raw)
        is EsimPayload -> Launch.esim(activity, payload.raw)
        is DppPayload -> Launch.dpp(activity, payload.raw)
        // Only the helper lets WeChat take the code; without it the card explains what to do.
        is MiniProgramPayload -> Targets.WECHAT.ready &&
            MiniCodeArt.draw(payload.raw)?.let { WeChat.openMiniProgram(activity, payload.raw, it) } == true
        else -> false
    }

    /**
     * Hands a code to the app it belongs to, exactly as printed, as from a real scan. Without the
     * helper in that app, FerryQR still knows where the code belongs but cannot take it there, the
     * same as with a mini program code, so the card shows it with whatever can be done instead.
     * One case still goes straight on: a code the camera still sees, for a general app such as
     * WeChat, whose codes nothing else opens. Its scanner opens for a second scan, which a toast
     * asks for, since the scanner alone would not say why it is there. A handoff through the helper
     * announces itself once the app takes it.
     */
    private fun toOwner(activity: Activity, owner: Target, text: String, origin: Origin): Boolean {
        if (owner.ready) return owner.open(activity, text)
        if (!owner.general || origin != Origin.CAMERA || !owner.open(activity, text)) return false
        val app = activity.applicationContext
        // The application context, since the scanner closes as the other app opens.
        Toast.makeText(app, R.string.scan_again_prompt, Toast.LENGTH_LONG).show()
        return true
    }
}
