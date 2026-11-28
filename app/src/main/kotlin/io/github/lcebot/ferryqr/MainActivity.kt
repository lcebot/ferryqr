package io.github.lcebot.ferryqr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import io.github.lcebot.ferryqr.ui.FerryApp
import io.github.lcebot.ferryqr.ui.FerryTheme

/** The scanner, under every other page of the app. */
class MainActivity : ComponentActivity() {

    private val vm by lazy { ViewModelProvider(this)[ScanViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        setContent {
            FerryTheme {
                FerryApp(vm)
            }
        }
        // An activity made again keeps the intent it was started with, which was taken up then.
        if (savedInstanceState == null) scanIfAsked(intent)
        addOnNewIntentListener { scanIfAsked(it) }
    }

    /** Back to scanning as closing a card does, when [intent] asks for the scanner and it shows something else. */
    private fun scanIfAsked(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SCAN, false) && vm.phase.value != Phase.Scanning) vm.resume()
    }

    companion object {
        /** Marks an intent that asks for the scanner itself, as the Quick Settings tile's does. */
        const val EXTRA_SCAN = "io.github.lcebot.ferryqr.extra.SCAN"
    }
}
