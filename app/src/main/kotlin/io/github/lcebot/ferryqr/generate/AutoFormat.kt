package io.github.lcebot.ferryqr.generate

import io.github.lcebot.ferryqr.content.BoardingPassParser

/**
 * The format the create page picks from the content: one only when the content plainly belongs to
 * it, and a QR code otherwise, since that is what almost everything is shared as. A boarding pass
 * goes in a PDF417, the one format every boarding pass reader takes. A 13 or 12 digit number with
 * a valid check digit is a product code, as EAN-13 or UPC-A; ISBNs and ISSNs are EAN-13 too. Numbers
 * that long are rarely anything else, while 8 digit numbers such as dates are common and one in ten
 * of them passes the check, so EAN-8 is left to be chosen by hand.
 */
object AutoFormat {

    fun pick(text: String): CodeType = when {
        BoardingPassParser.looksLike(text) -> CodeType.PDF417
        CodeRules.validGtin(text, 12) -> CodeType.EAN_13
        CodeRules.validGtin(text, 11) -> CodeType.UPC_A
        else -> CodeType.QR
    }
}
