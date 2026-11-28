package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * What the package manager says about the installed apps. Each answer is a call into another
 * process, asked afresh on every call, so it is reached only through [FerryEnv], which keeps the
 * answers for one card or plan, and [InstalledApps], which keeps those every page asks for the whole
 * process. The manifest queries for the apps FerryQR offers and for every app with links, which is
 * what lets them be seen here.
 */
internal object PackageQueries {

    private fun query(context: Context, intent: Intent): List<ResolveInfo> {
        val packages = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packages.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    private fun resolve(context: Context, intent: Intent): ResolveInfo? {
        val packages = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packages.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    /** The apps that open any web link, which is what makes an app a browser. */
    fun browsers(context: Context): Set<String> = query(context, Intents.anyWebLink()).mapTo(HashSet()) { it.activityInfo.packageName }

    /** The app the system opens any web link in without asking, which may be the system's chooser. */
    fun defaultForWebLinks(context: Context): String? = resolve(context, Intents.anyWebLink())?.activityInfo?.packageName

    /** What the system says about [intent]: the activity it resolves to and every one that matches. */
    fun resolved(context: Context, intent: Intent): Resolved = Resolved(resolve(context, intent)?.activityInfo, query(context, intent))

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** The icon of the installed app [packageName], or null for an app that is not visible or not installed. */
    fun appIcon(context: Context, packageName: String): Drawable? = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
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
