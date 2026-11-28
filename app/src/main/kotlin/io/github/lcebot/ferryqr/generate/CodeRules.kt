package io.github.lcebot.ferryqr.generate

import androidx.annotation.StringRes
import com.google.zxing.oned.UPCEReader
import io.github.lcebot.ferryqr.R

/** Why a format cannot hold some text, for the create page and the format picker to explain. */
sealed interface CodeProblem {
    data object TooLong : CodeProblem

    data object DigitsOnly : CodeProblem

    /** [requirement] names the digit counts the format takes, such as 12 or 13. */
    data class DigitCount(@param:StringRes val requirement: Int) : CodeProblem

    /** The last digit is a check digit, and [expected] is the one the other digits call for. */
    data class CheckDigit(val expected: Char) : CodeProblem

    data object EvenDigits : CodeProblem

    data object Characters : CodeProblem

    /** Any other rule of the format, such as the number systems UPC-E allows. */
    data object Rules : CodeProblem
}

/** Beyond this many UTF-8 bytes no format can hold the text, so encoding is not attempted. */
private const val MAX_BYTES = 3200

/** Barcodes longer than this many characters are too wide to scan from a screen. */
private const val LINEAR_MAX = 80

/** The characters Codabar holds between its start and stop characters. */
private const val CODABAR_BODY = "0123456789-$:/.+"
private const val CODABAR_GUARDS = "ABCD"

/**
 * The characters Code 39 holds in its standard form. Any other character would take its extended
 * form, which writes it as a pair of these, and readers in standard mode, as most handheld and till
 * scanners are, show that pair rather than the character.
 */
private const val CODE_39_CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ -.\$/+%"

/**
 * What each format accepts, checked before encoding so that the reason a text does not fit can be
 * named. Matrix codes take any text up to their capacity, which only encoding tells.
 */
object CodeRules {

    fun problem(text: String, type: CodeType): CodeProblem? {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return CodeProblem.TooLong
        if (type.linear && text.length > LINEAR_MAX) return CodeProblem.TooLong
        return when (type) {
            CodeType.EAN_13 -> gtin(text, 12, R.string.format_needs_12_13)
            CodeType.EAN_8 -> gtin(text, 7, R.string.format_needs_7_8)
            CodeType.UPC_A -> gtin(text, 11, R.string.format_needs_11_12)
            CodeType.UPC_E -> when {
                !digits(text) -> CodeProblem.DigitsOnly
                text.length !in 7..8 -> CodeProblem.DigitCount(R.string.format_needs_7_8)
                text[0] != '0' && text[0] != '1' -> CodeProblem.Rules
                // The check digit is that of the UPC-A code a UPC-E code stands for.
                text.length == 8 -> checkDigit(UPCEReader.convertUPCEtoUPCA(text.take(7)))
                    .takeIf { it != text[7] }?.let { CodeProblem.CheckDigit(it) }
                else -> null
            }
            CodeType.ITF -> when {
                !digits(text) -> CodeProblem.DigitsOnly
                text.length % 2 != 0 -> CodeProblem.EvenDigits
                else -> null
            }
            CodeType.CODABAR -> if (codabar(text)) null else CodeProblem.Characters
            CodeType.CODE_39 -> if (text.all { it in CODE_39_CHARACTERS }) null else CodeProblem.Characters
            CodeType.CODE_128, CodeType.CODE_93 ->
                if (text.all { it.code < 128 }) null else CodeProblem.Characters
            CodeType.QR, CodeType.DATA_MATRIX, CodeType.AZTEC, CodeType.PDF417 -> null
        }
    }

    /** The reason to give when a text passed the rules above but still could not be encoded. */
    fun unencodable(type: CodeType): CodeProblem = if (type.linear) CodeProblem.Rules else CodeProblem.TooLong

    /** Whether [text] is a GTIN of [length] digits plus its check digit, as EAN and UPC codes carry. */
    fun validGtin(text: String, length: Int): Boolean =
        text.length == length + 1 && digits(text) && text.last() == checkDigit(text.dropLast(1))

    /** The GS1 check digit: weights of 3 and 1 alternate from the rightmost digit, to a multiple of ten. */
    fun checkDigit(digits: String): Char {
        var sum = 0
        for ((i, c) in digits.reversed().withIndex()) sum += (c - '0') * if (i % 2 == 0) 3 else 1
        return '0' + (10 - sum % 10) % 10
    }

    /** A GTIN given with or without its check digit; with it, the digit must be right. */
    private fun gtin(text: String, length: Int, @StringRes requirement: Int): CodeProblem? = when {
        !digits(text) -> CodeProblem.DigitsOnly
        text.length != length && text.length != length + 1 -> CodeProblem.DigitCount(requirement)
        text.length == length + 1 && !validGtin(text, length) -> CodeProblem.CheckDigit(checkDigit(text.dropLast(1)))
        else -> null
    }

    private fun digits(text: String) = text.isNotEmpty() && text.all { it in '0'..'9' }

    /** Codabar's body characters, optionally between a start and a stop character. */
    private fun codabar(text: String): Boolean {
        if (text.isEmpty()) return false
        val guarded = text.length >= 2 && text.first().uppercaseChar() in CODABAR_GUARDS && text.last().uppercaseChar() in CODABAR_GUARDS
        val body = if (guarded) text.substring(1, text.length - 1) else text
        return body.isNotEmpty() && body.all { it in CODABAR_BODY }
    }
}
