package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import android.net.Uri
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.BoardingPassPayload
import io.github.lcebot.ferryqr.content.ContactPayload
import io.github.lcebot.ferryqr.content.EsimPayload
import io.github.lcebot.ferryqr.content.EventPayload
import io.github.lcebot.ferryqr.content.FidoPayload
import io.github.lcebot.ferryqr.content.MiniProgramPayload
import io.github.lcebot.ferryqr.content.OtpPayload
import io.github.lcebot.ferryqr.content.PasskeyOperation
import io.github.lcebot.ferryqr.content.PasskeyQr
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
     * FerryQR does not know, plain text or a Wi-Fi Easy Connect code. A passkey request is named for
     * what it asks, since its content is only digits.
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
        is OtpPayload -> otpName(context, payload)
        is FidoPayload -> context.getString(
            when (PasskeyQr.operation(payload.raw)) {
                PasskeyOperation.SIGN_IN -> R.string.title_passkey_sign_in
                PasskeyOperation.CREATE -> R.string.title_passkey_create
                PasskeyOperation.PRESENT_CREDENTIAL -> R.string.title_credential_present
                PasskeyOperation.ADD_CREDENTIAL -> R.string.title_credential_add
                null -> R.string.title_passkey_request
            },
        )
        is BoardingPassPayload -> payload.pass.legs.firstOrNull()?.flight?.ifEmpty { null }
            ?.let { context.getString(R.string.title_boarding_pass, it) }
            ?: context.getString(R.string.kind_boarding_pass)
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

    /** The service and the account a two-factor key is for, as much of them as the link gives. */
    private fun otpName(context: Context, payload: OtpPayload): String {
        val issuer = payload.key.issuer
        val account = payload.key.account
        return when {
            issuer != null && account != null -> context.getString(R.string.title_otp, issuer, account)
            else -> issuer ?: account ?: context.getString(R.string.kind_otp)
        }
    }

    /** The SM-DP+ server an eSIM activation code points to, which names its carrier's provider. */
    private fun esimServer(raw: String): String? =
        raw.removePrefix("LPA:").split('$').getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
}
