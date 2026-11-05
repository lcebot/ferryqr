package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import android.net.Uri
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.ContactPayload
import io.github.lcebot.ferryqr.content.EsimPayload
import io.github.lcebot.ferryqr.content.EventPayload
import io.github.lcebot.ferryqr.content.MiniProgramPayload
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.content.SchemePayload
import io.github.lcebot.ferryqr.content.WebPayload
import io.github.lcebot.ferryqr.content.WifiPayload
import io.github.lcebot.wxcode.WxText

/**
 * Names for content in words, where the content alone says what it is: the links of [KnownLink],
 * with a vehicle's number where the link carries one, and the parts of the basic kinds that name
 * them. Content these do not cover has no such name, and is shown as it is.
 */
internal object Titles {

    /** The name of the link or app URI [uri]; null for one FerryQR does not know. */
    fun link(context: Context, uri: String): String? {
        val link = KnownLink.of(uri) ?: return null
        if (link.numbering == Numbering.NONE) return context.getString(link.title)
        return context.getString(link.title, link.number(uri) ?: return null)
    }

    /**
     * The name [payload] carries, or null when it carries none and is best shown as it is: a link
     * FerryQR does not know, plain text, a passkey or a Wi-Fi Easy Connect code.
     */
    fun of(context: Context, payload: Payload): String? = when (payload) {
        is WebPayload -> link(context, payload.url)
        is SchemePayload -> link(context, payload.raw) ?: schemeTarget(payload)
        is WifiPayload -> payload.ssid.ifBlank { context.getString(R.string.wifi_hidden) }
        is ContactPayload -> with(payload.contact) {
            listOfNotNull(name, organization, phones.firstOrNull(), emails.firstOrNull()).firstOrNull { it.isNotBlank() }
        }
        is EventPayload -> payload.event.title?.takeIf { it.isNotBlank() }
        is EsimPayload -> esimServer(payload.raw)
        // The payload means something only as WeChat reads it.
        is MiniProgramPayload -> WxText.forWeChat(payload.raw)
        else -> null
    }

    /**
     * Who a message, call or email goes to, or the place a location names, which is what such a
     * link is about. A location without a name is its coordinates.
     */
    private fun schemeTarget(payload: SchemePayload): String? {
        val rest = payload.uri.substringAfter(':')
        val target = when (payload.scheme.lowercase()) {
            "mailto", "smsto", "sms", "tel" -> rest.substringBefore('?').substringBefore(':')
            "geo" -> rest.substringAfter("q=", "").substringBefore('&').ifEmpty { rest.substringBefore('?') }
            else -> return null
        }
        return Uri.decode(target).trim().takeIf { it.isNotEmpty() }
    }

    /** The SM-DP+ server an eSIM activation code points to, which names its carrier's provider. */
    private fun esimServer(raw: String): String? =
        raw.removePrefix("LPA:").split('$').getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
}
