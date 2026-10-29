package com.lcebot.ferryqr.dispatch

import android.app.Activity
import com.lcebot.ferryqr.content.DppPayload
import com.lcebot.ferryqr.content.EsimPayload
import com.lcebot.ferryqr.content.FidoPayload
import com.lcebot.ferryqr.content.Payload
import com.lcebot.ferryqr.content.SchemePayload
import com.lcebot.ferryqr.content.WebPayload

object Dispatcher {

    /**
     * Hands the payload straight to the app that owns it, without showing anything. Returns false
     * when nothing claimed it, in which case the caller shows a result card instead.
     */
    fun autoLaunch(activity: Activity, payload: Payload): Boolean = when (payload) {
        is WebPayload -> when {
            Alipay.claims(payload.url) -> Alipay.handoff(activity, payload.url)
            WeChat.claims(payload.url) -> WeChat.openScanner(activity, payload.raw)
            else -> Xiaomi.claim(activity, payload.url) || Launch.nonBrowser(activity, payload.url)
        }
        is SchemePayload -> Launch.scheme(activity, payload.uri, payload.scheme) ||
            (WeChat.claims(payload.raw) && WeChat.openScanner(activity, payload.raw))
        is FidoPayload -> Launch.fido(activity, payload.raw)
        is EsimPayload -> Launch.esim(activity, payload.raw)
        is DppPayload -> Launch.dpp(activity, payload.raw)
        else -> false
    }
}
