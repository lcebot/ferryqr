package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
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
        is WebPayload -> Targets.claimant(payload.url)?.let { toOwner(activity, it, payload.raw, origin) }
            ?: (Xiaomi.claim(activity, payload.url) || Launch.nonBrowser(activity, payload.url))
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
     * helper that only opens the app's scanner, which helps while the code is still in front of the
     * camera; a code in an image or in history gets a card that says what to do instead.
     */
    private fun toOwner(activity: Activity, owner: Target, text: String, origin: Origin): Boolean =
        (owner.ready || origin == Origin.CAMERA) && owner.open(activity, text)
}
