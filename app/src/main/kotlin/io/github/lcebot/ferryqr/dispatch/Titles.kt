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
 * The name content carries, [name], or null when it carries none. [distinct] is whether that name
 * tells this content apart from other content of its kind, which a contact's name, an event's title,
 * a network's name, a vehicle's number, a flight, the account a two-factor key is for and whom a
 * message goes to all do. A name every code of the kind shares, such as a passkey sign-in's or a
 * WeChat merchant's payment code's, or one that is only part of the content, such as an eSIM's
 * server, says what the content is but not which, so it is not distinct.
 */
internal data class CarriedName(val name: String?, val distinct: Boolean)

/**
 * Names for content in words, where the content alone says what it is: the links of [KnownLink],
 * with a vehicle's number where the link carries one, and the parts of the basic kinds that name
 * them. Content these do not cover has no such name, and is shown as it is.
 */
internal object Titles {

    private val NONE = CarriedName(null, distinct = false)

    /**
     * The name [payload] carries. It carries none and is best shown as it is when it is a link
     * FerryQR does not know, plain text or a Wi-Fi Easy Connect code. A passkey request is named for
     * what it asks, since its content is only digits.
     */
    fun carried(context: Context, payload: Payload): CarriedName = when (payload) {
        is WebPayload -> link(context, payload.url)
        is SchemePayload -> {
            val known = link(context, payload.raw)
            val target = schemeTarget(payload)
            CarriedName(known.name ?: target, known.distinct || target != null)
        }
        is WifiPayload -> CarriedName(
            payload.ssid.ifBlank { context.getString(R.string.wifi_hidden) },
            distinct = payload.ssid.isNotBlank(),
        )
        is ContactPayload -> with(payload.contact) {
            named(listOfNotNull(name, organization, phones.firstOrNull(), emails.firstOrNull()).firstOrNull { it.isNotBlank() })
        }
        is EventPayload -> named(payload.event.title?.takeIf { it.isNotBlank() })
        is EsimPayload -> CarriedName(esimServer(payload.raw), distinct = false)
        // The payload means something only as WeChat reads it.
        is MiniProgramPayload -> CarriedName(WxText.forWeChat(payload.raw), distinct = false)
        is OtpPayload -> CarriedName(otpName(context, payload), distinct = payload.key.issuer != null || payload.key.account != null)
        is FidoPayload -> CarriedName(
            context.getString(
                when (PasskeyQr.operation(payload.raw)) {
                    PasskeyOperation.SIGN_IN -> R.string.title_passkey_sign_in
                    PasskeyOperation.CREATE -> R.string.title_passkey_create
                    PasskeyOperation.PRESENT_CREDENTIAL -> R.string.title_credential_present
                    PasskeyOperation.ADD_CREDENTIAL -> R.string.title_credential_add
                    null -> R.string.title_passkey_request
                },
            ),
            distinct = false,
        )
        is BoardingPassPayload -> {
            val flight = payload.pass.legs.firstOrNull()?.flight?.ifEmpty { null }
            CarriedName(
                flight?.let { context.getString(R.string.title_boarding_pass, it) } ?: context.getString(R.string.kind_boarding_pass),
                distinct = flight != null,
            )
        }
        else -> NONE
    }

    /** A name taken from the content's own fields, as a contact's or an event's is, which tells it apart whenever there is one. */
    private fun named(name: String?): CarriedName = CarriedName(name, distinct = name != null)

    /**
     * The name of the link or app URI [uri], with no name for one FerryQR does not know. Only a link
     * that carries a vehicle's number is told from others of its kind, by that number.
     */
    private fun link(context: Context, uri: String): CarriedName {
        val link = KnownLink.of(uri) ?: return NONE
        if (link.numbering == Numbering.NONE) return CarriedName(context.getString(link.title), distinct = false)
        val number = link.number(uri) ?: return NONE
        return CarriedName(context.getString(link.title, number), distinct = true)
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
