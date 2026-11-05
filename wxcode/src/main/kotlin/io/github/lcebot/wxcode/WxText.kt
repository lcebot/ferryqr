package io.github.lcebot.wxcode

import java.math.BigInteger

/**
 * The text WeChat's own decoder reports for a mini program code, which is what its servers resolve.
 * The payload of the code is QR alphanumeric text, read as one number in base 45, and WeChat writes
 * that number again with 82 characters that are safe in URLs. Leading zero digits stay zero digits,
 * one for one, as in Base58, so a payload and its report have the same leading zeros.
 */
object WxText {

    private const val URL_SAFE = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!#\$&'()*+,/:;=?@-._~"

    private val BASE_IN = BigInteger.valueOf(ALPHANUMERIC.length.toLong())
    private val BASE_OUT = BigInteger.valueOf(URL_SAFE.length.toLong())

    /**
     * WeChat's report of [payload], the text a mini program code holds. A payload with any character
     * outside the QR alphanumeric set is reported as it is, since WeChat converts only payloads that
     * are wholly alphanumeric.
     */
    fun forWeChat(payload: String): String {
        if (payload.isEmpty() || payload.any { it !in ALPHANUMERIC }) return payload
        val zeros = payload.takeWhile { it == ALPHANUMERIC[0] }.length
        var n = payload.fold(BigInteger.ZERO) { acc, c -> acc * BASE_IN + BigInteger.valueOf(ALPHANUMERIC.indexOf(c).toLong()) }
        val digits = StringBuilder()
        while (n.signum() > 0) {
            val (quotient, remainder) = n.divideAndRemainder(BASE_OUT)
            digits.append(URL_SAFE[remainder.toInt()])
            n = quotient
        }
        return URL_SAFE[0].toString().repeat(zeros) + digits.reverse()
    }
}
