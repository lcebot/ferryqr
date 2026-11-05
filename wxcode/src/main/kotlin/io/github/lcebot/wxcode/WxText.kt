package io.github.lcebot.wxcode

/**
 * The text WeChat's own decoder reports for a mini program code, which is what its servers resolve.
 * The code holds QR alphanumeric text, which WeChat rewrites with 82 characters that are safe in
 * URLs, through bytes: [toBytes] in base 45, then [fromBytes] in base 82. This is not a big-integer
 * base change. Both steps stream the digits through a small accumulator, as WeChat's QBar library
 * does, and only the same steps give the same text.
 */
object WxText {

    private const val URL_SAFE = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!#\$&'()*+,/:;=?@-._~"

    /**
     * WeChat's report of [payload], the text a mini program code holds. A payload with any character
     * outside the QR alphanumeric set is reported as it is, since WeChat converts only payloads that
     * are wholly alphanumeric.
     */
    fun forWeChat(payload: String): String {
        if (payload.isEmpty() || payload.any { it !in ALPHANUMERIC }) return payload
        return fromBytes(toBytes(payload, ALPHANUMERIC) ?: return payload, URL_SAFE)
    }

    /**
     * Each character becomes its index in [alphabet]. Leading zero digits are only counted; every
     * other digit is folded into an accumulator that gives up its low byte whenever it reaches 256.
     * The bytes, then the count of leading zeros, come out in reverse order. Null when there are
     * more leading zeros than one byte counts.
     */
    private fun toBytes(text: String, alphabet: String): IntArray? {
        val base = alphabet.length
        val bytes = ArrayList<Int>()
        var acc = 0
        var zeros = 0
        var started = false
        for (c in text) {
            val digit = alphabet.indexOf(c)
            if (!started && digit == 0) {
                zeros++
                continue
            }
            started = true
            acc = acc * base + digit
            while (acc >= 256) {
                bytes += acc and 0xFF
                acc = acc ushr 8
            }
        }
        if (zeros > 255) return null
        if (acc != 0) bytes += acc
        bytes += zeros
        return bytes.reversed().toIntArray()
    }

    /**
     * The reverse of [toBytes] in another base: the first byte counts the leading zeros, and the
     * rest are carried through a small remainder, giving up a digit in [alphabet]'s base whenever
     * the remainder reaches it. The digits, then the zeros, come out in reverse order.
     */
    private fun fromBytes(bytes: IntArray, alphabet: String): String {
        val base = alphabet.length
        val out = StringBuilder()
        if (bytes.size > 1) {
            var carry = bytes[1]
            for (i in 1 until bytes.size) {
                var value = if (i == 1) carry else bytes[i] + (carry shl 8)
                while (value >= base) {
                    out.append(alphabet[value % base])
                    value /= base
                }
                carry = value
            }
            if (carry != 0) out.append(alphabet[carry])
        }
        repeat(bytes.firstOrNull() ?: 0) { out.append(alphabet[0]) }
        return out.reverse().toString()
    }
}
