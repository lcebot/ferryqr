package io.github.lcebot.ferryqr.generate

import io.github.lcebot.ferryqr.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Check digits come from real codes: an EAN-13 product, a UPC-A product and an ISBN. */
class FormatRulesTest {

    @Test
    fun computesGs1CheckDigits() {
        assertEquals('1', CodeRules.checkDigit("400638133393"))
        assertEquals('2', CodeRules.checkDigit("03600029145"))
        assertEquals('7', CodeRules.checkDigit("978030640615"))
    }

    @Test
    fun namesWhyAProductCodeDoesNotFit() {
        assertNull(CodeRules.problem("4006381333931", CodeType.EAN_13))
        assertNull(CodeRules.problem("400638133393", CodeType.EAN_13))
        assertEquals(CodeProblem.CheckDigit('1'), CodeRules.problem("4006381333930", CodeType.EAN_13))
        assertEquals(CodeProblem.DigitCount(R.string.format_needs_12_13), CodeRules.problem("12345", CodeType.EAN_13))
        assertEquals(CodeProblem.DigitsOnly, CodeRules.problem("ABC", CodeType.UPC_A))
        assertEquals(CodeProblem.EvenDigits, CodeRules.problem("123", CodeType.ITF))
        assertEquals(CodeProblem.Rules, CodeRules.problem("2123456", CodeType.UPC_E))
    }

    @Test
    fun barcodesTakeOnlyTheirCharacters() {
        assertNull(CodeRules.problem("Hello, world", CodeType.CODE_128))
        assertEquals(CodeProblem.Characters, CodeRules.problem("你好", CodeType.CODE_128))
        assertNull(CodeRules.problem("A1234-5678B", CodeType.CODABAR))
        assertEquals(CodeProblem.Characters, CodeRules.problem("12AB34", CodeType.CODABAR))
        assertEquals(CodeProblem.TooLong, CodeRules.problem("1".repeat(81), CodeType.CODE_128))
        assertNull(CodeRules.problem("你好", CodeType.QR))
    }

    @Test
    fun picksAFormatOnlyWhenTheContentPlainlyBelongsToIt() {
        assertEquals(CodeType.QR, AutoFormat.pick(""))
        assertEquals(CodeType.QR, AutoFormat.pick("https://example.com"))
        assertEquals(CodeType.EAN_13, AutoFormat.pick("4006381333931"))
        assertEquals(CodeType.EAN_13, AutoFormat.pick("9780306406157"))
        assertEquals(CodeType.UPC_A, AutoFormat.pick("036000291452"))
        // A wrong check digit is just a number.
        assertEquals(CodeType.QR, AutoFormat.pick("4006381333930"))
        // Eight digits, such as a date, stay a QR code.
        assertEquals(CodeType.QR, AutoFormat.pick("20261116"))
        assertEquals(CodeType.PDF417, AutoFormat.pick("M1SAMPLE/ALEX         EXYZ789 LHRJFKXX 0123 275Y014C0042 100"))
    }

    @Test
    fun mapsReaderNamesToFormats() {
        assertEquals(CodeType.PDF417, CodeType.ofReaderName("PDF_417"))
        assertEquals(CodeType.CODE_39, CodeType.ofReaderName("CODE_39_EXT"))
        assertEquals(CodeType.ITF, CodeType.ofReaderName("ITF_14"))
        assertNull(CodeType.ofReaderName("MAXICODE"))
    }
}
