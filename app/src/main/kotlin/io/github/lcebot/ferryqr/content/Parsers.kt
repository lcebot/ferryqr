package io.github.lcebot.ferryqr.content

import java.net.URLDecoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.abs

internal object WifiParser {
    fun parse(text: String): WifiPayload? {
        val fields = Fields.pairs(text.substringAfter(':')).associate { (k, v) -> k to Fields.unescape(v) }
        val ssid = fields["S"]?.dequote()?.takeIf { it.isNotEmpty() } ?: return null
        val password = fields["P"]?.dequote()?.takeIf { it.isNotEmpty() }
        val type = fields["T"]?.trim()?.uppercase().orEmpty()
        val security = when {
            type.isEmpty() || type == "NOPASS" -> if (password == null) WifiSecurity.OPEN else WifiSecurity.WPA
            type == "WEP" -> WifiSecurity.WEP
            "EAP" in type -> WifiSecurity.EAP
            type == "SAE" || type == "WPA3" -> WifiSecurity.SAE
            else -> WifiSecurity.WPA
        }
        val hidden = fields["H"].equals("true", ignoreCase = true)
        return WifiPayload(text, ssid, password, security, hidden)
    }

    private fun String.dequote() =
        if (length >= 2 && startsWith('"') && endsWith('"')) substring(1, length - 1) else this
}

internal object ContactParser {

    fun meCard(text: String): Contact {
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()
        var name: String? = null
        var org: String? = null
        var address: String? = null
        var url: String? = null
        var note: String? = null
        for ((key, value) in Fields.pairs(text.substringAfter(':'))) {
            when (key) {
                "N" -> {
                    val parts = Fields.split(value, ',').map { Fields.unescape(it) }
                    name = joinName(given = parts.getOrNull(1), family = parts.getOrNull(0))
                }
                "TEL" -> phones += Fields.unescape(value).trim()
                "EMAIL" -> emails += Fields.unescape(value).trim()
                "ORG" -> org = Fields.unescape(value).trim()
                "ADR" -> address = Fields.unescape(value).trim()
                "URL" -> url = Fields.unescape(value).trim()
                "NOTE" -> note = Fields.unescape(value).trim()
            }
        }
        return Contact(name, phones.filter { it.isNotEmpty() }, emails.filter { it.isNotEmpty() }, org, null, address, url, note)
    }

    fun vCard(text: String): Contact {
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()
        var formatted: String? = null
        var structured: String? = null
        var org: String? = null
        var title: String? = null
        var address: String? = null
        var url: String? = null
        var note: String? = null
        for (line in Fields.unfold(text)) {
            val colon = Fields.indexOfUnescaped(line, ':')
            if (colon <= 0) continue
            // "item1.EMAIL;TYPE=work" names the property EMAIL.
            val property = line.substring(0, colon).substringBefore(';').substringAfterLast('.').uppercase()
            val value = line.substring(colon + 1)
            fun plain() = Fields.unescape(value, vCard = true).trim()
            fun components() = Fields.split(value, ';').map { Fields.unescape(it, vCard = true).trim() }
            when (property) {
                "FN" -> formatted = plain()
                "N" -> components().let { structured = joinName(given = it.getOrNull(1), family = it.getOrNull(0)) }
                "TEL" -> phones += plain().removePrefix("tel:")
                "EMAIL" -> emails += plain()
                "ORG" -> org = components().filter { it.isNotEmpty() }.joinToString(" ")
                "TITLE" -> title = plain()
                "ADR" -> address = components().filter { it.isNotEmpty() }.joinToString(", ")
                "URL" -> url = plain()
                "NOTE" -> note = plain()
            }
        }
        return Contact(
            name = formatted?.takeIf { it.isNotEmpty() } ?: structured,
            phones = phones.filter { it.isNotEmpty() },
            emails = emails.filter { it.isNotEmpty() },
            organization = org?.takeIf { it.isNotEmpty() },
            title = title?.takeIf { it.isNotEmpty() },
            address = address?.takeIf { it.isNotEmpty() },
            url = url?.takeIf { it.isNotEmpty() },
            note = note?.takeIf { it.isNotEmpty() },
        )
    }
}

internal object EventParser {

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): CalendarEvent? {
        val lines = Fields.unfold(text)
        val start = lines.indexOfFirst { it.trim().equals("BEGIN:VEVENT", ignoreCase = true) }
        if (start < 0) return null
        var title: String? = null
        var location: String? = null
        var description: String? = null
        var begin: Long? = null
        var end: Long? = null
        var allDay = false
        for (line in lines.drop(start + 1)) {
            if (line.trim().equals("END:VEVENT", ignoreCase = true)) break
            val colon = Fields.indexOfUnescaped(line, ':')
            if (colon <= 0) continue
            val head = line.substring(0, colon).split(';')
            val property = head.first().uppercase()
            val params = head.drop(1)
            val value = line.substring(colon + 1)
            when (property) {
                "SUMMARY" -> title = Fields.unescape(value, vCard = true).trim()
                "LOCATION" -> location = Fields.unescape(value, vCard = true).trim()
                "DESCRIPTION" -> description = Fields.unescape(value, vCard = true).trim()
                "DTSTART" -> {
                    allDay = value.trim().length == 8
                    begin = parseTime(value, params, zone)
                }
                "DTEND" -> end = parseTime(value, params, zone)
            }
        }
        if (title.isNullOrEmpty() && begin == null) return null
        return CalendarEvent(
            title = title?.takeIf { it.isNotEmpty() },
            begin = begin,
            end = end,
            allDay = allDay,
            location = location?.takeIf { it.isNotEmpty() },
            description = description?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseTime(value: String, params: List<String>, zone: ZoneId): Long? {
        val v = value.trim()
        return try {
            if (v.length == 8) {
                LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant().toEpochMilli()
            } else {
                val utc = v.endsWith("Z", ignoreCase = true)
                val local = LocalDateTime.parse(if (utc) v.dropLast(1) else v, DATE_TIME)
                val tz = when {
                    utc -> ZoneOffset.UTC
                    else -> params.firstOrNull { it.startsWith("TZID=", ignoreCase = true) }
                        ?.substringAfter('=')?.trim('"')
                        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                        ?: zone
                }
                local.atZone(tz).toInstant().toEpochMilli()
            }
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

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

/**
 * Reads the QR code a computer shows for cross-device authentication with a passkey, as CTAP 2.2
 * defines it: FIDO:/ followed by digits that encode a CBOR map. Every 7 bytes are written as 17
 * digits of their little-endian value, and a shorter last group takes as few digits as its bytes
 * need. Key 3 of the map is when the code was made, in seconds since 1970, which browsers include.
 */
internal object PasskeyQr {

    private const val PREFIX = "FIDO:/"
    private const val CHUNK_BYTES = 7
    private const val CHUNK_DIGITS = 17

    /** Digits that a final group of 1 to 6 bytes takes. */
    private val PARTIAL_DIGITS = intArrayOf(0, 3, 5, 8, 10, 13, 15)

    private const val KEY_CREATED = 3L
    private const val KEY_OPERATION = 5L

    /** When the code was made, in seconds since 1970, or null when the code does not say. */
    fun createdAt(raw: String): Long? = valueOf(raw, KEY_CREATED) as? Long

    /**
     * What the other device asks for, from the hint CTAP 2.2 codes carry: `ga` to sign in with a
     * passkey, `mc` to create one, and `dcp` and `dci` to present or add a digital credential. Null
     * when the code gives no hint, as older codes do, or one FerryQR does not know, which the
     * specification has authenticators take as signing in but a name should not presume.
     */
    fun operation(raw: String): PasskeyOperation? = when (valueOf(raw, KEY_OPERATION)) {
        "ga" -> PasskeyOperation.SIGN_IN
        "mc" -> PasskeyOperation.CREATE
        "dcp" -> PasskeyOperation.PRESENT_CREDENTIAL
        "dci" -> PasskeyOperation.ADD_CREDENTIAL
        else -> null
    }

    private fun valueOf(raw: String, key: Long): Any? {
        if (!raw.startsWith(PREFIX, ignoreCase = true)) return null
        val bytes = bytesOf(raw.substring(PREFIX.length)) ?: return null
        return try {
            Cbor(bytes).valueInMap(key)
        } catch (e: IndexOutOfBoundsException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
    }

    private fun bytesOf(digits: String): ByteArray? {
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        val out = ArrayList<Byte>()
        var index = 0
        while (index < digits.length) {
            val remaining = digits.length - index
            val (take, bytes) = if (remaining >= CHUNK_DIGITS) {
                CHUNK_DIGITS to CHUNK_BYTES
            } else {
                val partial = PARTIAL_DIGITS.indexOf(remaining)
                if (partial <= 0) return null
                remaining to partial
            }
            var value = digits.substring(index, index + take).toLong()
            repeat(bytes) {
                out += (value and 0xFF).toByte()
                value = value ushr 8
            }
            if (value != 0L) return null
            index += take
        }
        return out.toByteArray()
    }

    /**
     * Just enough CBOR to find a value under an unsigned key of the top map: an unsigned number as
     * a Long, a string as a String, anything else as null. Byte strings count as strings too: the
     * specification's own example writes the hint as one, where Chromium writes text.
     */
    private class Cbor(private val data: ByteArray) {
        private var position = 0

        fun valueInMap(key: Long): Any? {
            val (major, pairs) = head()
            check(major == MAP)
            var found: Any? = null
            repeat(pairs.toInt()) {
                val (keyMajor, keyValue) = head()
                if (keyMajor == UINT && keyValue == key) {
                    val (valueMajor, value) = head()
                    found = when (valueMajor) {
                        UINT -> value
                        TEXT, BYTES -> text(value)
                        else -> null.also { skipBody(valueMajor, value, depth = 1) }
                    }
                } else {
                    skipBody(keyMajor, keyValue, depth = 1)
                    skip(depth = 1)
                }
            }
            return found
        }

        private fun next(): Int = data[position++].toInt() and 0xFF

        private fun text(length: Long): String {
            check(length in 0..(data.size - position).toLong())
            val start = position
            position += length.toInt()
            return String(data, start, length.toInt(), Charsets.UTF_8)
        }

        /** The major type of the next item and its argument: a value, a length or a count. */
        private fun head(): Pair<Int, Long> {
            val initial = next()
            val major = initial ushr 5
            val info = initial and 0x1F
            val argument = when {
                info < 24 -> info.toLong()
                info == 24 -> next().toLong()
                info == 25 -> (next().toLong() shl 8) or next().toLong()
                info == 26 -> (0 until 4).fold(0L) { acc, _ -> (acc shl 8) or next().toLong() }
                info == 27 -> (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or next().toLong() }
                else -> error("indefinite lengths are not used here")
            }
            return major to argument
        }

        private fun skip(depth: Int) {
            val (major, argument) = head()
            skipBody(major, argument, depth)
        }

        /**
         * Skips an item [depth] levels into the top map. Each level is a call deeper, and scanned
         * data can nest thousands of levels in a code, so nesting deeper than [MAX_DEPTH] is taken
         * as malformed before it can exhaust the stack.
         */
        private fun skipBody(major: Int, argument: Long, depth: Int) {
            check(depth <= MAX_DEPTH)
            when (major) {
                UINT, NEGATIVE, SIMPLE -> Unit
                BYTES, TEXT -> {
                    check(argument in 0..(data.size - position).toLong())
                    position += argument.toInt()
                }
                ARRAY -> repeat(argument.toInt()) { skip(depth + 1) }
                MAP -> repeat(argument.toInt()) {
                    skip(depth + 1)
                    skip(depth + 1)
                }
                TAG -> skip(depth + 1)
            }
        }

        companion object {
            const val UINT = 0
            const val NEGATIVE = 1
            const val BYTES = 2
            const val TEXT = 3
            const val ARRAY = 4
            const val MAP = 5
            const val TAG = 6
            const val SIMPLE = 7

            /** Far beyond the level or two that real codes nest. */
            const val MAX_DEPTH = 16
        }
    }
}

/**
 * Reads `otpauth://TYPE/LABEL?PARAMETERS`, the key URI format Google Authenticator defined and
 * every authenticator follows. Links in the wild stray from it, so this accepts what common
 * authenticators accept: any case in the scheme and type, the label's issuer split off at its first
 * colon, encoded or not, and a secret in lower case, padded, or broken up by spaces or hyphens. A
 * link without a usable secret, or an HOTP link without its counter, adds no key and is left to be
 * read as an ordinary link.
 */
internal object OtpParser {
    private const val PREFIX = "otpauth://"
    private val BASE32 = Regex("^[A-Z2-7]+$")
    private val COLON = Regex(":|%3A", RegexOption.IGNORE_CASE)

    fun parse(text: String): OtpKey? {
        if (!text.startsWith(PREFIX, ignoreCase = true)) return null
        val rest = text.substring(PREFIX.length)
        val type = rest.substringBefore('/', "").lowercase().takeIf { it.isNotEmpty() } ?: return null
        val path = rest.substringAfter('/')
        val params = parameters(path.substringAfter('?', ""))
        val secret = params["secret"]?.uppercase()?.filterNot { it == ' ' || it == '-' }?.trimEnd('=')
            ?.takeIf { BASE32.matches(it) } ?: return null
        val counter = params["counter"]?.trim()?.toLongOrNull()
        if (type == "hotp" && counter == null) return null

        // The first colon, encoded or not, ends the issuer; a later one belongs to the account.
        val label = path.substringBefore('?')
        val colon = COLON.find(label)
        val labelIssuer = colon?.let { decodeLabel(label.substring(0, it.range.first)).trim() }
        val account = decodeLabel(if (colon == null) label else label.substring(colon.range.last + 1)).trim()
        return OtpKey(
            type = type,
            // The parameter is what authenticators match on; the label's prefix only names it.
            issuer = params["issuer"]?.trim()?.takeIf { it.isNotEmpty() } ?: labelIssuer?.takeIf { it.isNotEmpty() },
            account = account.takeIf { it.isNotEmpty() },
            secret = secret,
            algorithm = params["algorithm"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            digits = params["digits"]?.trim()?.toIntOrNull(),
            period = params["period"]?.trim()?.toIntOrNull(),
            counter = counter,
        )
    }

    /** Query parameters by lowercase name, the first of any repeated one, decoded as forms encode them. */
    private fun parameters(query: String): Map<String, String> = buildMap {
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val name = decode(pair.substringBefore('='), plusIsSpace = true).lowercase()
            if (name !in this) put(name, decode(pair.substringAfter('=', ""), plusIsSpace = true))
        }
    }

    /** In the label, the path of the link, a plus is a plus rather than a space. */
    private fun decodeLabel(text: String) = decode(text, plusIsSpace = false)

    /** Percent-decoded UTF-8; malformed escapes leave the text as it was. */
    private fun decode(text: String, plusIsSpace: Boolean): String = try {
        URLDecoder.decode(if (plusIsSpace) text else text.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        text
    }
}
