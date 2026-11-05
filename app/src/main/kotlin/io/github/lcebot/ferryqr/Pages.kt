package io.github.lcebot.ferryqr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.core.view.WindowCompat
import io.github.lcebot.ferryqr.history.FavoriteShortcuts
import io.github.lcebot.ferryqr.ui.FerryTheme
import io.github.lcebot.ferryqr.ui.GeneratorScreen
import io.github.lcebot.ferryqr.ui.HistoryScreen
import io.github.lcebot.ferryqr.ui.SettingsScreen
import io.github.lcebot.ferryqr.ui.SystemBars

/**
 * A page above the scanner. Each is an activity of its own, so going back from it plays the
 * system's own predictive back animation, and its back arrow simply finishes it.
 */
abstract class PageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        setContent {
            FerryTheme {
                SystemBars()
                Page()
            }
        }
    }

    @Composable
    protected abstract fun Page()
}

/**
 * Scan history. A favorite's app shortcut opens it on top of the scanner, with the favorite's
 * content to show, so going back still leads to the scanner.
 */
class HistoryActivity : PageActivity() {
    /** Shown once; after a configuration change it has already been handled. */
    private var openText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null && intent?.action == FavoriteShortcuts.ACTION_SHOW) {
            openText = intent.getStringExtra(FavoriteShortcuts.EXTRA_TEXT)
        }
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun Page() = HistoryScreen(onBack = ::finish, openText = openText, onOpened = { openText = null })
}

class GeneratorActivity : PageActivity() {
    @Composable
    override fun Page() = GeneratorScreen(onBack = ::finish)
}

class SettingsActivity : PageActivity() {
    @Composable
    override fun Page() = SettingsScreen(onBack = ::finish)
}
