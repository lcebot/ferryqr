package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.BoardingPassParser
import io.github.lcebot.ferryqr.content.BoardingPassPayload
import io.github.lcebot.ferryqr.content.ContactPayload
import io.github.lcebot.ferryqr.content.DppPayload
import io.github.lcebot.ferryqr.content.EsimPayload
import io.github.lcebot.ferryqr.content.EventPayload
import io.github.lcebot.ferryqr.content.FidoPayload
import io.github.lcebot.ferryqr.content.MiniProgramPayload
import io.github.lcebot.ferryqr.content.OtpPayload
import io.github.lcebot.ferryqr.content.PasskeyQr
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.content.SchemePayload
import io.github.lcebot.ferryqr.content.TextPayload
import io.github.lcebot.ferryqr.content.WebPayload
import io.github.lcebot.ferryqr.content.WifiPayload
import io.github.lcebot.ferryqr.content.WifiSecurity
import io.github.lcebot.ferryqr.generate.MiniCodeArt
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
 * app's name formatted into [label], for the labels that name the app. [opens] marks an action that
 * opens the content elsewhere, as every app action does and the browser too, rather than acting on it.
 * [labeled] keeps the label on the button rather than showing only a round icon: every action that
 * opens the content keeps it, and so do actions that would look alike as icons.
 */
data class CardAction(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    val app: String? = null,
    val appName: String? = null,
    val opens: Boolean = app != null,
    val labeled: Boolean = opens,
    val run: (Activity) -> ActionResult,
)

/**
 * What the result sheet shows. The first action is the primary one. [image] is the code itself: a
 * picture of one that could not be read, or one drawn again from its content to be scanned from the
 * screen. [exportable] lets that drawing be shared, copied or saved, for history, where the original
 * is out of sight. [format] is empty for a code that was not read, which leaves nothing to keep in
 * history. [sections] lay out content with a structure of its own, under an empty [headline].
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
    val sections: List<CardSection> = emptyList(),
)

/** Parts of a card for content with a structure of its own, such as a boarding pass. */
sealed interface CardSection {
    /** A trip from one place to another, with [label] above it when there are several. */
    data class Route(val label: String?, val from: String, val to: String, val caption: String) : CardSection

    /** Named values, each a label with its value. */
    data class Fields(val rows: List<Pair<String, String>>) : CardSection

    /** The content exactly as the code holds it. */
    data class Raw(val text: String) : CardSection
}

/**
 * A passkey request is given up by the device that shows it within minutes: WebAuthn recommends
 * that ceremonies time out between five and ten minutes, so one older than five has most likely ended.
 */
private const val PASSKEY_EXPIRY_MS = 5 * 60 * 1000L

/** What the key URI format takes a link to mean when it leaves these out. */
private const val OTP_PERIOD = 30
private const val OTP_DIGITS = 6
private const val OTP_ALGORITHM = "SHA1"

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
        is BoardingPassPayload -> Kind(R.string.kind_boarding_pass, R.drawable.ic_flight)
        is OtpPayload -> Kind(R.string.kind_otp, R.drawable.ic_password)
    }

    /**
     * A probable WeChat mini program code that could not be read, shown as [crop]. With the module
     * in WeChat the crop goes to WeChat's scanner, which then simply opens it. Without the module, a
     * code the camera saw ([live]) is scanned again in WeChat; an image FerryQR holds cannot reach
     * WeChat's camera, so the body points to the scanner's own album button instead. [ready] holds
     * the packages of the apps that take content through the module.
     */
    fun miniProgram(context: Context, crop: Bitmap, live: Boolean, ready: Set<String>): ResultCard {
        val wechat = ActiveFerries.WECHAT
        val origin = if (live) Origin.CAMERA else Origin.IMAGE
        val body = when {
            wechat.isReady(ready) -> R.string.mini_body_module
            live -> R.string.mini_body_scan
            else -> R.string.mini_body_album
        }
        val installed = wechat.installed(context)
        return ResultCard(
            raw = "",
            kind = R.string.kind_mini_program,
            icon = R.drawable.ic_qr_code_scanner,
            headline = context.getString(R.string.mini_headline),
            body = context.getString(body),
            notes = if (installed) emptyList() else listOf(context.getString(R.string.mini_no_wechat)),
            actions = listOfNotNull(targetAction(context, wechat, origin, ready) { WeChat.openScanner(it, crop) }),
            image = crop,
        )
    }

    /**
     * The mini program code drawn last, by the content it holds. A scan's way to WeChat and its
     * card share one drawing of a 720 pixel square, and so do the cards built again for it as an
     * app's readiness changes. It is held weakly, so it goes once nothing shows or hands it on.
     */
    @Volatile
    private var lastMiniDrawing: Pair<String, WeakReference<Bitmap>>? = null

    /** The mini program code holding [raw], drawn as WeChat draws its codes, or null when none can hold it. */
    fun miniCodeDrawing(raw: String): Bitmap? {
        lastMiniDrawing?.let { (text, drawing) -> if (text == raw) drawing.get()?.let { return it } }
        return MiniCodeArt.draw(raw)?.also { lastMiniDrawing = raw to WeakReference(it) }
    }

    private val IP_LITERAL = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$|^\\[.*]$")

    /**
     * The card for [content], led by the content's own name as [ContentNames.headline] gives it, so
     * the card calls the content what its row and tile do. [ready] holds the packages of the apps
     * that take content through the module. [scannedAt] is when the code was read, which dates what
     * a code leaves undated.
     */
    fun build(
        context: Context,
        content: ReadContent,
        origin: Origin,
        ready: Set<String>,
        scannedAt: Long = System.currentTimeMillis(),
    ): ResultCard {
        val headline = content.names(context, null).headline
        return card(context, content.payload, headline, content.format, origin, ready, scannedAt).copy(format = content.format)
    }

    /**
     * The card for [raw] read in [format] taken as plain text, whatever it holds. It names nothing,
     * so it is what is left when making anything more of the content fails.
     */
    fun plainText(context: Context, raw: String, format: String): ResultCard =
        text(context, TextPayload(raw), raw, format).copy(format = format)

    private fun card(
        context: Context,
        payload: Payload,
        headline: String,
        format: String,
        origin: Origin,
        ready: Set<String>,
        scannedAt: Long,
    ): ResultCard = when (payload) {
        is WebPayload -> web(context, payload, headline, origin, ready)
        is SchemePayload -> scheme(context, payload, headline, origin, ready)
        is FidoPayload -> passkey(context, payload, headline, origin, scannedAt)
        is BoardingPassPayload -> boardingPass(context, payload, scannedAt)
        is OtpPayload -> otp(context, payload, origin, ready)
        // Only a code from the camera has already been offered to the system by the time its card
        // shows, so only then does the card say it was not taken; from elsewhere it offers to try.
        is EsimPayload -> ResultCard(
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
        is DppPayload -> ResultCard(
            payload.raw, R.string.kind_dpp, R.drawable.ic_wifi, headline, null,
            listOfNotNull(context.getString(R.string.dpp_unsupported).takeIf { origin == Origin.CAMERA }),
            listOf(
                CardAction(R.string.connect, R.drawable.ic_wifi) {
                    if (Launch.dpp(it, payload.raw)) ActionResult.Handoff else ActionResult.Unavailable
                },
                copy(payload.raw),
            ),
        )
        is WifiPayload -> wifi(context, payload, headline)
        is ContactPayload -> contact(payload, headline)
        is EventPayload -> event(context, payload, headline)
        is TextPayload -> text(context, payload, headline, format)
        is MiniProgramPayload -> miniProgramCode(context, payload, origin, ready)
    }

    /**
     * A mini program code that was read. Its content means nothing outside WeChat, so the card
     * shows the code drawn again from it. With the module, WeChat opens the mini program from what
     * was read, as after a scan of its own; without it, the user scans the code again in WeChat.
     */
    private fun miniProgramCode(context: Context, payload: MiniProgramPayload, origin: Origin, ready: Set<String>): ResultCard {
        val image = miniCodeDrawing(payload.raw)
        val wechat = ActiveFerries.WECHAT
        val installed = wechat.installed(context)
        val actions = buildList {
            if (image != null) {
                targetAction(context, wechat, origin, ready) { WeChat.openMiniProgram(it, payload.raw, image) }?.let { add(it) }
            }
            add(copy(payload.raw))
        }
        return ResultCard(
            raw = payload.raw,
            kind = R.string.kind_mini_program,
            icon = R.drawable.ic_qr_code_scanner,
            headline = context.getString(R.string.mini_read_headline),
            body = context.getString(R.string.mini_read_body),
            notes = if (installed) listOfNotNull(targetNote(context, wechat, origin, ready))
            else listOf(context.getString(R.string.mini_no_wechat)),
            actions = actions,
            image = image,
        )
    }

    /**
     * The action that hands content to [target], which [open] does. Ready, the app opens the content
     * wherever it came from. An app waiting for the module only opens its scanner, which can still
     * see a code in front of the camera, or pick an image from its album if it has one; a history
     * entry then gets no action.
     */
    private fun targetAction(
        context: Context,
        target: Ferry,
        origin: Origin,
        ready: Set<String>,
        open: (Activity) -> Boolean,
    ): CardAction? {
        if (!target.installed(context)) return null
        val label = when {
            target.isReady(ready) -> R.string.open_in_app
            origin == Origin.CAMERA -> R.string.scan_again_in_app
            origin == Origin.IMAGE && target.album -> R.string.open_app_scanner
            else -> return null
        }
        return CardAction(label, R.drawable.ic_open_in_new, target.pkg, target.label(context)) {
            if (open(it)) ActionResult.Handoff else ActionResult.Unavailable
        }
    }

    /** The actions that hand [text] to each of [ferries], in their order. */
    private fun ferryActions(context: Context, ferries: List<Ferry>, origin: Origin, ready: Set<String>, text: String): List<CardAction> =
        ferries.mapNotNull { ferry -> targetAction(context, ferry, origin, ready) { ferry.open(it, text) } }

    /**
     * That [owner] is needed but missing, for a code only its own app opens. The other ways in stay
     * on the card, though they can only show such a link as a page.
     */
    private fun missingNote(context: Context, owner: ActiveFerry): String? =
        if (owner.exclusive && !owner.installed(context)) context.getString(R.string.exclusive_not_installed, owner.label(context)) else null

    /** What the user has to do in an app waiting for the module, beyond what its action says. */
    private fun targetNote(context: Context, target: Ferry, origin: Origin, ready: Set<String>): String? {
        if (target.isReady(ready) || !target.installed(context)) return null
        val name = target.label(context)
        return when {
            origin == Origin.CAMERA -> null
            origin == Origin.IMAGE && target.album -> context.getString(R.string.pick_from_album_in_app, name)
            else -> context.getString(R.string.needs_module_in_app, name)
        }
    }

    /**
     * A passkey request from another device. Its passkey manager is offered again, which is what a
     * code in history is for: a quick retry while the other device still waits. A request older than
     * [PASSKEY_EXPIRY_MS], by the time the code says it was made or else by when it was scanned, has
     * most likely ended, which the card says, and copying it then comes first. Only a code from the
     * camera has already been offered to the passkey managers by the time its card shows.
     */
    private fun passkey(context: Context, payload: FidoPayload, headline: String, origin: Origin, scannedAt: Long): ResultCard {
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
     * A boarding pass: each flight's route with its own details, then the passenger's, then the
     * content as the code holds it, which keeps everything the card leaves out. Blank fields are left
     * out and fields that read as nothing known are shown as printed. A pass gives its dates without
     * the year, which [scannedAt] settles.
     */
    private fun boardingPass(context: Context, payload: BoardingPassPayload, scannedAt: Long): ResultCard {
        val pass = payload.pass
        val zone = ZoneId.systemDefault()
        val scanned = Instant.ofEpochMilli(scannedAt).atZone(zone).toLocalDate()
        fun rows(vararg pairs: Pair<Int, String?>) =
            pairs.mapNotNull { (label, value) -> value?.takeIf { it.isNotEmpty() }?.let { context.getString(label) to it } }
        val sections = buildList {
            pass.legs.forEachIndexed { index, leg ->
                val caption = listOfNotNull(leg.flight.ifEmpty { null }, flightDay(context, leg.dayOfYear, scanned, zone))
                add(
                    CardSection.Route(
                        label = if (pass.legs.size > 1) context.getString(R.string.boarding_leg, index + 1) else null,
                        from = leg.from,
                        to = leg.to,
                        caption = caption.joinToString("  "),
                    ),
                )
                val details = rows(
                    R.string.boarding_seat to leg.shownSeat,
                    R.string.boarding_class to leg.compartment,
                    R.string.boarding_sequence to leg.shownSequence,
                    R.string.boarding_booking_reference to leg.bookingReference,
                )
                if (details.isNotEmpty()) add(CardSection.Fields(details))
            }
            val traveller = rows(
                R.string.boarding_passenger to pass.passenger,
                R.string.boarding_frequent_flyer to pass.frequentFlyer,
                R.string.boarding_bag_tag to pass.bagTag,
            )
            if (traveller.isNotEmpty()) add(CardSection.Fields(traveller))
            add(CardSection.Raw(payload.raw))
        }
        val notes = listOfNotNull(
            context.getString(R.string.boarding_incomplete).takeUnless { pass.complete },
            context.getString(R.string.boarding_privacy),
        )
        return ResultCard(
            payload.raw, R.string.kind_boarding_pass, R.drawable.ic_flight, "", null, notes,
            listOf(copy(payload.raw), share(payload.raw)),
            sections = sections,
        )
    }

    /**
     * A two-factor key, from a camera scan only when no authenticator took it. The key's details come
     * first and the secret stays hidden, since a glance over a shoulder is enough to copy it. With no
     * authenticator installed, the card says so and leads with copying the secret, for one that adds
     * keys by hand; otherwise adding the key leads. The link itself can be copied and shared like
     * any other, with a note on what it gives away.
     */
    private fun otp(context: Context, payload: OtpPayload, origin: Origin, ready: Set<String>): ResultCard {
        val key = payload.key
        val addable = Launch.canOpen(context, payload.raw)
        // The authenticator the link goes to without asking is named; among several, the system asks.
        val authenticator = Ferries.rank(context, payload, ready)
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
        val actions = ferryActions(context, authenticator, origin, ready, payload.raw).ifEmpty {
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

    /** The flight's day as a month and day, or as printed when it is not a day of the year. */
    private fun flightDay(context: Context, dayOfYear: String, scanned: LocalDate, zone: ZoneId): String? {
        val date = dayOfYear.toIntOrNull()?.let { BoardingPassParser.flightDate(it, scanned) } ?: return dayOfYear.ifEmpty { null }
        val millis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)
    }

    /**
     * An app link, offered to its apps as [Ferries.rank] orders them: the app it belongs to when
     * FerryQR has its own way in, and the app that registers the scheme. The general apps are not
     * offered, since they open web links rather than other apps' schemes.
     */
    private fun scheme(context: Context, payload: SchemePayload, headline: String, origin: Origin, ready: Set<String>): ResultCard {
        val owner = ActiveFerries.claimant(payload)
        val ranked = Ferries.rank(context, payload, ready)
        val notes = listOfNotNull(
            context.getString(R.string.scheme_unhandled).takeUnless { Launch.opens(context, payload.uri, payload.scheme) },
            owner?.takeIf { it in ranked }?.let { targetNote(context, it, origin, ready) },
            owner?.let { missingNote(context, it) },
        )
        val actions = ferryActions(context, ranked, origin, ready, payload.raw) + copy(payload.raw) + share(payload.raw)
        return ResultCard(
            payload.raw, R.string.kind_app_link, R.drawable.ic_apps, headline, payload.raw.takeIf { headline != payload.raw },
            notes, actions,
        )
    }

    /**
     * A web link. The apps it goes to before any browser lead, as [Ferries.rank] orders them,
     * followed by the browser and then the general apps that open any link.
     */
    private fun web(context: Context, payload: WebPayload, headline: String, origin: Origin, ready: Set<String>): ResultCard {
        val host = payload.host
        // A link printed without its scheme belongs to its app by its URL, and is handed over as printed.
        val owner = ActiveFerries.claimant(payload)
        val ranked = Ferries.rank(context, payload, ready)
        val notes = buildList {
            if (payload.url.startsWith("http:", ignoreCase = true)) add(context.getString(R.string.warn_http))
            if (host.split('.').any { it.startsWith("xn--") } || host.any { it.code > 127 }) add(context.getString(R.string.warn_idn))
            if (IP_LITERAL.matches(host)) add(context.getString(R.string.warn_ip))
            // Only a code that belongs to an app needs that app; for other links it is one more
            // option. Nor does it once the system opens the link in that app directly instead.
            owner?.takeIf { it in ranked }?.let { targetNote(context, it, origin, ready) }?.let { add(it) }
            owner?.let { missingNote(context, it) }?.let { add(it) }
        }
        val general = Ferries.general(payload, ranked)
        val browser = CardAction(R.string.open, R.drawable.ic_open_in_new, opens = true) {
            if (Launch.browser(it, payload.url)) ActionResult.Handoff else ActionResult.Unavailable
        }
        val actions = ferryActions(context, ranked, origin, ready, payload.raw) + browser +
            ferryActions(context, general, origin, ready, payload.raw) + copy(payload.raw) + share(payload.raw)
        return ResultCard(payload.raw, R.string.kind_link, R.drawable.ic_link, headline, payload.url, notes, actions)
    }

    private fun wifi(context: Context, payload: WifiPayload, headline: String): ResultCard {
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

    /**
     * A contact, led by its name. One without a name, organisation, phone or email is led by the
     * first detail it has, which says more of it than the text of its code.
     */
    private fun contact(payload: ContactPayload, headline: String): ResultCard {
        val c = payload.contact
        val details = (c.phones + c.emails + listOfNotNull(c.organization, c.title, c.address, c.url, c.note))
        val shown = if (headline != payload.raw) headline else details.firstOrNull() ?: headline
        return ResultCard(
            payload.raw, R.string.kind_contact, R.drawable.ic_person_add, shown,
            details.filter { it != shown }.joinToString("\n").ifEmpty { null },
            emptyList(),
            listOf(
                CardAction(R.string.add_contact, R.drawable.ic_person_add) {
                    if (Launch.addContact(it, c)) ActionResult.Handoff else ActionResult.Unavailable
                },
                copy(payload.raw),
            ),
        )
    }

    /**
     * An event, led by its title. One without a title is led by its time, which says more of it
     * than the text of its code.
     */
    private fun event(context: Context, payload: EventPayload, headline: String): ResultCard {
        val e = payload.event
        val titled = headline != payload.raw
        val time = e.begin?.let { begin ->
            val flags = if (e.allDay) DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY
            else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY
            DateUtils.formatDateRange(context, begin, e.end ?: begin, flags)
        }
        return ResultCard(
            payload.raw, R.string.kind_event, R.drawable.ic_event,
            if (titled) headline else time ?: headline,
            listOfNotNull(time.takeIf { titled }, e.location, e.description).joinToString("\n").ifEmpty { null },
            emptyList(),
            listOf(
                CardAction(R.string.add_event, R.drawable.ic_event) {
                    if (Launch.addEvent(it, e)) ActionResult.Handoff else ActionResult.Unavailable
                },
                copy(payload.raw),
            ),
        )
    }

    private fun text(context: Context, payload: TextPayload, headline: String, format: String): ResultCard {
        val formatNote = if (format.contains("QR")) null
        else context.getString(R.string.format_label, format.replace('_', '-'))
        return ResultCard(
            payload.raw, R.string.kind_text, R.drawable.ic_notes, headline, formatNote,
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
