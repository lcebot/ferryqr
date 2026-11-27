package io.github.lcebot.ferryqr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.lcebot.ferryqr.dispatch.InstalledApps
import io.github.lcebot.ferryqr.openin.OpenInModule

/**
 * The packages of the apps that take content, read so that a screen offering apps draws again once
 * the framework reports the module, which may come after the screen first drew, as when a shortcut
 * opens a page before the scanner ever ran. The framework is asked again as the screen appears,
 * since the scope may have changed in LSPosed since FerryQR last asked, and what is known of the
 * installed apps is let go, as the screen refreshes itself.
 */
@Composable
internal fun readyApps(): Set<String> {
    LaunchedEffect(Unit) {
        InstalledApps.invalidate()
        OpenInModule.refresh()
    }
    val apps by OpenInModule.readyApps.collectAsState()
    return apps
}
