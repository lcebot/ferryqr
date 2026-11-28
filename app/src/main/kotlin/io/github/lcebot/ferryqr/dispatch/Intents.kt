package io.github.lcebot.ferryqr.dispatch

import android.app.SearchManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.Intents.Insert
import android.provider.Settings
import io.github.lcebot.ferryqr.content.CalendarEvent
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.content.Contact
import io.github.lcebot.ferryqr.content.WifiPayload
import io.github.lcebot.ferryqr.content.WifiSecurity

/**
 * The intents content is handed over with, made from the content alone. Nothing here asks the system
 * or starts anything, so the same intent can be asked about through [FerryEnv] and started by [Launch].
 */
internal object Intents {

    /**
     * [uri] opened as a link. Intent filters match schemes case-sensitively and declare them in lower
     * case, while scanned links often keep an upper-case scheme, such as the `HTTPS://` that the
     * denser alphanumeric mode of a QR code calls for.
     */
    fun browsable(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri.normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)

    /** A link no app claims for itself, so only browsers open it; the .invalid domain never exists. */
    private val ANY_WEB_LINK = Uri.parse("https://example.invalid/")

    /** A link that only browsers open, which tells the browsers from other apps. */
    fun anyWebLink(): Intent = browsable(ANY_WEB_LINK)

    /** [uri] opened as a link, the way a browser hands it on. */
    fun link(uri: String): Intent = browsable(Uri.parse(uri))

    /**
     * [url] opened by the browser [browser], even when an app holds a verified App Link for it; with
     * no browser named, it goes wherever the system opens any link.
     */
    fun browser(url: String, browser: String?): Intent = link(url).apply { browser?.let { setPackage(it) } }

    /** [url] opened only when an app other than a browser claims it, such as a verified App Link. */
    fun nonBrowser(url: String): Intent = link(url).addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)

    /**
     * The intent an app link stands for. An `intent:` URI follows the browser rules for untrusted
     * pages: no explicit component or selector, no permission grants, and only activities that
     * declare themselves browsable. Null for one that cannot be read or names a blocked scheme.
     */
    fun scheme(uri: String, scheme: String): Intent? {
        if (scheme != "intent") return link(uri)
        // Besides URISyntaxException, the parser lets the number parsing of typed extras and flags
        // throw as it is, so a crafted code can make it throw nearly anything; all of it is unreadable.
        val intent = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            return null
        }
        intent.component = null
        intent.selector = null
        intent.flags = 0
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        // The scheme in lower case, as [browsable] gives every other link. Setting the data alone
        // would drop a type the URI names.
        intent.data?.let { intent.setDataAndType(it.normalizeScheme(), intent.type) }
        return intent.takeUnless { it.data?.scheme?.lowercase() in Classifier.BLOCKED_SCHEMES }
    }

    /** The ways the passkey request [raw] reaches a passkey manager, best first, in both [spellings]. */
    fun fido(raw: String): List<Intent> {
        val candidates = spellings(raw)
        return candidates.map { Intent(FIDO_ACTION, it) } + candidates.map { Intent(Intent.ACTION_VIEW, it) }
    }

    /**
     * [raw] as scanned and with its scheme in lower case. Passkey requests and eSIM activation codes
     * write their schemes in upper case, as `FIDO` and `LPA`, and intent filters match schemes
     * case-sensitively, so an app may declare either spelling and both are asked.
     */
    private fun spellings(raw: String): List<Uri> = Uri.parse(raw).let { listOf(it, it.normalizeScheme()).distinct() }

    private const val FIDO_ACTION = "org.fidoalliance.intent.QRCodeAuthenticator"

    /**
     * The ways the eSIM activation code [raw] reaches an LPA app, best first, the code in both
     * [spellings]. No public eSIM activation intent exists, so these are the known LPA apps' own,
     * then any app that opens the code.
     */
    fun esim(raw: String): List<Intent> {
        val codes = spellings(if (raw.startsWith("LPA:", ignoreCase = true)) raw else "LPA:$raw")
        val miui = Intent("com.miui.euicc.action.ADD_ESIM")
            .putExtra("com.miui.euicc.extra.ACTIVATE_TYPE", "activate_manual")
            .putExtra("com.miui.euicc.extra.ACTIVATE_CODE", raw)
            .putExtra("com.miui.euicc.extra.OPERATE_TYPE", "scan")
        val google = codes.map { Intent(Intent.ACTION_VIEW, it).setPackage(GOOGLE_LPA) }
        return listOf(miui) + google + codes.map { Intent(Intent.ACTION_VIEW, it) }
    }

    /** Google's LPA app, which the manifest lists so it can be seen. */
    private const val GOOGLE_LPA = "com.google.android.euicc"

    /** The screens where SIM profiles are managed, best first. */
    fun simSettings(): List<Intent> =
        listOf(Intent("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))

    /** The Wi-Fi Easy Connect code [raw], for the system to configure a device with. */
    fun dpp(raw: String): Intent = Intent(Settings.ACTION_PROCESS_WIFI_EASY_CONNECT_URI, Uri.parse(raw))

    /** The network [wifi] names as the system adds it, or null for one the system cannot add from a code. */
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
    fun addWifi(suggestion: WifiNetworkSuggestion): Intent =
        Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))

    /** Where a new contact is added, which the contacts app takes. */
    fun newContact(): Intent = Intent(Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)

    /** [contact] to be added as a new contact. */
    fun newContact(contact: Contact): Intent {
        val intent = newContact()
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
        return intent
    }

    /** Where a new event is added, which the calendar app takes. */
    fun newEvent(): Intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)

    /** [event] to be added as a new event. */
    fun newEvent(event: CalendarEvent): Intent {
        val intent = newEvent()
        event.title?.let { intent.putExtra(CalendarContract.Events.TITLE, it) }
        event.begin?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        event.end?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
        event.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        event.description?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        return intent
    }

    fun search(text: String): Intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text)

    fun share(text: String): Intent =
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
}
