package io.github.lcebot.ferryqr.content

internal object ContactParser {

    fun meCard(text: String): Contact {
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()
        var name: String? = null
        var org: String? = null
        var address: String? = null
        var url: String? = null
        var note: String? = null
        for ((key, value) in Fields.pairs(text.substringAfter(':'))) {
            when (key) {
                "N" -> {
                    val parts = Fields.split(value, ',').map { Fields.unescape(it) }
                    name = joinName(given = parts.getOrNull(1), family = parts.getOrNull(0))
                }
                "TEL" -> phones += Fields.unescape(value).trim()
                "EMAIL" -> emails += Fields.unescape(value).trim()
                "ORG" -> org = Fields.unescape(value).trim()
                "ADR" -> address = Fields.unescape(value).trim()
                "URL" -> url = Fields.unescape(value).trim()
                "NOTE" -> note = Fields.unescape(value).trim()
            }
        }
        return Contact(name, phones.filter { it.isNotEmpty() }, emails.filter { it.isNotEmpty() }, org, null, address, url, note)
    }

    fun vCard(text: String): Contact {
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()
        var formatted: String? = null
        var structured: String? = null
        var org: String? = null
        var title: String? = null
        var address: String? = null
        var url: String? = null
        var note: String? = null
        for (line in Fields.unfold(text)) {
            val colon = Fields.indexOfUnescaped(line, ':')
            if (colon <= 0) continue
            // "item1.EMAIL;TYPE=work" names the property EMAIL.
            val property = line.substring(0, colon).substringBefore(';').substringAfterLast('.').uppercase()
            // Decoded after the structure is split, so an encoded ";" or "\" stays text.
            val raw = line.substring(colon + 1)
            val params = Fields.parameters(line)
            fun plain() = Fields.unescape(Fields.decode(raw, params), vCard = true).trim()
            fun components() = Fields.split(raw, ';').map { Fields.unescape(Fields.decode(it, params), vCard = true).trim() }
            when (property) {
                "FN" -> formatted = plain()
                "N" -> components().let { structured = joinName(given = it.getOrNull(1), family = it.getOrNull(0)) }
                "TEL" -> phones += plain().removePrefix("tel:")
                "EMAIL" -> emails += plain()
                "ORG" -> org = components().filter { it.isNotEmpty() }.joinToString(" ")
                "TITLE" -> title = plain()
                "ADR" -> address = components().filter { it.isNotEmpty() }.joinToString(", ")
                "URL" -> url = plain()
                "NOTE" -> note = plain()
            }
        }
        return Contact(
            name = formatted?.takeIf { it.isNotEmpty() } ?: structured,
            phones = phones.filter { it.isNotEmpty() },
            emails = emails.filter { it.isNotEmpty() },
            organization = org?.takeIf { it.isNotEmpty() },
            title = title?.takeIf { it.isNotEmpty() },
            address = address?.takeIf { it.isNotEmpty() },
            url = url?.takeIf { it.isNotEmpty() },
            note = note?.takeIf { it.isNotEmpty() },
        )
    }
}
