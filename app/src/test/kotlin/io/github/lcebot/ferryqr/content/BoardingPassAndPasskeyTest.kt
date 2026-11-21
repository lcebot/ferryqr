package io.github.lcebot.ferryqr.content

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sample pass follows the field widths of IATA's implementation guide; the passkey codes were encoded as CTAP 2.2 does. */
class BoardingPassAndPasskeyTest {

    private val sample = "M1SAMPLE/ALEX         EXYZ789 LHRJFKXX 0123 275Y014C0042 100"

    @Test
    fun readsTheMandatoryFields() {
        val pass = BoardingPassParser.parse(sample)!!
        assertEquals("SAMPLE/ALEX", pass.passenger)
        assertTrue(pass.complete)
        val leg = pass.legs.single()
        assertEquals("XYZ789", leg.bookingReference)
        assertEquals("LHR", leg.from)
        assertEquals("JFK", leg.to)
        assertEquals("XX123", leg.flight)
        assertEquals("275", leg.dayOfYear)
        assertEquals("Y", leg.compartment)
        assertEquals("14C", leg.shownSeat)
        assertEquals("42", leg.shownSequence)
    }

    @Test
    fun classifiesPassesBeforeTrimming() {
        assertIs<BoardingPassPayload>(Classifier.classify(sample))
        assertNull(BoardingPassParser.parse("M1 not a boarding pass"))
    }

    @Test
    fun keepsWhatWasReadWhenALaterPartIsCutOff() {
        // Two flights are announced but only one follows.
        val pass = BoardingPassParser.parse("M2" + sample.substring(2))!!
        assertEquals(1, pass.legs.size)
        assertFalse(pass.complete)
    }

    @Test
    fun marksAnOptionalLengthThatRunsPastTheEnd() {
        val pass = BoardingPassParser.parse(sample.dropLast(2) + "1F")!!
        assertEquals(1, pass.legs.size)
        assertFalse(pass.complete)
    }

    @Test
    fun datesFlightsInTheYearClosestToTheScan() {
        val midYear = LocalDate.of(2026, 6, 15)
        assertEquals(LocalDate.of(2026, 10, 2), BoardingPassParser.flightDate(275, midYear))
        // Late December, a flight on day 3 is in the new year.
        assertEquals(LocalDate.of(2027, 1, 3), BoardingPassParser.flightDate(3, LocalDate.of(2026, 12, 30)))
        // Early January, a flight on day 364 was in the old year.
        assertEquals(LocalDate.of(2025, 12, 30), BoardingPassParser.flightDate(364, LocalDate.of(2026, 1, 2)))
        // Day 60 is February 29 in a leap year and March 1 otherwise.
        assertEquals(LocalDate.of(2028, 2, 29), BoardingPassParser.flightDate(60, LocalDate.of(2028, 2, 20)))
        assertEquals(LocalDate.of(2026, 3, 1), BoardingPassParser.flightDate(60, LocalDate.of(2026, 2, 20)))
        assertNull(BoardingPassParser.flightDate(400, midYear))
    }

    @Test
    fun readsWhenAPasskeyCodeWasMade() {
        assertEquals(1_700_000_000L, PasskeyQr.createdAt("FIDO:/073216565403977300015815525"))
    }

    /**
     * The codes hold the CBOR maps {3: 1700000000, 5: "ga"}, the same with "mc", with "dcp", with "mc"
     * as a byte string as the specification's example writes it, and the time alone.
     */
    @Test
    fun readsWhatAPasskeyCodeAsksFor() {
        assertEquals(PasskeyOperation.SIGN_IN, PasskeyQr.operation("FIDO:/002653404807832661634165253"))
        assertEquals(PasskeyOperation.CREATE, PasskeyQr.operation("FIDO:/002653404807832661668112901"))
        assertEquals(PasskeyOperation.PRESENT_CREDENTIAL, PasskeyQr.operation("FIDO:/002653404807832660482703852293"))
        assertEquals(PasskeyOperation.CREATE, PasskeyQr.operation("FIDO:/002653404807832661668104709"))
        assertEquals(1_700_000_000L, PasskeyQr.createdAt("FIDO:/002653404807832661634165253"))
        assertNull(PasskeyQr.operation("FIDO:/00265340480783265"))
    }

    @Test
    fun passkeyCodesWithoutATimeOrWithBadDigitsHaveNone() {
        assertNull(PasskeyQr.createdAt("FIDO:/0418346304929"))
        assertNull(PasskeyQr.createdAt("FIDO:/12"))
        assertNull(PasskeyQr.createdAt("FIDO:/abc"))
        assertNull(PasskeyQr.createdAt("https://example.com"))
    }
}
