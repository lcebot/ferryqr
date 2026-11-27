package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.BoardingPassPayload
import io.github.lcebot.ferryqr.content.ContactPayload
import io.github.lcebot.ferryqr.content.DppPayload
import io.github.lcebot.ferryqr.content.EsimPayload
import io.github.lcebot.ferryqr.content.EventPayload
import io.github.lcebot.ferryqr.content.FidoPayload
import io.github.lcebot.ferryqr.content.MiniProgramPayload
import io.github.lcebot.ferryqr.content.OtpPayload
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.content.SchemePayload
import io.github.lcebot.ferryqr.content.TextPayload
import io.github.lcebot.ferryqr.content.WebPayload
import io.github.lcebot.ferryqr.content.WifiPayload
import io.github.lcebot.ferryqr.generate.CodeDrawing
import io.github.lcebot.ferryqr.generate.MiniCodeArt
import java.lang.ref.WeakReference

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
 * screen, the base of [drawing] then. [exportable] lets that drawing be shared, copied or saved, for
 * history, where the original is out of sight. [format] is empty for a code that was not read, which
 * leaves nothing to keep in history. [sections] lay out content with a structure of its own, under
 * an empty [headline].
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
    /** What [image] was drawn from when it is a code drawn from content, whose base is [image] itself. */
    val drawing: CodeDrawing? = null,
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

data class Kind(@param:StringRes val label: Int, @param:DrawableRes val icon: Int)

/**
 * The drawing [draw] made last, by the content it was made from, since a card is built again for the
 * same content whenever what it offers may change. It is held weakly, so it goes once nothing shows
 * or hands it on.
 */
private class LastDrawing<K : Any, T : Any>(private val draw: (K) -> T?) {
    @Volatile
    private var last: Pair<K, WeakReference<T>>? = null

    fun of(key: K): T? {
        last?.let { (drawnFor, drawing) -> if (drawnFor == key) drawing.get()?.let { return it } }
        return draw(key)?.also { last = key to WeakReference(it) }
    }
}

object Cards {

    /** The kind of a mini program code, read or only located, the one kind a code without content can be. */
    val MINI_PROGRAM_KIND = Kind(R.string.kind_mini_program, R.drawable.ic_qr_code_scanner)

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
        is MiniProgramPayload -> MINI_PROGRAM_KIND
        is BoardingPassPayload -> Kind(R.string.kind_boarding_pass, R.drawable.ic_flight)
        is OtpPayload -> Kind(R.string.kind_otp, R.drawable.ic_password)
    }

    /**
     * A probable WeChat mini program code that could not be read, shown as [crop]. With the module
     * in WeChat the crop goes to WeChat's scanner, which then simply opens it. Without the module, a
     * code the camera saw is scanned again in WeChat; an image FerryQR holds cannot reach WeChat's
     * camera, so the body points to the scanner's own album button instead.
     */
    fun miniProgram(env: FerryEnv, crop: Bitmap, origin: Origin): ResultCard {
        val context = env.context
        val wechat = ActiveFerries.WECHAT
        val body = when {
            wechat.isReady(env) -> R.string.mini_body_module
            origin == Origin.CAMERA -> R.string.mini_body_scan
            else -> R.string.mini_body_album
        }
        return plainMiniProgram(context, crop).copy(
            body = context.getString(body),
            notes = if (wechat.installed(env)) emptyList() else listOf(context.getString(R.string.mini_no_wechat)),
            actions = listOfNotNull(targetAction(env, wechat, origin) { WeChat.openScanner(it, crop) }),
        )
    }

    /**
     * A mini program code located but not read, shown as [crop] under its headline and nothing more,
     * which asks nothing of the system. [miniProgram] adds to it, and a card that fails falls back to it.
     */
    fun plainMiniProgram(context: Context, crop: Bitmap): ResultCard = ResultCard(
        raw = "",
        kind = MINI_PROGRAM_KIND.label,
        icon = MINI_PROGRAM_KIND.icon,
        headline = context.getString(R.string.mini_headline),
        body = null,
        notes = emptyList(),
        actions = emptyList(),
        image = crop,
    )

    /**
     * A scan's way to WeChat and its card share one drawing of a 720 pixel square, and so do the
     * cards built again for it as an app's readiness changes or the language does.
     */
    private val miniDrawings = LastDrawing<String, Bitmap> { raw -> MiniCodeArt.draw(raw) }

    /**
     * A history card built again keeps its code's drawing, and with it the taller drawings made for
     * its enlarged code, rather than drawing it anew.
     */
    private val codeDrawings = LastDrawing<Pair<String, String>, CodeDrawing> { (text, format) -> CodeDrawing.redraw(text, format) }

    /** The mini program code holding [raw], drawn as WeChat draws its codes, or null when none can hold it. */
    fun miniCodeDrawing(raw: String): Bitmap? = miniDrawings.of(raw)

    /** [text] read in [format] drawn again as [CodeDrawing.redraw] draws it, or null when nothing can hold it. */
    fun codeDrawing(text: String, format: String): CodeDrawing? = codeDrawings.of(text to format)

    /**
     * The card for [content], led by the content's own name as [ContentNames.headline] gives it, so
     * the card calls the content what its row and tile do. [scannedAt] is when the code was read,
     * which dates what a code leaves undated.
     */
    fun build(env: FerryEnv, content: ReadContent, origin: Origin, scannedAt: Long = System.currentTimeMillis()): ResultCard {
        val headline = content.names(env.context, null).headline
        return card(env, content.payload, headline, content.format, origin, scannedAt).copy(format = content.format)
    }

    /**
     * The card for [raw] read in [format] taken as plain text, whatever it holds. It names nothing,
     * so it is what is left when making anything more of the content fails.
     */
    fun plainText(context: Context, raw: String, format: String): ResultCard =
        text(context, TextPayload(raw), raw, format).copy(format = format)

    private fun card(
        env: FerryEnv,
        payload: Payload,
        headline: String,
        format: String,
        origin: Origin,
        scannedAt: Long,
    ): ResultCard = when (payload) {
        is WebPayload -> web(env, payload, headline, origin)
        is SchemePayload -> scheme(env, payload, headline, origin)
        is FidoPayload -> passkey(env.context, payload, headline, origin, scannedAt)
        is BoardingPassPayload -> boardingPass(env.context, payload, scannedAt)
        is OtpPayload -> otp(env, payload, origin)
        is EsimPayload -> esim(env.context, payload, headline, origin)
        is DppPayload -> dpp(env.context, payload, headline, origin)
        is WifiPayload -> wifi(env.context, payload, headline)
        is ContactPayload -> contact(payload, headline)
        is EventPayload -> event(env.context, payload, headline)
        is TextPayload -> text(env.context, payload, headline, format)
        is MiniProgramPayload -> miniProgramCode(env, payload, origin)
    }

    /**
     * A mini program code that was read. Its content means nothing outside WeChat, so the card
     * shows the code drawn again from it. With the module, WeChat opens the mini program from what
     * was read, as after a scan of its own; without it, the user scans the code again in WeChat.
     */
    private fun miniProgramCode(env: FerryEnv, payload: MiniProgramPayload, origin: Origin): ResultCard {
        val context = env.context
        val image = miniCodeDrawing(payload.raw)
        val wechat = ActiveFerries.WECHAT
        val actions = buildList {
            if (image != null) {
                targetAction(env, wechat, origin) { WeChat.openMiniProgram(it, payload.raw, image) }?.let { add(it) }
            }
            add(copy(payload.raw))
        }
        return ResultCard(
            raw = payload.raw,
            kind = MINI_PROGRAM_KIND.label,
            icon = MINI_PROGRAM_KIND.icon,
            headline = context.getString(R.string.mini_read_headline),
            body = context.getString(R.string.mini_read_body),
            notes = if (wechat.installed(env)) listOfNotNull(targetNote(env, wechat, origin))
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
    internal fun targetAction(env: FerryEnv, target: Ferry, origin: Origin, open: (Activity) -> Boolean): CardAction? {
        if (!target.installed(env)) return null
        val label = when {
            target.isReady(env) -> R.string.open_in_app
            origin == Origin.CAMERA -> R.string.scan_again_in_app
            origin == Origin.IMAGE && target.album -> R.string.open_app_scanner
            else -> return null
        }
        return CardAction(label, R.drawable.ic_open_in_new, target.pkg, target.label(env.context)) {
            if (open(it)) ActionResult.Handoff else ActionResult.Unavailable
        }
    }

    /** The actions that hand [text] to each of [ferries], in their order. */
    internal fun ferryActions(env: FerryEnv, ferries: List<Ferry>, origin: Origin, text: String): List<CardAction> =
        ferries.mapNotNull { ferry -> targetAction(env, ferry, origin) { ferry.open(it, text) } }

    /**
     * That [owner] is needed but missing, for a code only its own app opens. The other ways in stay
     * on the card, though they can only show such a link as a page.
     */
    internal fun missingNote(env: FerryEnv, owner: ActiveFerry): String? = if (owner.exclusive && !owner.installed(env)) {
        env.context.getString(R.string.exclusive_not_installed, owner.label(env.context))
    } else {
        null
    }

    /** What the user has to do in an app waiting for the module, beyond what its action says. */
    internal fun targetNote(env: FerryEnv, target: Ferry, origin: Origin): String? {
        if (target.isReady(env) || !target.installed(env)) return null
        val context = env.context
        val name = target.label(context)
        return when {
            origin == Origin.CAMERA -> null
            origin == Origin.IMAGE && target.album -> context.getString(R.string.pick_from_album_in_app, name)
            else -> context.getString(R.string.needs_module_in_app, name)
        }
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

    internal fun copy(text: String) = CardAction(R.string.copy, R.drawable.ic_content_copy) {
        Launch.copy(it, text)
        ActionResult.Copied
    }

    internal fun share(text: String) = CardAction(R.string.share, R.drawable.ic_share) {
        if (Launch.share(it, text)) ActionResult.Done else ActionResult.Unavailable
    }
}
