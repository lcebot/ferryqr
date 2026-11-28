package io.github.lcebot.ferryqr.dispatch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.PersistableBundle
import android.telephony.euicc.EuiccManager
import io.github.lcebot.ferryqr.content.CalendarEvent
import io.github.lcebot.ferryqr.content.Contact
import kotlin.math.min

/** Hands content over: starts the activities [Intents] makes, and fills the clipboard. */
internal object Launch {

    fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** Opens [url] in a browser, even when an app holds a verified App Link for it. */
    fun browser(activity: Activity, url: String): Boolean = start(activity, Intents.browser(url, InstalledApps.browser(activity)))

    /** Succeeds only when an app other than a browser claims the link, such as a verified App Link. */
    fun nonBrowser(activity: Activity, url: String) = start(activity, Intents.nonBrowser(url))

    /**
     * Opens an app link through whatever app registers it, or for an `intent:` URI that nothing
     * opens, the web page it names as a fallback.
     */
    fun scheme(activity: Activity, uri: String, scheme: String): Boolean {
        val intent = Intents.scheme(uri, scheme) ?: return false
        if (start(activity, intent)) return true
        if (scheme != "intent") return false
        val fallbackUri = intent.getStringExtra("browser_fallback_url")?.let(Uri::parse)
            ?.takeIf { it.scheme.equals("https", true) || it.scheme.equals("http", true) }
            ?: return false
        return start(activity, Intents.browsable(fallbackUri))
    }

    /** Whether the phone has an eSIM it can add profiles to. */
    fun esimSupported(context: Context): Boolean = context.getSystemService(EuiccManager::class.java)?.isEnabled == true

    /**
     * Whether the phone supports Wi-Fi Easy Connect. Asking needs ACCESS_WIFI_STATE, which some
     * systems let the user take away; without it, the feature counts as missing.
     */
    fun dppSupported(context: Context): Boolean = try {
        context.getSystemService(WifiManager::class.java)?.isEasyConnectSupported == true
    } catch (e: SecurityException) {
        false
    }

    fun dpp(activity: Activity, raw: String): Boolean = dppSupported(activity) && start(activity, Intents.dpp(raw))

    fun connectWifi(activity: Activity, suggestion: WifiNetworkSuggestion): Boolean = start(activity, Intents.addWifi(suggestion))

    fun addContact(activity: Activity, contact: Contact): Boolean = start(activity, Intents.newContact(contact))

    fun addEvent(activity: Activity, event: CalendarEvent): Boolean = start(activity, Intents.newEvent(event))

    fun search(activity: Activity, text: String): Boolean = start(activity, Intents.search(text))

    fun share(activity: Activity, text: String): Boolean = start(activity, Intents.share(text))

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
}
