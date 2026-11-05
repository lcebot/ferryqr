package io.github.lcebot.ferryqr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import io.github.lcebot.ferryqr.dispatch.Target
import io.github.lcebot.ferryqr.dispatch.Targets
import io.github.lcebot.ferryqr.openin.OpenInModule

/**
 * Whether each app takes content, read so that a screen offering apps draws again once the
 * framework reports the module, which may come after the screen first drew, as when a shortcut
 * opens a page before the scanner ever ran. The framework is asked again as the screen appears,
 * since the scope may have changed in LSPosed since FerryQR last asked.
 */
@Composable
internal fun targetsReady(): Map<Target, Boolean> {
    LaunchedEffect(Unit) { OpenInModule.refresh() }
    return Targets.all.associateWith { it.readyFlow.collectAsState().value }
}
