package io.github.lcebot.ferryqr.content

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
