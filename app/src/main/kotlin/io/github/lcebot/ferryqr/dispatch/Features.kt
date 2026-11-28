package io.github.lcebot.ferryqr.dispatch

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.lcebot.ferryqr.R

/*
 * What the ferry page lists besides the apps FerryQR reaches its own way: the features the system
 * provides for a kind of code, and the kinds of content it hands to whichever app is chosen for
 * them. Neither is ranked among the ferries; result cards reach them through actions of their own.
 */

/** A feature the system provides for a kind of code, which a device has or lacks. */
internal class SystemFeature(
    @StringRes val name: Int,
    @StringRes val summary: Int,
    @DrawableRes val icon: Int,
    val supported: (FerryEnv) -> Boolean,
)

internal object SystemFeatures {
    val all = listOf(
        SystemFeature(R.string.feature_esim, R.string.feature_esim_summary, R.drawable.ic_sim_card) { Launch.esimSupported(it.context) },
        // Anything that takes passkey requests, asked with the forms a scanned request is handed over in.
        SystemFeature(R.string.feature_passkey, R.string.feature_passkey_summary, R.drawable.ic_key) { env ->
            Intents.fido("FIDO:/").any { env.resolved(it).matches.isNotEmpty() }
        },
        // Joining a network takes nothing beyond Android itself, in every version FerryQR runs on.
        SystemFeature(R.string.feature_wifi, R.string.feature_wifi_summary, R.drawable.ic_wifi) { true },
        SystemFeature(R.string.feature_dpp, R.string.feature_dpp_summary, R.drawable.ic_wifi) { Launch.dppSupported(it.context) },
    )
}

/** Where a kind of content goes: the app the system picks without asking, several it asks between, or none. */
internal sealed interface Handling {
    data class App(val pkg: String) : Handling
    data object Asks : Handling
    data object None : Handling
}

/** A kind of content the system hands to the app chosen for it, such as phone numbers to the dialer. */
internal class DefaultApp(@StringRes val name: Int, @DrawableRes val icon: Int, private val intent: () -> Intent) {
    /** Where the content goes now, asked with the same intent its card's action sends. */
    fun handling(env: FerryEnv): Handling = env.handling(intent())
}

internal object DefaultApps {
    val all = listOf(
        DefaultApp(R.string.default_contacts, R.drawable.ic_person_add) { Intents.newContact() },
        DefaultApp(R.string.default_events, R.drawable.ic_event) { Intents.newEvent() },
        DefaultApp(R.string.default_mail, R.drawable.ic_mail) { Intents.link("mailto:") },
        DefaultApp(R.string.default_sms, R.drawable.ic_sms) { Intents.link("smsto:") },
        DefaultApp(R.string.default_phone, R.drawable.ic_call) { Intents.link("tel:") },
        DefaultApp(R.string.default_maps, R.drawable.ic_location_on) { Intents.link("geo:0,0") },
    )
}
