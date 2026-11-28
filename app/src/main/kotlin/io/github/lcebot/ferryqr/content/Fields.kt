package io.github.lcebot.ferryqr.content

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

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

    /**
     * RFC 5545 / vCard line unfolding: a line starting with whitespace continues the previous one. So
     * does the line after a quoted-printable value that ends in `=`, a soft line break of vCard 2.1.
     */
    fun unfold(raw: String): List<String> {
        val lines = ArrayList<String>()
        var softBreak = false
        for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            when {
                softBreak -> lines[lines.lastIndex] = lines.last().dropLast(1) + line
                (line.startsWith(' ') || line.startsWith('\t')) && lines.isNotEmpty() ->
                    lines[lines.lastIndex] = lines.last() + line.substring(1)
                else -> lines += line
            }
            val last = lines.last()
            softBreak = last.endsWith('=') && quotedPrintable(parameters(last))
        }
        return lines
    }

    /** The parameters of a content [line], such as `CHARSET=UTF-8`, between its name and its value. */
    fun parameters(line: String): List<String> {
        val colon = indexOfUnescaped(line, ':')
        return if (colon <= 0) emptyList() else line.substring(0, colon).split(';').drop(1)
    }

    /**
     * [value] decoded as its [parameters] say. vCard 2.1 writes a value that is not plain ASCII in
     * quoted-printable, as the bytes of the CHARSET it names, or UTF-8 when it names none; any other
     * value is left as it is.
     */
    fun decode(value: String, parameters: List<String>): String {
        if (!quotedPrintable(parameters)) return value
        val name = parameters.firstOrNull { it.substringBefore('=').trim().equals("CHARSET", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.trim('"')
        val charset = name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val out = StringBuilder(value.length)
        val bytes = ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() == 0) return
            out.append(String(bytes.toByteArray(), charset))
            bytes.reset()
        }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val high = if (c == '=' && i + 2 < value.length) Character.digit(value[i + 1], 16) else -1
            val low = if (high >= 0) Character.digit(value[i + 2], 16) else -1
            when {
                low >= 0 -> {
                    bytes.write(high * 16 + low)
                    i += 3
                }
                // Literal ASCII joins the encoded bytes around it, since in a double-byte charset
                // the second byte of a character may be a printable one.
                c.code < 0x80 -> {
                    bytes.write(c.code)
                    i++
                }
                else -> {
                    flush()
                    out.append(c)
                    i++
                }
            }
        }
        flush()
        return out.toString()
    }

    /** vCard 2.1 marks quoted-printable as `ENCODING=QUOTED-PRINTABLE`, or by the bare parameter. */
    private fun quotedPrintable(parameters: List<String>): Boolean = parameters.any { parameter ->
        val key = parameter.substringBefore('=', "").trim()
        val value = parameter.substringAfter('=').trim()
        (key.isEmpty() || key.equals("ENCODING", ignoreCase = true)) && value.equals("QUOTED-PRINTABLE", ignoreCase = true)
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
