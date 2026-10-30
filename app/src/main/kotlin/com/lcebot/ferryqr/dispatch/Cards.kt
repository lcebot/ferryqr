package com.lcebot.ferryqr.dispatch

import android.app.Activity
import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.lcebot.ferryqr.R
import com.lcebot.ferryqr.content.ContactPayload
import com.lcebot.ferryqr.content.DppPayload
import com.lcebot.ferryqr.content.EsimPayload
import com.lcebot.ferryqr.content.EventPayload
import com.lcebot.ferryqr.content.FidoPayload
import com.lcebot.ferryqr.content.MiniProgramPayload
import com.lcebot.ferryqr.content.Payload
import com.lcebot.ferryqr.content.SchemePayload
import com.lcebot.ferryqr.content.TextPayload
import com.lcebot.ferryqr.content.WebPayload
import com.lcebot.ferryqr.content.WifiPayload
import com.lcebot.ferryqr.content.WifiSecurity
import com.lcebot.ferryqr.generate.MiniCodeArt
import com.lcebot.ferryqr.helper.WeChatHelper

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

/** [app] is the package the action hands off to; its own icon then replaces [icon]. */
data class CardAction(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    val app: String? = null,
    val run: (Activity) -> ActionResult,
)

/**
 * What the result sheet shows. The first action is the primary one. [image] shows a code that was
 * located but could not be read, since there is no content to show instead.
 */
data class ResultCard(
    val raw: String,
    @param:StringRes val kind: Int,
    @param:DrawableRes val icon: Int,
    val headline: String,
    val body: String?,
    val notes: List<Int>,
    val actions: List<CardAction>,
    val image: Bitmap? = null,
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
        is MiniProgramPayload -> Kind(R.string.kind_mini_program, R.drawable.ic_qr_code_scanner)
    }

    /**
     * A probable WeChat mini program code that could not be read, shown as [crop]. With the WeChat
     * helper the crop goes to WeChat's scanner, which then simply opens it. Without the helper, a
     * code the camera saw ([live]) is scanned again in WeChat; an image FerryQR holds cannot reach
     * WeChat's camera, so the user is pointed to the scanner's own album button instead.
     */
    fun miniProgram(activity: Activity, crop: Bitmap, helperReady: Boolean, live: Boolean): ResultCard {
        val installed = Launch.isInstalled(activity, WeChat.PACKAGE)
        val label = when {
            helperReady -> R.string.open_in_wechat
            live -> R.string.scan_in_wechat
            else -> R.string.mini_open_scanner
        }
        val body = when {
            helperReady -> R.string.mini_body_helper
            live -> R.string.mini_body_scan
            else -> R.string.mini_body_album
        }
        val action = CardAction(label, R.drawable.ic_open_in_new, WeChat.PACKAGE) {
            if (WeChat.openScanner(it, crop)) ActionResult.Handoff else ActionResult.Unavailable
        }
        return ResultCard(
            raw = "",
            kind = R.string.kind_mini_program,
            icon = R.drawable.ic_qr_code_scanner,
            headline = activity.getString(R.string.mini_headline),
            body = activity.getString(body),
            notes = if (installed) emptyList() else listOf(R.string.mini_no_wechat),
            actions = if (installed) listOf(action) else emptyList(),
            image = crop,
        )
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
        is MiniProgramPayload -> miniProgramCode(activity, payload)
    }

    /**
     * A mini program code that was read. Its content means nothing outside WeChat, so the card
     * shows the code drawn again from it. With the helper, WeChat's scanner is shown that drawing
     * and opens the mini program; without it, the user scans the code again in WeChat.
     */
    private fun miniProgramCode(activity: Activity, payload: MiniProgramPayload): ResultCard {
        val image = MiniCodeArt.draw(payload.raw)
        val installed = Launch.isInstalled(activity, WeChat.PACKAGE)
        val helperReady = WeChatHelper.ready.value
        val actions = buildList {
            if (installed && image != null) {
                add(CardAction(
                    if (helperReady) R.string.open_in_wechat else R.string.mini_open_scanner,
                    R.drawable.ic_open_in_new,
                    WeChat.PACKAGE,
                ) {
                    if (WeChat.openScanner(it, image)) ActionResult.Handoff else ActionResult.Unavailable
                })
            }
            add(copy(payload.raw))
        }
        return ResultCard(
            raw = payload.raw,
            kind = R.string.kind_mini_program,
            icon = R.drawable.ic_qr_code_scanner,
            headline = activity.getString(R.string.mini_read_headline),
            body = activity.getString(if (helperReady) R.string.mini_read_body_helper else R.string.mini_read_body),
            notes = if (installed) emptyList() else listOf(R.string.mini_no_wechat),
            actions = actions,
            image = image,
        )
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
                add(CardAction(R.string.open_in_alipay, R.drawable.ic_open_in_new, Alipay.PACKAGE) {
                    if (Alipay.handoff(it, payload.raw)) ActionResult.Handoff else ActionResult.Unavailable
                })
            }
            if (Launch.isInstalled(activity, WeChat.PACKAGE)) {
                // With the helper WeChat reads the code at once, so to the user it simply opens there.
                val opens = WeChatHelper.ready.value
                add(CardAction(
                    if (opens) R.string.open_in_wechat else R.string.scan_in_wechat,
                    R.drawable.ic_open_in_new,
                    WeChat.PACKAGE,
                ) {
                    if (WeChat.openScanner(it, payload.raw)) ActionResult.Handoff else ActionResult.Unavailable
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
