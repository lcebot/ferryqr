package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.ContactPayload
import io.github.lcebot.ferryqr.content.DppPayload
import io.github.lcebot.ferryqr.content.EsimPayload
import io.github.lcebot.ferryqr.content.EventPayload
import io.github.lcebot.ferryqr.content.FidoPayload
import io.github.lcebot.ferryqr.content.MiniProgramPayload
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.content.SchemePayload
import io.github.lcebot.ferryqr.content.TextPayload
import io.github.lcebot.ferryqr.content.WebPayload
import io.github.lcebot.ferryqr.content.WifiPayload
import io.github.lcebot.ferryqr.content.WifiSecurity
import io.github.lcebot.ferryqr.generate.MiniCodeArt

/** Where a code came from, which decides whether an app's own camera can still see it. */
enum class Origin {
    /** The live camera; the code is still in front of it. */
    CAMERA,

    /** An image on the phone; another app's scanner can only reach it through its album. */
    IMAGE,

    /** A history entry; no code is left to scan. */
    HISTORY,
}

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

/**
 * [app] is the package the action hands off to; its own icon then replaces [icon]. [appName] is the
 * name formatted into [label], for the labels that name the app. [opens] marks an action that opens
 * the content elsewhere, as every app action does and the browser too, rather than acting on it.
 */
data class CardAction(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    val app: String? = null,
    @param:StringRes val appName: Int? = null,
    val opens: Boolean = app != null,
    val run: (Activity) -> ActionResult,
)

/**
 * What the result sheet shows. The first action is the primary one. [image] is the code itself: a
 * picture of one that could not be read, or one drawn again from its content to be scanned from the
 * screen. [exportable] lets that drawing be shared, copied or saved, for history, where the original
 * is out of sight. [format] is empty for a code that was not read, which leaves nothing to keep in
 * history.
 */
data class ResultCard(
    val raw: String,
    @param:StringRes val kind: Int,
    @param:DrawableRes val icon: Int,
    val headline: String,
    val body: String?,
    val notes: List<String>,
    val actions: List<CardAction>,
    val image: Bitmap? = null,
    val format: String = "",
    val exportable: Boolean = false,
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
        val installed = Targets.WECHAT.installed(activity)
        val label = when {
            helperReady -> R.string.open_in_app
            live -> R.string.scan_again_in_app
            else -> R.string.open_app_scanner
        }
        val body = when {
            helperReady -> R.string.mini_body_helper
            live -> R.string.mini_body_scan
            else -> R.string.mini_body_album
        }
        val action = CardAction(label, R.drawable.ic_open_in_new, WeChat.PACKAGE, Targets.WECHAT.name) {
            if (WeChat.openScanner(it, crop)) ActionResult.Handoff else ActionResult.Unavailable
        }
        return ResultCard(
            raw = "",
            kind = R.string.kind_mini_program,
            icon = R.drawable.ic_qr_code_scanner,
            headline = activity.getString(R.string.mini_headline),
            body = activity.getString(body),
            notes = if (installed) emptyList() else listOf(activity.getString(R.string.mini_no_wechat)),
            actions = if (installed) listOf(action) else emptyList(),
            image = crop,
        )
    }

    private val IP_LITERAL = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$|^\\[.*]$")

    /** A short name for content in lists and shortcuts, or null when only its kind describes it. */
    fun titleOf(payload: Payload): String? = when (payload) {
        is WebPayload -> payload.host
        is WifiPayload -> payload.ssid
        is ContactPayload -> payload.contact.name ?: payload.contact.organization
        is EventPayload -> payload.event.title
        is TextPayload, is SchemePayload -> payload.raw
        is FidoPayload, is EsimPayload, is DppPayload, is MiniProgramPayload -> null
    }

    fun build(activity: Activity, payload: Payload, format: String, origin: Origin): ResultCard =
        card(activity, payload, format, origin).copy(format = format)

    private fun card(activity: Activity, payload: Payload, format: String, origin: Origin): ResultCard = when (payload) {
        is WebPayload -> web(activity, payload, origin)
        is SchemePayload -> scheme(activity, payload, origin)
        is FidoPayload -> ResultCard(
            payload.raw, R.string.kind_fido, R.drawable.ic_key, payload.raw, null,
            listOf(activity.getString(R.string.fido_unhandled)), listOf(copy(payload.raw)),
        )
        is EsimPayload -> ResultCard(
            payload.raw, R.string.kind_esim, R.drawable.ic_sim_card, payload.raw, null,
            listOf(activity.getString(R.string.esim_unhandled)),
            listOf(
                copy(payload.raw),
                CardAction(R.string.open_sim_settings, R.drawable.ic_settings) {
                    if (Launch.simSettings(it)) ActionResult.Done else ActionResult.Unavailable
                },
            ),
        )
        is DppPayload -> ResultCard(
            payload.raw, R.string.kind_dpp, R.drawable.ic_wifi, payload.raw, null,
            listOf(activity.getString(R.string.dpp_unsupported)), listOf(copy(payload.raw)),
        )
        is WifiPayload -> wifi(activity, payload)
        is ContactPayload -> contact(payload)
        is EventPayload -> event(activity, payload)
        is TextPayload -> text(activity, payload, format)
        is MiniProgramPayload -> miniProgramCode(activity, payload, origin)
    }

    /**
     * A mini program code that was read. Its content means nothing outside WeChat, so the card
     * shows the code drawn again from it. With the helper, WeChat opens the mini program from what
     * was read, as after a scan of its own; without it, the user scans the code again in WeChat.
     */
    private fun miniProgramCode(activity: Activity, payload: MiniProgramPayload, origin: Origin): ResultCard {
        val image = MiniCodeArt.draw(payload.raw)
        val wechat = Targets.WECHAT
        val installed = wechat.installed(activity)
        val actions = buildList {
            if (image != null) {
                targetAction(activity, wechat, origin) { WeChat.openMiniProgram(it, payload.raw, image) }?.let { add(it) }
            }
            add(copy(payload.raw))
        }
        return ResultCard(
            raw = payload.raw,
            kind = R.string.kind_mini_program,
            icon = R.drawable.ic_qr_code_scanner,
            headline = activity.getString(R.string.mini_read_headline),
            body = activity.getString(R.string.mini_read_body),
            notes = if (installed) listOfNotNull(targetNote(activity, wechat, origin))
            else listOf(activity.getString(R.string.mini_no_wechat)),
            actions = actions,
            image = image,
        )
    }

    /**
     * The action that hands content to [target], which [open] does. Ready, the app opens the content
     * wherever it came from. An app waiting for the helper only opens its scanner, which can still
     * see a code in front of the camera, or pick an image from its album if it has one; a history
     * entry then gets no action.
     */
    private fun targetAction(
        activity: Activity,
        target: Target,
        origin: Origin,
        open: (Activity) -> Boolean,
    ): CardAction? {
        if (!target.installed(activity)) return null
        val label = when {
            target.ready -> R.string.open_in_app
            origin == Origin.CAMERA -> R.string.scan_again_in_app
            origin == Origin.IMAGE && target.album -> R.string.open_app_scanner
            else -> return null
        }
        return CardAction(label, R.drawable.ic_open_in_new, target.pkg, target.name) {
            if (open(it)) ActionResult.Handoff else ActionResult.Unavailable
        }
    }

    /** What the user has to do in an app waiting for the helper, beyond what its action says. */
    private fun targetNote(activity: Activity, target: Target, origin: Origin): String? {
        if (target.ready || !target.installed(activity)) return null
        val name = activity.getString(target.name)
        return when {
            origin == Origin.CAMERA -> null
            origin == Origin.IMAGE && target.album -> activity.getString(R.string.pick_from_album_in_app, name)
            else -> activity.getString(R.string.needs_module_in_app, name)
        }
    }

    /**
     * An app link nothing opened. The app it belongs to is offered when it has its own way in, and
     * the general apps are not, since they open web links rather than other apps' schemes.
     */
    private fun scheme(activity: Activity, payload: SchemePayload, origin: Origin): ResultCard {
        val owner = Targets.claimant(payload.raw)
        val notes = listOfNotNull(
            activity.getString(R.string.scheme_unhandled),
            owner?.let { targetNote(activity, it, origin) },
        )
        val actions = listOfNotNull(
            owner?.let { o -> targetAction(activity, o, origin) { o.open(it, payload.raw) } },
            copy(payload.raw),
            share(payload.raw),
        )
        return ResultCard(payload.raw, R.string.kind_app_link, R.drawable.ic_apps, payload.raw, null, notes, actions)
    }

    /**
     * A web link. A code of an app that only its own scanner opens properly leads with that app;
     * otherwise the browser does, followed by the general apps that open any link.
     */
    private fun web(activity: Activity, payload: WebPayload, origin: Origin): ResultCard {
        val host = payload.host
        // A link printed without its scheme is claimed by its URL, and still handed over as printed.
        val owner = Targets.claimant(payload.url)
        val notes = buildList {
            if (payload.url.startsWith("http:", ignoreCase = true)) add(activity.getString(R.string.warn_http))
            if (host.split('.').any { it.startsWith("xn--") } || host.any { it.code > 127 }) add(activity.getString(R.string.warn_idn))
            if (IP_LITERAL.matches(host)) add(activity.getString(R.string.warn_ip))
            // Only a code that belongs to an app needs that app; for other links it is one more option.
            owner?.let { targetNote(activity, it, origin) }?.let { add(it) }
        }
        val offered = Targets.forText(payload.url).mapNotNull { target ->
            targetAction(activity, target, origin) { target.open(it, payload.raw) }?.let { target to it }
        }
        val browser = CardAction(R.string.open, R.drawable.ic_open_in_new, opens = true) {
            if (Launch.browser(it, payload.url)) ActionResult.Handoff else ActionResult.Unavailable
        }
        val actions = buildList {
            offered.filter { it.first === owner }.forEach { add(it.second) }
            add(browser)
            offered.filter { it.first !== owner }.forEach { add(it.second) }
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
        // Without a suggestion only WEP and enterprise networks are unsupported as such; for WPA and
        // WPA3 the password is missing or one the system rejects, such as one shorter than 8 characters.
        val notes = when {
            suggestion != null || payload.security == WifiSecurity.OPEN -> emptyList()
            payload.security == WifiSecurity.WEP || payload.security == WifiSecurity.EAP ->
                listOf(activity.getString(R.string.wifi_connect_unsupported))
            else -> listOf(activity.getString(R.string.wifi_password_invalid))
        }
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
