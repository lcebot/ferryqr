package io.github.lcebot.ferryqr.content

import java.net.URLDecoder

/**
 * Reads `otpauth://TYPE/LABEL?PARAMETERS`, the key URI format Google Authenticator defined and
 * every authenticator follows. Links in the wild stray from it, so this accepts what common
 * authenticators accept: any case in the scheme and type, the label's issuer split off at its first
 * colon, encoded or not, and a secret in lower case, padded, or broken up by spaces or hyphens. A
 * link without a usable secret, or an HOTP link without its counter, adds no key and is left to be
 * read as an ordinary link.
 */
internal object OtpParser {
    private const val PREFIX = "otpauth://"
    private val BASE32 = Regex("^[A-Z2-7]+$")
    private val COLON = Regex(":|%3A", RegexOption.IGNORE_CASE)

    fun parse(text: String): OtpKey? {
        if (!text.startsWith(PREFIX, ignoreCase = true)) return null
        val rest = text.substring(PREFIX.length)
        val type = rest.substringBefore('/', "").lowercase().takeIf { it.isNotEmpty() } ?: return null
        val path = rest.substringAfter('/')
        val params = parameters(path.substringAfter('?', ""))
        val secret = params["secret"]?.uppercase()?.filterNot { it == ' ' || it == '-' }?.trimEnd('=')
            ?.takeIf { BASE32.matches(it) } ?: return null
        val counter = params["counter"]?.trim()?.toLongOrNull()
        if (type == "hotp" && counter == null) return null

        // The first colon, encoded or not, ends the issuer; a later one belongs to the account.
        val label = path.substringBefore('?')
        val colon = COLON.find(label)
        val labelIssuer = colon?.let { decodeLabel(label.substring(0, it.range.first)).trim() }
        val account = decodeLabel(if (colon == null) label else label.substring(colon.range.last + 1)).trim()
        return OtpKey(
            type = type,
            // The parameter is what authenticators match on; the label's prefix only names it.
            issuer = params["issuer"]?.trim()?.takeIf { it.isNotEmpty() } ?: labelIssuer?.takeIf { it.isNotEmpty() },
            account = account.takeIf { it.isNotEmpty() },
            secret = secret,
            algorithm = params["algorithm"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            digits = params["digits"]?.trim()?.toIntOrNull(),
            period = params["period"]?.trim()?.toIntOrNull(),
            counter = counter,
        )
    }

    /** Query parameters by lowercase name, the first of any repeated one, decoded as forms encode them. */
    private fun parameters(query: String): Map<String, String> = buildMap {
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val name = decode(pair.substringBefore('='), plusIsSpace = true).lowercase()
            if (name !in this) put(name, decode(pair.substringAfter('=', ""), plusIsSpace = true))
        }
    }

    /** In the label, the path of the link, a plus is a plus rather than a space. */
    private fun decodeLabel(text: String) = decode(text, plusIsSpace = false)

    /** Percent-decoded UTF-8; malformed escapes leave the text as it was. */
    private fun decode(text: String, plusIsSpace: Boolean): String = try {
        URLDecoder.decode(if (plusIsSpace) text else text.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        text
    }
}
