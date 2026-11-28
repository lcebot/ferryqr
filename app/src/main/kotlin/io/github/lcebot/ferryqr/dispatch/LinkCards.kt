package io.github.lcebot.ferryqr.dispatch

import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.SchemePayload
import io.github.lcebot.ferryqr.content.WebPayload
import io.github.lcebot.ferryqr.content.sensitiveKind

private val IP_LITERAL = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$|^\\[.*]$")

/**
 * An app link, offered to its apps as [Ferries.rank] orders them: the app it belongs to when
 * FerryQR has its own way in, and the app that registers the scheme. The general apps are not
 * offered, since they open web links rather than other apps' schemes. A link named for whom or what
 * it is about shows the link itself below that name.
 */
internal fun Cards.scheme(env: FerryEnv, payload: SchemePayload, names: ContentNames, origin: Origin): CardBody {
    val context = env.context
    val owner = ActiveFerries.claimant(payload)
    val ranked = env.rank(payload)
    val opens = Intents.scheme(payload.uri, payload.scheme)?.let { env.resolved(it).matches.isNotEmpty() } == true
    val notes = listOfNotNull(
        context.getString(R.string.scheme_unhandled).takeUnless { opens },
        owner?.takeIf { it in ranked }?.let { targetNote(env, it, origin) },
        owner?.let { missingNote(env, it) },
    )
    val actions = ferryActions(env, ranked, origin, payload.raw) +
        copyAction(payload.raw, payload.sensitiveKind != null) + share(payload.raw)
    return CardBody(body = payload.raw.takeIf { names.carried.name != null }, notes = notes, actions = actions)
}

/**
 * A web link. The apps it goes to before any browser lead, as [Ferries.rank] orders them,
 * followed by the browser and then the general apps that open any link.
 */
internal fun Cards.web(env: FerryEnv, payload: WebPayload, origin: Origin): CardBody {
    val context = env.context
    val host = payload.host
    // A link printed without its scheme belongs to its app by its URL, and is handed over as printed.
    val owner = ActiveFerries.claimant(payload)
    val ranked = env.rank(payload)
    val notes = buildList {
        if (payload.url.startsWith("http:", ignoreCase = true)) add(context.getString(R.string.warn_http))
        if (host.split('.').any { it.startsWith("xn--") } || host.any { it.code > 127 }) add(context.getString(R.string.warn_idn))
        if (IP_LITERAL.matches(host)) add(context.getString(R.string.warn_ip))
        // Only a code that belongs to an app needs that app; for other links it is one more
        // option. Nor does it once the system opens the link in that app directly instead.
        owner?.takeIf { it in ranked }?.let { targetNote(env, it, origin) }?.let { add(it) }
        owner?.let { missingNote(env, it) }?.let { add(it) }
    }
    val general = Ferries.general(payload, ranked)
    val browser = CardAction(R.string.open, R.drawable.ic_open_in_new, opens = true) {
        if (Launch.browser(it, payload.url)) ActionResult.Handoff else ActionResult.Unavailable
    }
    val actions = ferryActions(env, ranked, origin, payload.raw) + browser +
        ferryActions(env, general, origin, payload.raw) + copyAction(payload.raw, payload.sensitiveKind != null) + share(payload.raw)
    return CardBody(body = payload.url, notes = notes, actions = actions)
}
