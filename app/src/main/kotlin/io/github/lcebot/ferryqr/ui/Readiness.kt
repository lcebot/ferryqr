package io.github.lcebot.ferryqr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.lcebot.ferryqr.dispatch.InstalledApps
import io.github.lcebot.ferryqr.openin.OpenInModule
import kotlinx.coroutines.launch

/**
 * Asks again what decides where content can go each time the screen comes back: what is known of
 * the installed apps is let go, and the framework is asked about the module, since coming back from
 * LSPosed, or from an app that restarted, the scope may have changed, and an app may have been
 * installed or removed meanwhile. [alsoRead] runs on each of those returns as well, after the
 * installed apps are let go and alongside the framework's answer, for what else the screen reads.
 */
@Composable
internal fun RefreshAppFactsOnResume(alsoRead: suspend () -> Unit = {}) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val read by rememberUpdatedState(alsoRead)
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            InstalledApps.invalidate()
            launch { OpenInModule.refresh() }
            read()
        }
    }
}

/**
 * The packages of the apps that take content, read so that a screen offering apps draws again once
 * the framework reports the module, which may come after the screen first drew, as when a shortcut
 * opens a page before the scanner ever ran.
 */
@Composable
internal fun readyApps(): Set<String> {
    RefreshAppFactsOnResume()
    val apps by OpenInModule.readyApps.collectAsState()
    return apps
}
