package io.github.lcebot.ferryqr

import android.app.Application
import android.os.LocaleList
import io.github.lcebot.ferryqr.dispatch.CardFactory
import io.github.lcebot.ferryqr.dispatch.CardSource
import io.github.lcebot.ferryqr.dispatch.FerryEnv
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.openin.OpenInModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/**
 * The result card a view model shows, built for the [CardSource] it is given and built again
 * whenever what the card offers may change: the apps that take content through the module, or the
 * language the app shows text in. Each build runs off the main thread, and one that a newer change
 * overtakes is dropped. A card already built for the source as things stand is not built again.
 *
 * A card handed over already built shows in the same frame as whatever turns to show it, since
 * [card] holds it as soon as [show] returns; a card that a flow built would only arrive a dispatch
 * later.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardHolder(
    private val app: Application,
    private val scope: CoroutineScope,
    private val languages: StateFlow<LocaleList> = app.languages(scope),
) {

    /** A source asked for. Each is its own, so asking for a source again is told from the last time. */
    private class Request(val source: CardSource?)

    /** What a card was built under. */
    private data class Basis(val ready: Set<String>, val languages: LocaleList)

    private class Built(val request: Request, val basis: Basis, val card: ResultCard)

    /** Guards [requests], [builtFor] and [_card] together, as [show] may be called off the main thread. */
    private val lock = Any()
    private val requests = MutableStateFlow(Request(null))

    /** What the card held for the request asked last was built under, or null while it has none. */
    private var builtFor: Basis? = null

    private val _card = MutableStateFlow<ResultCard?>(null)

    /**
     * The card for the source shown. While it is built, it is null, or the card of the source before
     * when that is kept; without a source, it is null.
     */
    val card: StateFlow<ResultCard?> = _card.asStateFlow()

    init {
        scope.launch {
            combine(requests, OpenInModule.readyApps, languages) { request, ready, locales -> request to Basis(ready, locales) }
                .mapLatest { (request, basis) ->
                    val source = request.source ?: return@mapLatest null
                    if (synchronized(lock) { request === requests.value && builtFor == basis }) return@mapLatest null
                    Built(request, basis, CardFactory.build(FerryEnv(app, basis.ready), source))
                }
                .flowOn(Dispatchers.Default)
                .collect { built ->
                    if (built == null) return@collect
                    synchronized(lock) {
                        // A card built for a source asked before the one shown now is not shown, nor
                        // one that a newer change overtook while it was on its way here, which a
                        // build that cannot be stopped midway may still hand on.
                        val basis = Basis(OpenInModule.readyApps.value, languages.value)
                        if (built.request === requests.value && built.basis == basis) {
                            builtFor = built.basis
                            _card.value = built.card
                        }
                    }
                }
        }
    }

    /**
     * Shows the card for [source], or none. The source shown already is not built again, and the
     * framework is asked again as a card appears, since the scope may have changed in LSPosed since
     * FerryQR last asked. [keep] lets the card shown stay until the one for [source] is built, for a
     * source that takes the place of the one before on the same sheet, so the sheet never empties.
     */
    fun show(source: CardSource?, keep: Boolean = false) {
        synchronized(lock) {
            if (source == requests.value.source) return
            requests.value = Request(source)
            builtFor = null
            if (source == null || !keep) _card.value = null
        }
        if (source != null) scope.launch { OpenInModule.refresh() }
    }

    /** Shows [card], already built for [source] from [env], and builds it again only once that changes. */
    fun show(source: CardSource, card: ResultCard, env: FerryEnv) {
        synchronized(lock) {
            requests.value = Request(source)
            builtFor = Basis(env.ready, languages.value)
            _card.value = card
        }
        scope.launch { OpenInModule.refresh() }
    }
}
