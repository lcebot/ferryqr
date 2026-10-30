package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.PersistableBundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.Intents.Insert
import android.provider.Settings
import io.github.lcebot.ferryqr.content.CalendarEvent
import io.github.lcebot.ferryqr.content.Contact
import io.github.lcebot.ferryqr.content.WifiPayload
import io.github.lcebot.ferryqr.content.WifiSecurity
import java.net.URISyntaxException

internal object Launch {

    private val BLOCKED_SCHEMES = setOf("file", "content", "javascript", "data", "about", "blob")

    fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun browsable(uri: Uri) = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)

    fun browser(activity: Activity, url: String) = start(activity, browsable(Uri.parse(url)))

    /** Succeeds only when an app other than a browser claims the link, such as a verified App Link. */
    fun nonBrowser(activity: Activity, url: String) =
        start(activity, browsable(Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER))

    fun scheme(activity: Activity, uri: String, scheme: String): Boolean =
        if (scheme == "intent") intentUri(activity, uri) else start(activity, browsable(Uri.parse(uri)))

    /**
     * Follows the browser rules for `intent:` URIs from untrusted pages: no explicit component or
     * selector, no permission grants, and only activities that declare themselves browsable.
     */
    private fun intentUri(activity: Activity, uri: String): Boolean {
        val intent = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            return false
        }
        val fallback = intent.getStringExtra("browser_fallback_url")
        intent.component = null
        intent.selector = null
        intent.flags = 0
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        if (intent.data?.scheme?.lowercase() in BLOCKED_SCHEMES) return false
        if (start(activity, intent)) return true
        val fallbackUri = fallback?.let(Uri::parse)
            ?.takeIf { it.scheme.equals("https", true) || it.scheme.equals("http", true) }
            ?: return false
        return start(activity, browsable(fallbackUri))
    }

    /** QR codes write the scheme as `FIDO`, while intent filters match schemes case-sensitively. */
    fun fido(activity: Activity, raw: String): Boolean {
        val original = Uri.parse(raw)
        val candidates = listOf(original, original.normalizeScheme()).distinct()
        return candidates.any { start(activity, Intent("org.fidoalliance.intent.QRCodeAuthenticator", it)) } ||
            candidates.any { start(activity, Intent(Intent.ACTION_VIEW, it)) }
    }

    /** No public eSIM activation intent exists, so known LPA apps are tried in turn. */
    fun esim(activity: Activity, raw: String): Boolean {
        val code = if (raw.startsWith("LPA:", ignoreCase = true)) raw else "LPA:$raw"
        val miui = Intent("com.miui.euicc.action.ADD_ESIM")
            .putExtra("com.miui.euicc.extra.ACTIVATE_TYPE", "activate_manual")
            .putExtra("com.miui.euicc.extra.ACTIVATE_CODE", raw)
            .putExtra("com.miui.euicc.extra.OPERATE_TYPE", "scan")
        val google = Intent(Intent.ACTION_VIEW, Uri.parse(code)).setPackage("com.google.android.euicc")
        return start(activity, miui) || start(activity, google) ||
            start(activity, Intent(Intent.ACTION_VIEW, Uri.parse(code)))
    }

    fun simSettings(activity: Activity): Boolean =
        start(activity, Intent("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS")) ||
            start(activity, Intent(Settings.ACTION_WIRELESS_SETTINGS))

    fun dppSupported(context: Context): Boolean =
        context.getSystemService(WifiManager::class.java)?.isEasyConnectSupported == true

    fun dpp(activity: Activity, raw: String): Boolean =
        dppSupported(activity) &&
            start(activity, Intent(Settings.ACTION_PROCESS_WIFI_EASY_CONNECT_URI, Uri.parse(raw)))

    fun wifiSuggestion(wifi: WifiPayload): WifiNetworkSuggestion? = try {
        val builder = WifiNetworkSuggestion.Builder().setSsid(wifi.ssid).setIsHiddenSsid(wifi.hidden)
        when (wifi.security) {
            WifiSecurity.OPEN -> builder.build()
            WifiSecurity.WPA -> wifi.password?.let { builder.setWpa2Passphrase(it).build() }
            WifiSecurity.SAE -> wifi.password?.let { builder.setWpa3Passphrase(it).build() }
            WifiSecurity.WEP, WifiSecurity.EAP -> null
        }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The system shows its own confirmation, so no location permission is involved. */
    fun connectWifi(activity: Activity, suggestion: WifiNetworkSuggestion): Boolean = start(
        activity,
        Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
            .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion)),
    )

    fun addContact(activity: Activity, contact: Contact): Boolean {
        val intent = Intent(Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        contact.name?.let { intent.putExtra(Insert.NAME, it) }
        listOf(Insert.PHONE, Insert.SECONDARY_PHONE, Insert.TERTIARY_PHONE)
            .zip(contact.phones).forEach { (key, value) -> intent.putExtra(key, value) }
        listOf(Insert.EMAIL, Insert.SECONDARY_EMAIL, Insert.TERTIARY_EMAIL)
            .zip(contact.emails).forEach { (key, value) -> intent.putExtra(key, value) }
        contact.organization?.let { intent.putExtra(Insert.COMPANY, it) }
        contact.title?.let { intent.putExtra(Insert.JOB_TITLE, it) }
        contact.address?.let { intent.putExtra(Insert.POSTAL, it) }
        contact.note?.let { intent.putExtra(Insert.NOTES, it) }
        contact.url?.let { url ->
            val website = ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Website.URL, url)
            }
            intent.putParcelableArrayListExtra(Insert.DATA, arrayListOf(website))
        }
        return start(activity, intent)
    }

    fun addEvent(activity: Activity, event: CalendarEvent): Boolean {
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        event.title?.let { intent.putExtra(CalendarContract.Events.TITLE, it) }
        event.begin?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        event.end?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
        event.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        event.description?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        return start(activity, intent)
    }

    fun search(activity: Activity, text: String): Boolean =
        start(activity, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text))

    fun share(activity: Activity, text: String): Boolean = start(
        activity,
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null),
    )

    fun copy(context: Context, text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(text.take(40), text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }
}
