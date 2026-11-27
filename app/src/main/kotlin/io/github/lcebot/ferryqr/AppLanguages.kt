package io.github.lcebot.ferryqr

import android.app.Application
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.os.LocaleList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The languages the app shows text in, for as long as [scope] lasts. A view model outlives its page
 * across a change of language, while what it made holds words in the language it was made in, so it
 * follows the language itself and makes them again when it changes. This listens on the application,
 * which hears of the change with its resources already in the new language and before the page is
 * made again, so what is made in the new language is on its way by the time the page shows. A page
 * passing in its own language would be heard only once made again, and would show the old words
 * until then.
 */
internal fun Application.languages(scope: CoroutineScope): StateFlow<LocaleList> = callbackFlow<LocaleList> {
    val changes = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            trySend(newConfig.locales)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
    }
    registerComponentCallbacks(changes)
    awaitClose { unregisterComponentCallbacks(changes) }
}.stateIn(scope, SharingStarted.Eagerly, resources.configuration.locales)
