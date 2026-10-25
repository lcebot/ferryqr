package com.lcebot.ferryqr.content

sealed interface Payload {
    /** The scanned content as shown to the user and copied to the clipboard. */
    val raw: String
}

data class WebPayload(override val raw: String, val url: String, val host: String) : Payload

/** Any other URI scheme, opened through whatever app registers it as browsable. */
data class SchemePayload(override val raw: String, val uri: String, val scheme: String) : Payload

data class FidoPayload(override val raw: String) : Payload

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
