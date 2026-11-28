package io.github.lcebot.ferryqr.dispatch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.openin.OpenInModule

/**
 * An app that takes content for one purpose, called [name], and the [intent] that opens it there,
 * aimed at the app's own activity, so it reaches that app and no other.
 */
internal class AppCandidate(val pkg: String, val name: String, val intent: Intent)

/**
 * What one result card or one launch plan is worked out from. [ready] holds the packages of the apps
 * that take content through the module, read once, so every part of the card or plan goes by the same
 * value. What the package manager says about the apps is asked at most once each, since a card asks
 * about the same app from several of its parts, and a plan and the card built for the same code share
 * one env, and with it the order their apps are ranked in. What every page asks, the browsers and
 * what apps are called, is kept for the process by [InstalledApps].
 *
 * The answers are asked for as they are needed, each a call into another process, so an env is used
 * off the main thread and by one thread at a time; only [ready] may be read anywhere, as it asks nothing.
 */
class FerryEnv(val context: Context, val ready: Set<String> = OpenInModule.readyApps.value) {

    private val installed = HashMap<String, Boolean>()
    private val rankings = HashMap<Payload, List<Ferry>>()
    private val resolutions = HashMap<IntentKey, Resolved>()
    private val candidates = HashMap<List<IntentKey>, List<AppCandidate>>()

    /** The apps that open any web link. */
    internal val browsers: Set<String> by lazy(LazyThreadSafetyMode.NONE) { InstalledApps.browsers(context) }

    /** Whether the app [pkg] is installed. */
    internal fun installed(pkg: String): Boolean = installed.getOrPut(pkg) { PackageQueries.isInstalled(context, pkg) }

    /** What the app [pkg] is called, or the package itself for one that cannot be seen. */
    internal fun label(pkg: String): String = InstalledApps.labelOrPackage(context, pkg)

    /** The apps [payload] goes to, best first, as [Ferries.rank] orders them. */
    internal fun rank(payload: Payload): List<Ferry> = rankings.getOrPut(payload) { Ferries.rank(this, payload) }

    /** What the system says about [intent]: the activity it resolves to and every one that matches. */
    internal fun resolved(intent: Intent): Resolved = resolutions.getOrPut(IntentKey(intent)) { PackageQueries.resolved(context, intent) }

    /**
     * The app other than a browser that the system opens [intent] in without asking, such as one
     * with a verified App Link for a web link, which is where [Intents.nonBrowser] goes too. Null
     * when it goes to a browser, or when the system would ask, since no one app is chosen then.
     */
    internal fun handler(intent: Intent): String? =
        resolved(intent).chosen?.packageName?.takeIf { it != context.packageName && it !in browsers }

    /** Where [intent] goes: the app the system picks without asking, several it asks between, or none. */
    internal fun handling(intent: Intent): Handling {
        handler(intent)?.let { return Handling.App(it) }
        return if (resolved(intent).matches.isEmpty()) Handling.None else Handling.Asks
    }

    /**
     * The apps that take content through any of [chain], the ways in for one purpose, best first,
     * each app once with the first of its ways. When the system has a default app for one of the
     * ways, one it picks among several without asking, that app is the only one. A way only one app
     * takes has no default: counted as one, any app declaring the first way would take the content
     * unasked. Worked out once for each chain, so a plan and its card name each app once.
     */
    internal fun apps(chain: List<Intent>): List<AppCandidate> = candidates.getOrPut(chain.map(::IntentKey)) { candidatesOf(chain) }

    private fun candidatesOf(chain: List<Intent>): List<AppCandidate> {
        val own = context.packageName
        for (intent in chain) {
            val answer = resolved(intent)
            val default = answer.chosen?.takeIf { answer.matches.size > 1 && it.packageName != own }
            if (default != null) return listOf(candidate(intent, default))
        }
        return chain
            .flatMap { intent -> resolved(intent).matches.map { intent to it.activityInfo } }
            .filter { (_, activity) -> activity.packageName != own }
            .distinctBy { (_, activity) -> activity.packageName }
            .map { (intent, activity) -> candidate(intent, activity) }
    }

    private fun candidate(intent: Intent, activity: ActivityInfo): AppCandidate = AppCandidate(
        activity.packageName,
        label(activity.packageName),
        Intent(intent).setComponent(ComponentName(activity.packageName, activity.name)),
    )

    /**
     * An intent as the system resolves it, which leaves its extras out. A chain's extras carry the
     * content, which is the same for everything one env is asked about.
     */
    private class IntentKey(val intent: Intent) {
        override fun equals(other: Any?): Boolean = other is IntentKey && other.intent.filterEquals(intent)

        override fun hashCode(): Int = intent.filterHashCode()
    }
}
