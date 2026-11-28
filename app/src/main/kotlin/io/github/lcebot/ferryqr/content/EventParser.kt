package io.github.lcebot.ferryqr.content

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

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
            val value = Fields.decode(line.substring(colon + 1), params)
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
