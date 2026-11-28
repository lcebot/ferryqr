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
import android.content.pm.ActivityInfo
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
import kotlin.math.min

internal object Launch {

    fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /**
     * [uri] opened as a link. Intent filters match schemes case-sensitively and declare them in lower
     * case, while scanned links often keep an upper-case scheme, such as the `HTTPS://` that the
     * denser alphanumeric mode of a QR code calls for.
     */
    private fun browsable(uri: Uri) = Intent(Intent.ACTION_VIEW, uri.normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)

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

    /**
     * The apps that open any web link, which is what makes an app a browser, asked afresh on each call. Every link
     * card asks for them, so they are asked through [InstalledApps.browsers], which keeps them.
     */
    fun findBrowsers(context: Context): Set<String> =
        query(context, browsable(ANY_WEB_LINK), PackageManager.MATCH_DEFAULT_ONLY).mapTo(HashSet()) { it.activityInfo.packageName }

    /**
     * The default browser, or the only one. Without either, the system asks which app to use, and
     * a link with a verified App Link goes to its app instead.
     */
    private fun browserPackage(context: Context): String? {
        val browsers = InstalledApps.browsers(context)
        val default = resolve(context, browsable(ANY_WEB_LINK), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return default?.takeIf { it in browsers } ?: browsers.singleOrNull()
    }

    /** Opens [url] in a browser, even when an app holds a verified App Link for it. */
    fun browser(activity: Activity, url: String): Boolean {
        val intent = browsable(Uri.parse(url))
        browserPackage(activity)?.let { intent.setPackage(it) }
        return start(activity, intent)
    }

    /** [uri] opened as a link, the way a browser hands it on. */
    fun linkIntent(uri: String): Intent = browsable(Uri.parse(uri))

    /**
     * What the system says about [intent], asked once: the activity it resolves to and every one
     * that matches. A card asks through [FerryEnv.resolved], which keeps the answer for the card.
     */
    fun resolved(context: Context, intent: Intent): Resolved = Resolved(
        resolve(context, intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo,
        query(context, intent, PackageManager.MATCH_DEFAULT_ONLY),
    )

    /**
     * The app other than a browser that the system opens an intent in without asking, as [resolved]
     * gives it, such as one with a verified App Link for a web link, which is where [nonBrowser] goes
     * too. Null when it goes to a browser, or when the system would ask, since no one app is chosen
     * then. The manifest queries for every app with links, which is what lets the app be seen here.
     * [browsers] are the apps that open any web link.
     */
    fun handler(context: Context, resolved: Resolved, browsers: Set<String>): String? =
        resolved.chosen?.packageName?.takeIf { it != context.packageName && it !in browsers }

    /** Where [intent] goes: the app the system picks without asking, several it asks between, or none. */
    fun handling(context: Context, intent: Intent): Handling {
        val answer = resolved(context, intent)
        handler(context, answer, InstalledApps.browsers(context))?.let { return Handling.App(it) }
        return if (answer.matches.isEmpty()) Handling.None else Handling.Asks
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

    /** Whether anything takes passkey requests, asked with the forms [fidoIntents] gives. */
    fun fidoSupported(context: Context): Boolean =
        fidoIntents("FIDO:/").any { query(context, it, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() }

    /** The ways the passkey request [raw] reaches a passkey manager, best first, in both [spellings]. */
    fun fidoIntents(raw: String): List<Intent> {
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

    /** Whether the phone has an eSIM it can add profiles to. */
    fun esimSupported(context: Context): Boolean = context.getSystemService(EuiccManager::class.java)?.isEnabled == true

    /**
     * The ways the eSIM activation code [raw] reaches an LPA app, best first, the code in both
     * [spellings]. No public eSIM activation intent exists, so these are the known LPA apps' own,
     * then any app that opens the code.
     */
    fun esimIntents(raw: String): List<Intent> {
        val codes = spellings(if (raw.startsWith("LPA:", ignoreCase = true)) raw else "LPA:$raw")
        val miui = Intent("com.miui.euicc.action.ADD_ESIM")
            .putExtra("com.miui.euicc.extra.ACTIVATE_TYPE", "activate_manual")
            .putExtra("com.miui.euicc.extra.ACTIVATE_CODE", raw)
            .putExtra("com.miui.euicc.extra.OPERATE_TYPE", "scan")
        val google = codes.map { Intent(Intent.ACTION_VIEW, it).setPackage(GOOGLE_LPA) }
        return listOf(miui) + google + codes.map { Intent(Intent.ACTION_VIEW, it) }
    }

    /** Google's LPA app, which the manifest lists so it can be seen here. */
    private const val GOOGLE_LPA = "com.google.android.euicc"

    /** The screens where SIM profiles are managed, best first. */
    fun simSettingsIntents(): List<Intent> =
        listOf(Intent("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))

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
        // Cut at whole code points, so a label never ends in half of a surrogate pair.
        val label = if (sensitive) "" else text.substring(0, text.offsetByCodePoints(0, min(CLIP_LABEL, text.codePointCount(0, text.length))))
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    /** How much of a copied text its clipboard label holds, in code points. */
    private const val CLIP_LABEL = 40

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

/**
 * What the system says about an intent: every activity that [matches] it, and [chosen], the one it
 * opens without asking, if any. The system's chooser resolves too when it would ask, but it is not
 * among the matches, so it is never [chosen].
 */
internal class Resolved(resolved: ActivityInfo?, val matches: List<ResolveInfo>) {
    val chosen: ActivityInfo? = resolved?.takeIf { activity ->
        matches.any { it.activityInfo.packageName == activity.packageName && it.activityInfo.name == activity.name }
    }
}
