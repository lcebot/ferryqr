package com.lcebot.ferryqr.content

import java.net.URLEncoder

object Classifier {

    /** GSMA activation code without the `LPA:` prefix: `1$SM-DP+ address$matching ID`. */
    private val ESIM_BARE = Regex("^1\\$[^$\\s]+\\$\\S*$")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):(.+)$", RegexOption.DOT_MATCHES_ALL)
    private val SMS_COLON = Regex("^sms:([^:?]+):(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HOST = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://(?:[^@/?#]*@)?(\\[[^\\]]*]|[^:/?#]*)")
    private val BARE_WEB = Regex(
        "^(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}(?::\\d{1,5})?(?:[/?#]\\S*)?$"
    )
    private val WHITESPACE = Regex("\\s")

    /** Schemes that must never be handed to another app from untrusted input. */
    private val BLOCKED_SCHEMES = setOf("file", "content", "javascript", "data", "about", "blob")

    fun classify(raw: String): Payload {
        val text = raw.trim()
        fun has(prefix: String) = text.startsWith(prefix, ignoreCase = true)
        return when {
            text.isEmpty() -> TextPayload(raw)
            has("FIDO:/") -> FidoPayload(text)
            has("LPA:") || ESIM_BARE.matches(text) -> EsimPayload(text)
            has("WIFI:") -> WifiParser.parse(text) ?: TextPayload(text)
            has("DPP:") -> DppPayload(text)
            has("BEGIN:VCARD") -> ContactPayload(text, ContactParser.vCard(text))
            has("MECARD:") -> ContactPayload(text, ContactParser.meCard(text))
            has("BEGIN:VEVENT") || has("BEGIN:VCALENDAR") ->
                EventParser.parse(text)?.let { EventPayload(text, it) } ?: TextPayload(text)
            has("MATMSG:") -> mailto(text)
            has("SMSTO:") -> smsto(text, text.substringAfter(':'))
            SMS_COLON.matches(text) -> smsto(text, text.substringAfter(':'))
            WHITESPACE.containsMatchIn(text) -> TextPayload(text)
            else -> uriLike(text)
        }
    }

    private fun uriLike(text: String): Payload {
        val match = SCHEME.matchEntire(text)
        if (match != null) {
            val scheme = match.groupValues[1].lowercase()
            // "example.com:8080/x" parses as scheme "example.com"; real dotted schemes use "://".
            if ('.' !in scheme || match.groupValues[2].startsWith("//")) {
                return when (scheme) {
                    "http", "https" -> web(text, text) ?: TextPayload(text)
                    in BLOCKED_SCHEMES -> TextPayload(text)
                    else -> SchemePayload(text, text, scheme)
                }
            }
        }
        val looksLikeSite = text.startsWith("www.", ignoreCase = true) || text.any { it == '/' || it == '?' }
        if (looksLikeSite && BARE_WEB.matches(text)) return web(text, "https://$text") ?: TextPayload(text)
        return TextPayload(text)
    }

    private fun web(raw: String, url: String): WebPayload? {
        val host = HOST.find(url)?.groupValues?.get(1)?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return WebPayload(raw, url, host)
    }

    private fun mailto(text: String): Payload {
        val fields = Fields.pairs(text.substringAfter(':')).associate { (k, v) -> k to Fields.unescape(v).trim() }
        val to = fields["TO"].orEmpty()
        val query = listOfNotNull(
            fields["SUB"]?.takeIf { it.isNotEmpty() }?.let { "subject=${encode(it)}" },
            fields["BODY"]?.takeIf { it.isNotEmpty() }?.let { "body=${encode(it)}" },
        ).joinToString("&")
        val uri = "mailto:" + encode(to) + if (query.isEmpty()) "" else "?$query"
        return SchemePayload(text, uri, "mailto")
    }

    /** `SMSTO:number:body`, the non-URI form most generators emit. */
    private fun smsto(text: String, rest: String): Payload {
        val number = rest.substringBefore(':').trim()
        val body = rest.substringAfter(':', "").trim()
        if (number.isEmpty()) return TextPayload(text)
        val uri = "smsto:" + encode(number) + if (body.isEmpty()) "" else "?body=${encode(body)}"
        return SchemePayload(text, uri, "smsto")
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
