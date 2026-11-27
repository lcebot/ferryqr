package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.openin.OpenInModule

/**
 * What one result card or one launch plan is worked out from. [ready] holds the packages of the apps
 * that take content through the module, read once, so every part of the card or plan goes by the same
 * value. What the package manager says about the apps is asked at most once each, since a card asks
 * about the same app from several of its parts, and a plan and the card built for the same code share
 * one env, and with it the order their apps are ranked in.
 *
 * The answers are asked for as they are needed, each a call into another process, so an env is used
 * off the main thread and by one thread at a time; only [ready] may be read anywhere, as it asks nothing.
 */
class FerryEnv(val context: Context, val ready: Set<String> = OpenInModule.readyApps.value) {

    private val installed = HashMap<String, Boolean>()
    private val rankings = HashMap<Payload, List<Ferry>>()

    /** The apps that open any web link. */
    internal val browsers: Set<String> by lazy(LazyThreadSafetyMode.NONE) { InstalledApps.browsers(context) }

    /** Whether the app [pkg] is installed. */
    internal fun installed(pkg: String): Boolean = installed.getOrPut(pkg) { Launch.isInstalled(context, pkg) }

    /** The apps [payload] goes to, best first, as [Ferries.rank] orders them. */
    internal fun rank(payload: Payload): List<Ferry> = rankings.getOrPut(payload) { Ferries.rank(this, payload) }
}
