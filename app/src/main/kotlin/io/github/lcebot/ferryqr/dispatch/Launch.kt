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
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.PersistableBundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.Intents.Insert
import android.provider.Settings
import android.telephony.euicc.EuiccManager
import io.github.lcebot.ferryqr.content.CalendarEvent
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.content.Contact
import io.github.lcebot.ferryqr.content.WifiPayload
import io.github.lcebot.ferryqr.content.WifiSecurity
import java.net.URISyntaxException

internal object Launch {


    fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun browsable(uri: Uri) = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)

    /** A link no app claims for itself, so only browsers open it; the .invalid domain never exists. */
    private val ANY_WEB_LINK = Uri.parse("https://example.invalid/")

    private fun query(context: Context, intent: Intent, flags: Int): List<ResolveInfo> {
        val packages = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packages.queryIntentActivities(intent, flags)
        }
    }

    private fun resolve(context: Context, intent: Intent, flags: Int): ResolveInfo? {
        val packages = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packages.resolveActivity(intent, flags)
        }
    }

    /** The apps that open any web link, which is what makes an app a browser. */
    private fun browsers(context: Context): Set<String> =
        query(context, browsable(ANY_WEB_LINK), PackageManager.MATCH_DEFAULT_ONLY).mapTo(HashSet()) { it.activityInfo.packageName }

    /**
     * The default browser, or the only one. Without either, the system asks which app to use, and
     * a link with a verified App Link goes to its app instead.
     */
    private fun browserPackage(context: Context): String? {
        val browsers = browsers(context)
        val default = resolve(context, browsable(ANY_WEB_LINK), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return default?.takeIf { it in browsers } ?: browsers.singleOrNull()
    }

    /** Opens [url] in a browser, even when an app holds a verified App Link for it. */
    fun browser(activity: Activity, url: String): Boolean {
        val intent = browsable(Uri.parse(url))
        browserPackage(activity)?.let { intent.setPackage(it) }
        return start(activity, intent)
    }

    /**
     * Whether any app opens [uri] as a link. Only schemes the manifest queries for are visible, so
     * this answers for those alone.
     */
    fun canOpen(context: Context, uri: String): Boolean = query(context, browsable(Uri.parse(uri)), 0).isNotEmpty()

    /** Whether any app opens the app link [uri], the way [scheme] would open it. */
    fun opens(context: Context, uri: String, scheme: String): Boolean =
        schemeIntent(uri, scheme)?.let { query(context, it, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() } == true

    /** [uri] opened as a link, the way a browser hands it on. */
    fun linkIntent(uri: String): Intent = browsable(Uri.parse(uri))

    /**
     * The app other than a browser that the system opens [intent] in without asking, such as one
     * with a verified App Link for a web link, which is where [nonBrowser] goes too. Null when it
     * goes to a browser, or when the system would ask, since no one app is chosen then. The
     * manifest queries for every app with links, which is what lets the app be seen here.
     */
    fun handler(context: Context, intent: Intent): String? {
        val chosen = resolve(context, intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        // The system's chooser resolves too, but is not among the activities that match the link.
        val matches = query(context, intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.packageName == chosen.packageName && it.activityInfo.name == chosen.name }
        val pkg = chosen.packageName
        return pkg.takeIf { matches && it != context.packageName && it !in browsers(context) }
    }

    /** Where [intent] goes: the app the system picks without asking, several it asks between, or none. */
    fun handling(context: Context, intent: Intent): Handling {
        handler(context, intent)?.let { return Handling.App(it) }
        return if (query(context, intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) Handling.None else Handling.Asks
    }

    /** Succeeds only when an app other than a browser claims the link, such as a verified App Link. */
    fun nonBrowser(activity: Activity, url: String) =
        start(activity, browsable(Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER))

    /**
     * Opens an app link through whatever app registers it, or for an `intent:` URI that nothing
     * opens, the web page it names as a fallback.
     */
    fun scheme(activity: Activity, uri: String, scheme: String): Boolean {
        val intent = schemeIntent(uri, scheme) ?: return false
        if (start(activity, intent)) return true
        if (scheme != "intent") return false
        val fallbackUri = intent.getStringExtra("browser_fallback_url")?.let(Uri::parse)
            ?.takeIf { it.scheme.equals("https", true) || it.scheme.equals("http", true) }
            ?: return false
        return start(activity, browsable(fallbackUri))
    }

    /**
     * The intent an app link stands for. An `intent:` URI follows the browser rules for untrusted
     * pages: no explicit component or selector, no permission grants, and only activities that
     * declare themselves browsable. Null for one that cannot be read or names a blocked scheme.
     */
    fun schemeIntent(uri: String, scheme: String): Intent? {
        if (scheme != "intent") return browsable(Uri.parse(uri))
        val intent = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            return null
        }
        intent.component = null
        intent.selector = null
        intent.flags = 0
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        return intent.takeUnless { it.data?.scheme?.lowercase() in Classifier.BLOCKED_SCHEMES }
    }

    /** QR codes write the scheme as `FIDO`, while intent filters match schemes case-sensitively. */
    fun fido(activity: Activity, raw: String): Boolean = fidoIntents(raw).any { start(activity, it) }

    /** Whether anything takes passkey requests, asked with the forms [fido] tries. */
    fun fidoSupported(context: Context): Boolean =
        fidoIntents("FIDO:/").any { query(context, it, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() }

    private fun fidoIntents(raw: String): List<Intent> {
        val original = Uri.parse(raw)
        val candidates = listOf(original, original.normalizeScheme()).distinct()
        return candidates.map { Intent(FIDO_ACTION, it) } + candidates.map { Intent(Intent.ACTION_VIEW, it) }
    }

    private const val FIDO_ACTION = "org.fidoalliance.intent.QRCodeAuthenticator"

    /** Whether the phone has an eSIM it can add profiles to. */
    fun esimSupported(context: Context): Boolean = context.getSystemService(EuiccManager::class.java)?.isEnabled == true

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

    /**
     * Whether the phone supports Wi-Fi Easy Connect. Asking needs ACCESS_WIFI_STATE, which some
     * systems let the user take away; without it, the feature counts as missing.
     */
    fun dppSupported(context: Context): Boolean = try {
        context.getSystemService(WifiManager::class.java)?.isEasyConnectSupported == true
    } catch (e: SecurityException) {
        false
    }

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

    /** Where a new contact is added, which the contacts app takes. */
    fun contactIntent(): Intent = Intent(Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)

    fun addContact(activity: Activity, contact: Contact): Boolean {
        val intent = contactIntent()
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

    /** Where a new event is added, which the calendar app takes. */
    fun eventIntent(): Intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)

    fun addEvent(activity: Activity, event: CalendarEvent): Boolean {
        val intent = eventIntent()
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

    /** A [sensitive] text gets no label, since clipboard managers may show the label in full. */
    fun copy(context: Context, text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText(if (sensitive) "" else text.take(40), text)
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

    /** What the installed app [packageName] calls itself, in the phone's language. */
    fun appLabel(context: Context, packageName: String): String? = try {
        val packages = context.packageManager
        packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
