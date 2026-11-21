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

/** A two-factor authentication key in an `otpauth://` link, which authenticator apps add. */
data class OtpPayload(override val raw: String, val key: OtpKey) : Payload

/**
 * What an `otpauth://` link says about a key. [type] is as the link names it, lowercased: `totp`
 * and `hotp` are standard, and some apps add their own, such as `steam`. [secret] is Base32 in
 * capitals without padding. Parameters the link leaves out are null, so their defaults stay the
 * authenticator's to apply.
 */
data class OtpKey(
    val type: String,
    val issuer: String?,
    val account: String?,
    val secret: String,
    val algorithm: String?,
    val digits: Int?,
    val period: Int?,
    val counter: Long?,
)

/**
 * Content that may grant access or identify its holder, each kind kept in history or not as the
 * settings choose. They are listed by how much the content gives whoever holds it, least first: a
 * network to join, a trip to look up and change, a phone line to download, a sign-in to an account,
 * and a lasting second factor for one. What people scan to look at again, such as a boarding pass,
 * is kept unless the user says otherwise; credentials, which are added once and are risky to keep,
 * start out left out.
 */
enum class SensitiveKind(val keptByDefault: Boolean) {
    WIFI_PASSWORD(true),
    BOARDING_PASS(true),
    ESIM(false),
    PASSKEY(false),
    OTP(false),
}

/** The kind of sensitive content this is, or null for content that is not. */
val Payload.sensitiveKind: SensitiveKind?
    get() = when (this) {
        is WifiPayload -> SensitiveKind.WIFI_PASSWORD.takeIf { password != null }
        is BoardingPassPayload -> SensitiveKind.BOARDING_PASS
        is EsimPayload -> SensitiveKind.ESIM
        is FidoPayload -> SensitiveKind.PASSKEY
        is OtpPayload -> SensitiveKind.OTP
        else -> null
    }

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
