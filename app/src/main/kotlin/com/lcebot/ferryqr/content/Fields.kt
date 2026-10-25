package com.lcebot.ferryqr.content

/** Backslash-escaped field syntax shared by WIFI:, MECARD:, MATMSG: and vCard values. */
internal object Fields {

    fun split(body: String, separator: Char): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                current.append(c).append(body[i + 1])
                i += 2
                continue
            }
            if (c == separator) {
                out += current.toString()
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }

    fun indexOfUnescaped(s: String, target: Char): Int {
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                target -> return i
                else -> i++
            }
        }
        return -1
    }

    /** vCard additionally encodes line breaks as `\n`. */
    fun unescape(s: String, vCard: Boolean = false): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                out.append(if (vCard && (next == 'n' || next == 'N')) '\n' else next)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** `KEY:value;KEY:value;;` with values left escaped, keys upper-cased. */
    fun pairs(body: String): List<Pair<String, String>> = split(body, ';').mapNotNull { field ->
        val colon = indexOfUnescaped(field, ':')
        if (colon <= 0) null
        else field.substring(0, colon).trim().uppercase() to field.substring(colon + 1)
    }

    /** RFC 5545 / vCard line unfolding: a line starting with whitespace continues the previous one. */
    fun unfold(raw: String): List<String> {
        val lines = ArrayList<String>()
        for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((line.startsWith(' ') || line.startsWith('\t')) && lines.isNotEmpty()) {
                lines[lines.lastIndex] = lines.last() + line.substring(1)
            } else {
                lines += line
            }
        }
        return lines
    }
}

/** Western names read given-first; CJK names keep family-first without a space. */
internal fun joinName(given: String?, family: String?): String? {
    val g = given?.trim().orEmpty()
    val f = family?.trim().orEmpty()
    return when {
        g.isEmpty() && f.isEmpty() -> null
        g.isEmpty() -> f
        f.isEmpty() -> g
        (g + f).all { it.code < 0x2E80 } -> "$g $f"
        else -> f + g
    }
}
