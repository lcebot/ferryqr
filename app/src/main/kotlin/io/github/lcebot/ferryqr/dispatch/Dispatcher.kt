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
import io.github.lcebot.ferryqr.helper.WeChatHelper

object Dispatcher {

    /**
     * Hands the payload straight to the app that owns it, without showing anything. Returns false
     * when nothing claimed it, in which case the caller shows a result card instead.
     */
    fun autoLaunch(activity: Activity, payload: Payload, origin: Origin): Boolean = when (payload) {
        is WebPayload -> when {
            Alipay.claims(payload.url) -> Alipay.handoff(activity, payload.url)
            // A WeChat code in an image would leave the user facing WeChat's camera with nothing to
            // scan, so without the helper it gets a card that says how to pick it from the album.
            WeChat.claims(payload.url) -> wechatSees(origin) && WeChat.openScanner(activity, payload.raw)
            else -> Xiaomi.claim(activity, payload.url) || Launch.nonBrowser(activity, payload.url)
        }
        is SchemePayload -> Launch.scheme(activity, payload.uri, payload.scheme) ||
            (wechatSees(origin) && WeChat.claims(payload.raw) && WeChat.openScanner(activity, payload.raw))
        is FidoPayload -> Launch.fido(activity, payload.raw)
        is EsimPayload -> Launch.esim(activity, payload.raw)
        is DppPayload -> Launch.dpp(activity, payload.raw)
        // Only the helper lets WeChat take the code; without it the card explains what to do.
        is MiniProgramPayload -> WeChatHelper.ready.value &&
            MiniCodeArt.draw(payload.raw)?.let { WeChat.openScanner(activity, it) } == true
        else -> false
    }

    /** Whether WeChat's scanner can see the code: through the helper, or because the camera still can. */
    private fun wechatSees(origin: Origin) = WeChatHelper.ready.value || origin == Origin.CAMERA
}
