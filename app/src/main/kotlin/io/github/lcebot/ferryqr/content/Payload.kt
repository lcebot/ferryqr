package io.github.lcebot.ferryqr.content

sealed interface Payload {
    /** The scanned content as shown to the user and copied to the clipboard. */
    val raw: String
}

data class WebPayload(override val raw: String, val url: String, val host: String) : Payload

/** Any other URI scheme, opened through whatever app registers it as browsable. */
data class SchemePayload(override val raw: String, val uri: String, val scheme: String) : Payload

data class FidoPayload(override val raw: String) : Payload

/** An airline boarding pass, in IATA's format, read into [pass]. */
data class BoardingPassPayload(override val raw: String, val pass: BoardingPass) : Payload

data class EsimPayload(override val raw: String) : Payload

data class DppPayload(override val raw: String) : Payload

data class WifiPayload(
    override val raw: String,
    val ssid: String,
    val password: String?,
    val security: WifiSecurity,
    val hidden: Boolean,
) : Payload

data class ContactPayload(override val raw: String, val contact: Contact) : Payload

data class EventPayload(override val raw: String, val event: CalendarEvent) : Payload

data class TextPayload(override val raw: String) : Payload

/** A WeChat mini program code's content, an identifier only WeChat resolves. */
data class MiniProgramPayload(override val raw: String) : Payload

enum class WifiSecurity { OPEN, WEP, WPA, SAE, EAP }

data class Contact(
    val name: String?,
    val phones: List<String>,
    val emails: List<String>,
    val organization: String?,
    val title: String?,
    val address: String?,
    val url: String?,
    val note: String?,
)

/** Times are epoch milliseconds. */
data class CalendarEvent(
    val title: String?,
    val begin: Long?,
    val end: Long?,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
)

/**
 * One flight of a boarding pass, each field as printed, spaces trimmed; a field the pass leaves blank
 * is empty. [dayOfYear] is the flight's date as the day of its year, which the pass gives without
 * the year.
 */
data class FlightLeg(
    val bookingReference: String,
    val from: String,
    val to: String,
    val carrier: String,
    val flightNumber: String,
    val dayOfYear: String,
    val compartment: String,
    val seat: String,
    val sequence: String,
) {
    /** The carrier with the flight number as timetables write it, such as CA123 for CA and 0123. */
    val flight: String get() = carrier + unpadded(flightNumber)

    /** The seat as signs show it, such as 14C for 014C. */
    val shownSeat: String get() = unpadded(seat)

    /** The check-in sequence number without its leading zeros. */
    val shownSequence: String get() = unpadded(sequence)
}

/**
 * The data of a bar coded boarding pass. [complete] is false when a later part could not be read,
 * in which case [legs] holds the flights read before it.
 */
data class BoardingPass(
    val passenger: String,
    val legs: List<FlightLeg>,
    val frequentFlyer: String?,
    val bagTag: String?,
    val complete: Boolean,
)

/** Zeros that only pad a number, which signs and timetables leave out. */
private val PADDING = Regex("^0+(?=\\d)")

private fun unpadded(value: String) = PADDING.replace(value, "")
