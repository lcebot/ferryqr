package com.lcebot.ferryqr.dispatch

import android.app.Activity
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.lcebot.ferryqr.R
import com.lcebot.ferryqr.content.ContactPayload
import com.lcebot.ferryqr.content.DppPayload
import com.lcebot.ferryqr.content.EsimPayload
import com.lcebot.ferryqr.content.EventPayload
import com.lcebot.ferryqr.content.FidoPayload
import com.lcebot.ferryqr.content.Payload
import com.lcebot.ferryqr.content.SchemePayload
import com.lcebot.ferryqr.content.TextPayload
import com.lcebot.ferryqr.content.WebPayload
import com.lcebot.ferryqr.content.WifiPayload
import com.lcebot.ferryqr.content.WifiSecurity

enum class ActionResult {
    /** Another app took over; the scanner can close. */
    Handoff,

    /** Content went to the clipboard. */
    Copied,

    /** A system screen opened on top; the card stays for when the user comes back. */
    Done,

    /** Nothing could handle the action. */
    Unavailable,
}

data class CardAction(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    val run: (Activity) -> ActionResult,
)

/** What the result sheet shows. The first action is the primary one. */
data class ResultCard(
    val raw: String,
    @param:StringRes val kind: Int,
    @param:DrawableRes val icon: Int,
    val headline: String,
    val body: String?,
    val notes: List<Int>,
    val actions: List<CardAction>,
)

data class Kind(@param:StringRes val label: Int, @param:DrawableRes val icon: Int)

object Cards {

    fun kindOf(payload: Payload): Kind = when (payload) {
        is WebPayload -> Kind(R.string.kind_link, R.drawable.ic_link)
        is SchemePayload -> Kind(R.string.kind_app_link, R.drawable.ic_apps)
        is FidoPayload -> Kind(R.string.kind_fido, R.drawable.ic_key)
        is EsimPayload -> Kind(R.string.kind_esim, R.drawable.ic_sim_card)
        is DppPayload -> Kind(R.string.kind_dpp, R.drawable.ic_wifi)
        is WifiPayload -> Kind(R.string.kind_wifi, R.drawable.ic_wifi)
        is ContactPayload -> Kind(R.string.kind_contact, R.drawable.ic_person_add)
        is EventPayload -> Kind(R.string.kind_event, R.drawable.ic_event)
        is TextPayload -> Kind(R.string.kind_text, R.drawable.ic_notes)
    }

    private val IP_LITERAL = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$|^\\[.*]$")

    fun build(activity: Activity, payload: Payload, format: String): ResultCard = when (payload) {
        is WebPayload -> web(activity, payload)
        is SchemePayload -> ResultCard(
            raw = payload.raw,
            kind = R.string.kind_app_link,
            icon = R.drawable.ic_apps,
            headline = payload.raw,
            body = null,
            notes = listOf(R.string.scheme_unhandled),
            actions = listOf(copy(payload.raw), share(payload.raw)),
        )
        is FidoPayload -> ResultCard(
            payload.raw, R.string.kind_fido, R.drawable.ic_key, payload.raw, null,
            listOf(R.string.fido_unhandled), listOf(copy(payload.raw)),
        )
        is EsimPayload -> ResultCard(
            payload.raw, R.string.kind_esim, R.drawable.ic_sim_card, payload.raw, null,
            listOf(R.string.esim_unhandled),
            listOf(
                copy(payload.raw),
                CardAction(R.string.open_sim_settings, R.drawable.ic_settings) {
                    if (Launch.simSettings(it)) ActionResult.Done else ActionResult.Unavailable
                },
            ),
        )
        is DppPayload -> ResultCard(
            payload.raw, R.string.kind_dpp, R.drawable.ic_wifi, payload.raw, null,
            listOf(R.string.dpp_unsupported), listOf(copy(payload.raw)),
        )
        is WifiPayload -> wifi(activity, payload)
        is ContactPayload -> contact(payload)
        is EventPayload -> event(activity, payload)
        is TextPayload -> text(activity, payload, format)
    }

    private fun web(activity: Activity, payload: WebPayload): ResultCard {
        val host = payload.host
        val notes = buildList {
            if (payload.url.startsWith("http:", ignoreCase = true)) add(R.string.warn_http)
            if (host.split('.').any { it.startsWith("xn--") } || host.any { it.code > 127 }) add(R.string.warn_idn)
            if (IP_LITERAL.matches(host)) add(R.string.warn_ip)
        }
        val actions = buildList {
            add(CardAction(R.string.open, R.drawable.ic_open_in_new) {
                if (Launch.browser(it, payload.url)) ActionResult.Handoff else ActionResult.Unavailable
            })
            if (Launch.isInstalled(activity, Alipay.PACKAGE)) {
                add(CardAction(R.string.open_in_alipay, R.drawable.ic_account_balance_wallet) {
                    if (Alipay.handoff(it, payload.raw)) ActionResult.Handoff else ActionResult.Unavailable
                })
            }
            if (Launch.isInstalled(activity, WeChat.PACKAGE)) {
                add(CardAction(R.string.scan_in_wechat, R.drawable.ic_qr_code_scanner) {
                    if (WeChat.openScanner(it)) ActionResult.Handoff else ActionResult.Unavailable
                })
            }
            add(copy(payload.raw))
            add(share(payload.raw))
        }
        return ResultCard(payload.raw, R.string.kind_link, R.drawable.ic_link, host, payload.url, notes, actions)
    }

    private fun wifi(activity: Activity, payload: WifiPayload): ResultCard {
        val security = activity.getString(
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
            payload.password?.let { activity.getString(R.string.wifi_password, it) },
            if (payload.hidden) activity.getString(R.string.wifi_hidden) else null,
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
        val notes = if (suggestion == null && payload.security != WifiSecurity.OPEN) {
            listOf(R.string.wifi_connect_unsupported)
        } else emptyList()
        return ResultCard(payload.raw, R.string.kind_wifi, R.drawable.ic_wifi, payload.ssid, body, notes, actions)
    }

    private fun contact(payload: ContactPayload): ResultCard {
        val c = payload.contact
        val details = (c.phones + c.emails + listOfNotNull(c.organization, c.title, c.address, c.url, c.note))
        val headline = c.name ?: details.firstOrNull() ?: payload.raw
        return ResultCard(
            payload.raw, R.string.kind_contact, R.drawable.ic_person_add, headline,
            details.filter { it != headline }.joinToString("\n").ifEmpty { null },
            emptyList(),
            listOf(
                CardAction(R.string.add_contact, R.drawable.ic_person_add) {
                    if (Launch.addContact(it, c)) ActionResult.Handoff else ActionResult.Unavailable
                },
                copy(payload.raw),
            ),
        )
    }

    private fun event(activity: Activity, payload: EventPayload): ResultCard {
        val e = payload.event
        val time = e.begin?.let { begin ->
            val flags = if (e.allDay) DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY
            else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY
            DateUtils.formatDateRange(activity, begin, e.end ?: begin, flags)
        }
        return ResultCard(
            payload.raw, R.string.kind_event, R.drawable.ic_event,
            e.title ?: time ?: payload.raw,
            listOfNotNull(time.takeIf { e.title != null }, e.location, e.description).joinToString("\n").ifEmpty { null },
            emptyList(),
            listOf(
                CardAction(R.string.add_event, R.drawable.ic_event) {
                    if (Launch.addEvent(it, e)) ActionResult.Handoff else ActionResult.Unavailable
                },
                copy(payload.raw),
            ),
        )
    }

    private fun text(activity: Activity, payload: TextPayload, format: String): ResultCard {
        val formatNote = if (format.contains("QR")) null
        else activity.getString(R.string.format_label, format.replace('_', '-'))
        return ResultCard(
            payload.raw, R.string.kind_text, R.drawable.ic_notes, payload.raw, formatNote,
            emptyList(),
            listOf(
                copy(payload.raw),
                CardAction(R.string.search, R.drawable.ic_search) {
                    if (Launch.search(it, payload.raw)) ActionResult.Handoff else ActionResult.Unavailable
                },
                share(payload.raw),
            ),
        )
    }

    private fun copy(text: String) = CardAction(R.string.copy, R.drawable.ic_content_copy) {
        Launch.copy(it, text)
        ActionResult.Copied
    }

    private fun share(text: String) = CardAction(R.string.share, R.drawable.ic_share) {
        if (Launch.share(it, text)) ActionResult.Done else ActionResult.Unavailable
    }
}
