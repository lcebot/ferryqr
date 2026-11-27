package io.github.lcebot.ferryqr.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import io.github.lcebot.ferryqr.dispatch.CardFactory
import io.github.lcebot.ferryqr.dispatch.CardSource
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.openin.OpenInModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/**
 * The result card for [source], built off the main thread for the apps that take content now and
 * again whenever that changes, since what a card offers depends on it. A build that a newer change
 * overtakes is dropped. [builtFor] is the readiness a card already on screen was built for, which
 * is not built again while it still holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun cardFlow(context: Context, source: CardSource, builtFor: Set<String>? = null): Flow<ResultCard> =
    OpenInModule.readyApps
        .dropWhile { it == builtFor }
        .mapLatest { CardFactory.build(context, source, it) }
        .flowOn(Dispatchers.Default)

/**
 * The packages of the apps that take content, read so that a screen offering apps draws again once
 * the framework reports the module, which may come after the screen first drew, as when a shortcut
 * opens a page before the scanner ever ran. The framework is asked again as the screen appears,
 * since the scope may have changed in LSPosed since FerryQR last asked.
 */
@Composable
internal fun readyApps(): Set<String> {
    LaunchedEffect(Unit) { OpenInModule.refresh() }
    val apps by OpenInModule.readyApps.collectAsState()
    return apps
}

/**
 * The result card for [source], as [cardFlow] builds it; the card shown stays until the next one is
 * ready, and it is null before the first is, or without a source. [initial], a card already built
 * for [source], shows from the first frame, and [builtFor] is the readiness it was built for, so it
 * is built again only once that changes. The framework is asked again as the card appears, as
 * [readyApps] does.
 */
@Composable
internal fun rememberResultCard(
    source: CardSource?,
    initial: ResultCard? = null,
    builtFor: Set<String>? = null,
): ResultCard? {
    val context = LocalContext.current
    LaunchedEffect(Unit) { OpenInModule.refresh() }
    val card by produceState<ResultCard?>(initial, source, initial, builtFor) {
        if (initial != null) value = initial
        if (source == null) {
            value = null
            return@produceState
        }
        // The readiness only holds back building the initial card again; with none, the first must come.
        cardFlow(context, source, builtFor.takeIf { initial != null }).collect { value = it }
    }
    return card
}
