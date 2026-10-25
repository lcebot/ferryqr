package com.lcebot.ferryqr.content

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

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
