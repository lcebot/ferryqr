package io.github.lcebot.ferryqr.content

import java.time.LocalDate
import java.time.Year
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Reads IATA's Bar Coded Boarding Pass (Resolution 792), the content of every airline boarding pass
 * whatever its symbology. Its first 60 characters have fixed fields: a format code M, the number of
 * flights, the passenger's name and an e-ticket mark, then the first flight's booking reference,
 * airports, carrier, flight number, day, class, seat, sequence number and status, and the length
 * of the optional data that follows. Each further flight repeats the flight fields.
 */
internal object BoardingPassParser {

    private val MANDATORY = Regex("^M[1-4].{20}[E ].{7}[A-Z]{3}[A-Z]{3}.{3}[0-9 ]{4}[A-Z ][0-9]{3}[A-Z].{4}.{5}.[0-9A-Fa-f]{2}")

    private const val HEADER = 23
    private const val LEG = 37

    /** Whether [text] begins the way every boarding pass does. */
    fun looksLike(text: String): Boolean = MANDATORY.containsMatchIn(text)

    fun parse(raw: String): BoardingPass? {
        if (!looksLike(raw)) return null
        val count = raw[1] - '0'
        val passenger = raw.substring(2, 22).trim()
        val legs = mutableListOf<FlightLeg>()
        var frequentFlyer: String? = null
        var bagTag: String? = null
        var complete = true
        var position = HEADER
        for (index in 0 until count) {
            if (raw.length < position + LEG) {
                complete = false
                break
            }
            val leg = raw.substring(position, position + LEG)
            legs += FlightLeg(
                bookingReference = leg.substring(0, 7).trim(),
                from = leg.substring(7, 10).trim(),
                to = leg.substring(10, 13).trim(),
                carrier = leg.substring(13, 16).trim(),
                flightNumber = leg.substring(16, 21).trim(),
                dayOfYear = leg.substring(21, 24).trim(),
                compartment = leg.substring(24, 25).trim(),
                seat = leg.substring(25, 29).trim(),
                sequence = leg.substring(29, 34).trim(),
            )
            position += LEG
            val size = leg.substring(35, 37).toIntOrNull(16)
            if (size == null || raw.length < position + size) {
                complete = false
                break
            }
            val optional = readOptional(raw.substring(position, position + size), first = index == 0)
            position += size
            if (optional == null) {
                complete = false
                break
            }
            frequentFlyer = frequentFlyer ?: optional.frequentFlyer
            bagTag = bagTag ?: optional.bagTag
        }
        return BoardingPass(passenger, legs, frequentFlyer, bagTag, complete)
    }

    private class Optional(val frequentFlyer: String?, val bagTag: String?)

    /**
     * The optional data of one flight; null when its lengths do not add up. The first flight's data
     * may begin with a version mark and the items that occur once per pass, among them the bag tag;
     * every flight's data may then hold the items repeated per flight, among them the frequent flyer
     * number. Data past those items is each airline's own and is left alone.
     */
    private fun readOptional(data: String, first: Boolean): Optional? {
        var position = 0
        var bagTag: String? = null
        if (first && data.startsWith('>')) {
            val uniqueSize = data.slice(2, 4)?.toIntOrNull(16) ?: return null
            val unique = data.slice(4, 4 + uniqueSize) ?: return null
            bagTag = unique.slice(11, 24)?.trim()?.takeIf { tag -> tag.any { it != '0' } }
            position = 4 + uniqueSize
        }
        if (data.length < position + 2) return Optional(null, bagTag)
        // Airline data that does not start with a length is not the repeated items, and is left alone.
        val repeatedSize = data.slice(position, position + 2)?.toIntOrNull(16) ?: return Optional(null, bagTag)
        val repeated = data.slice(position + 2, position + 2 + repeatedSize) ?: return null
        val airline = repeated.slice(18, 21)?.trim().orEmpty()
        val number = repeated.slice(21, 37)?.trim().orEmpty()
        val frequentFlyer = if (number.isEmpty()) null else listOf(airline, number).filter { it.isNotEmpty() }.joinToString(" ")
        return Optional(frequentFlyer, bagTag)
    }

    /**
     * The date of a flight given as [dayOfYear], in whichever of the year before, the year of and the
     * year after [scanned] puts it closest to the scan, so a pass scanned at the turn of the year
     * still lands on the right one. Each year counts its own leap day. Null for a day no year has.
     */
    fun flightDate(dayOfYear: Int, scanned: LocalDate): LocalDate? =
        (scanned.year - 1..scanned.year + 1)
            .filter { dayOfYear in 1..Year.of(it).length() }
            .map { LocalDate.ofYearDay(it, dayOfYear) }
            .minByOrNull { abs(ChronoUnit.DAYS.between(scanned, it)) }

    /** [start] to [end] of this text, or null when the text is shorter. */
    private fun String.slice(start: Int, end: Int): String? = if (end <= length && start <= end) substring(start, end) else null
}
