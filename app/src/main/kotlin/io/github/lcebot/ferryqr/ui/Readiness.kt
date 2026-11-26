package io.github.lcebot.ferryqr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.lcebot.ferryqr.dispatch.ActiveFerries
import io.github.lcebot.ferryqr.dispatch.ActiveFerry
import io.github.lcebot.ferryqr.dispatch.CardFactory
import io.github.lcebot.ferryqr.dispatch.CardSource
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.dispatch.Route
import io.github.lcebot.ferryqr.openin.OpenInModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether each app takes content, read so that a screen offering apps draws again once the
 * framework reports the module, which may come after the screen first drew, as when a shortcut
 * opens a page before the scanner ever ran. The framework is asked again as the screen appears,
 * since the scope may have changed in LSPosed since FerryQR last asked.
 */
@Composable
internal fun ferriesReady(): Map<ActiveFerry, Boolean> {
    LaunchedEffect(Unit) { OpenInModule.refresh() }
    val apps by OpenInModule.readyApps.collectAsState()
    return remember(apps) { ActiveFerries.all.associateWith { it.route != Route.MODULE || it.pkg in apps } }
}

/**
 * The result card for [source], built off the main thread and again whenever an app's readiness
 * changes; the card shown stays until the next one is ready, and it is null before the first is,
 * or without a source.
 */
@Composable
internal fun rememberResultCard(source: CardSource?): ResultCard? {
    val context = LocalContext.current
    val ready = ferriesReady()
    val card by produceState<ResultCard?>(null, source, ready) {
        value = source?.let { withContext(Dispatchers.Default) { CardFactory.build(context, it) } }
    }
    return card
}
