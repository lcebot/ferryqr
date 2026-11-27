package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import android.text.format.DateUtils
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.DppPayload
import io.github.lcebot.ferryqr.content.EsimPayload
import io.github.lcebot.ferryqr.content.FidoPayload
import io.github.lcebot.ferryqr.content.OtpPayload
import io.github.lcebot.ferryqr.content.PasskeyQr
import io.github.lcebot.ferryqr.content.WifiPayload
import io.github.lcebot.ferryqr.content.WifiSecurity

/**
 * A passkey request is given up by the device that shows it within minutes: WebAuthn recommends
 * that ceremonies time out between five and ten minutes, so one older than five has most likely ended.
 */
private const val PASSKEY_EXPIRY_MS = 5 * 60 * 1000L

/** What the key URI format takes a link to mean when it leaves these out. */
private const val OTP_PERIOD = 30
private const val OTP_DIGITS = 6
private const val OTP_ALGORITHM = "SHA1"

/**
 * A passkey request from another device. Its passkey manager is offered again, which is what a
 * code in history is for: a quick retry while the other device still waits. A request older than
 * [PASSKEY_EXPIRY_MS], by the time the code says it was made or else by when it was scanned, has
 * most likely ended, which the card says, and copying it then comes first. Only a code from the
 * camera has already been offered to the passkey managers by the time its card shows.
 */
internal fun Cards.passkey(context: Context, payload: FidoPayload, headline: String, origin: Origin, scannedAt: Long): ResultCard {
    val now = System.currentTimeMillis()
    val created = PasskeyQr.createdAt(payload.raw)?.times(1000)?.takeIf { it <= now } ?: scannedAt
    val expired = now - created > PASSKEY_EXPIRY_MS
    val use = CardAction(R.string.use_passkey, R.drawable.ic_key, opens = true) {
        if (Launch.fido(it, payload.raw)) ActionResult.Handoff else ActionResult.Unavailable
    }
    val notes = listOfNotNull(
        context.getString(R.string.fido_unhandled).takeIf { origin == Origin.CAMERA },
        context.getString(
            R.string.passkey_expired,
            DateUtils.getRelativeTimeSpanString(created, now, DateUtils.MINUTE_IN_MILLIS),
        ).takeIf { expired },
    )
    val actions = if (expired) listOf(copy(payload.raw), use) else listOf(use, copy(payload.raw))
    return ResultCard(payload.raw, R.string.kind_fido, R.drawable.ic_key, headline, payload.raw, notes, actions)
}

/**
 * A two-factor key, from a camera scan only when no authenticator took it. The key's details come
 * first and the secret stays hidden, since a glance over a shoulder is enough to copy it. With no
 * authenticator installed, the card says so and leads with copying the secret, for one that adds
 * keys by hand; otherwise adding the key leads. The link itself can be copied and shared like
 * any other, with a note on what it gives away.
 */
internal fun Cards.otp(env: FerryEnv, payload: OtpPayload, origin: Origin): ResultCard {
    val context = env.context
    val key = payload.key
    val addable = Launch.canOpen(context, payload.raw)
    // The authenticator the link goes to without asking is named; among several, the system asks.
    val authenticator = env.rank(payload)
    val rows = buildList {
        key.issuer?.let { add(context.getString(R.string.otp_issuer) to it) }
        key.account?.let { add(context.getString(R.string.otp_account) to it) }
        add(context.getString(R.string.otp_type) to key.type.uppercase())
        if (key.type != "hotp") {
            val seconds = key.period ?: OTP_PERIOD
            add(context.getString(R.string.otp_period) to context.resources.getQuantityString(R.plurals.otp_seconds, seconds, seconds))
        }
        key.counter?.let { add(context.getString(R.string.otp_counter) to it.toString()) }
        key.digits?.takeIf { it != OTP_DIGITS }?.let { add(context.getString(R.string.otp_digits) to it.toString()) }
        key.algorithm?.takeIf { it != OTP_ALGORITHM }?.let { add(context.getString(R.string.otp_algorithm) to it) }
    }
    val notes = listOfNotNull(
        context.getString(R.string.otp_unhandled).takeUnless { addable },
        context.getString(R.string.otp_privacy),
    )
    val actions = ferryActions(env, authenticator, origin, payload.raw).ifEmpty {
        listOfNotNull(
            CardAction(R.string.otp_add, R.drawable.ic_password, opens = true) {
                if (Launch.scheme(it, payload.raw, "otpauth")) ActionResult.Handoff else ActionResult.Unavailable
            }.takeIf { addable },
        )
    } + listOfNotNull(
        CardAction(R.string.otp_copy_secret, R.drawable.ic_content_copy, labeled = true) {
            Launch.copy(it, key.secret, sensitive = true)
            ActionResult.Copied
        },
        key.account?.let { account ->
            CardAction(R.string.otp_copy_account, R.drawable.ic_content_copy, labeled = true) {
                Launch.copy(it, account)
                ActionResult.Copied
            }
        },
        CardAction(R.string.copy, R.drawable.ic_content_copy) {
            Launch.copy(it, payload.raw, sensitive = true)
            ActionResult.Copied
        },
        share(payload.raw),
    )
    return ResultCard(
        payload.raw, R.string.kind_otp, R.drawable.ic_password, "", null, notes, actions,
        sections = listOf(CardSection.Fields(rows)),
    )
}

/**
 * An eSIM activation code. Only a code from the camera has already been offered to the system by
 * the time its card shows, so only then does the card say it was not taken; from elsewhere it
 * offers to try.
 */
internal fun Cards.esim(context: Context, payload: EsimPayload, headline: String, origin: Origin): ResultCard = ResultCard(
    payload.raw, R.string.kind_esim, R.drawable.ic_sim_card, headline, payload.raw.takeIf { headline != payload.raw },
    listOfNotNull(context.getString(R.string.esim_unhandled).takeIf { origin == Origin.CAMERA }),
    listOf(
        CardAction(R.string.add_esim, R.drawable.ic_sim_card) {
            if (Launch.esim(it, payload.raw)) ActionResult.Handoff else ActionResult.Unavailable
        },
        copy(payload.raw),
        CardAction(R.string.open_sim_settings, R.drawable.ic_settings) {
            if (Launch.simSettings(it)) ActionResult.Done else ActionResult.Unavailable
        },
    ),
)

/**
 * A Wi-Fi Easy Connect code. As with an eSIM code, only one from the camera has already been offered
 * to the system by the time its card shows, so only then does the card say the system could not start it.
 */
internal fun Cards.dpp(context: Context, payload: DppPayload, headline: String, origin: Origin): ResultCard = ResultCard(
    payload.raw, R.string.kind_dpp, R.drawable.ic_wifi, headline, null,
    listOfNotNull(context.getString(R.string.dpp_unsupported).takeIf { origin == Origin.CAMERA }),
    listOf(
        CardAction(R.string.connect, R.drawable.ic_wifi) {
            if (Launch.dpp(it, payload.raw)) ActionResult.Handoff else ActionResult.Unavailable
        },
        copy(payload.raw),
    ),
)

internal fun Cards.wifi(context: Context, payload: WifiPayload, headline: String): ResultCard {
    val security = context.getString(
        when (payload.security) {
            WifiSecurity.OPEN -> R.string.wifi_security_open
            WifiSecurity.WEP -> R.string.wifi_security_wep
            WifiSecurity.WPA -> R.string.wifi_security_wpa
            WifiSecurity.SAE -> R.string.wifi_security_sae
            WifiSecurity.EAP -> R.string.wifi_security_eap
        }
    )
    val body = listOfNotNull(
        security,
        payload.password?.let { context.getString(R.string.wifi_password, it) },
        // A network without a name is already called hidden by its headline.
        context.getString(R.string.wifi_hidden).takeIf { payload.hidden && it != headline },
    ).joinToString("\n")
    val suggestion = Launch.wifiSuggestion(payload)
    val actions = buildList {
        if (suggestion != null) {
            add(CardAction(R.string.connect, R.drawable.ic_wifi) {
                if (Launch.connectWifi(it, suggestion)) ActionResult.Done else ActionResult.Unavailable
            })
        }
        payload.password?.let { password ->
            add(CardAction(R.string.copy_password, R.drawable.ic_password) {
                Launch.copy(it, password, sensitive = true)
                ActionResult.Copied
            })
        }
        add(copy(payload.raw))
    }
    // Without a suggestion only WEP and enterprise networks are unsupported as such; for WPA and
    // WPA3 the password is missing or one the system rejects, such as one shorter than 8 characters.
    val notes = when {
        suggestion != null || payload.security == WifiSecurity.OPEN -> emptyList()
        payload.security == WifiSecurity.WEP || payload.security == WifiSecurity.EAP ->
            listOf(context.getString(R.string.wifi_connect_unsupported))
        else -> listOf(context.getString(R.string.wifi_password_invalid))
    }
    return ResultCard(payload.raw, R.string.kind_wifi, R.drawable.ic_wifi, headline, body, notes, actions)
}
