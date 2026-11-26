package io.github.lcebot.ferryqr

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import io.github.lcebot.ferryqr.history.FavoriteShortcuts
import io.github.lcebot.ferryqr.ui.FerrySettingsScreen
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
                MatchWindowBackground()
                SystemBars()
                Page()
            }
        }
    }

    /**
     * On Android 12 and 13 the dynamic scheme's background is a tone no system colour resource
     * names, so the window, which shows through while the page opens and around it during a back
     * gesture, takes the exact colour from the composed theme. Elsewhere the resource already is it.
     */
    @Composable
    private fun MatchWindowBackground() {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.S..Build.VERSION_CODES.TIRAMISU) return
        val background = MaterialTheme.colorScheme.background
        LaunchedEffect(background) { window.setBackgroundDrawable(ColorDrawable(background.toArgb())) }
    }

    @Composable
    protected abstract fun Page()
}

/**
 * Scan history. A favorite's app shortcut opens it on top of the scanner, with the favorite's
 * content to show, so going back still leads to the scanner.
 */
class HistoryActivity : PageActivity() {
    private val vm by lazy { ViewModelProvider(this)[HistoryViewModel::class.java] }

    /** Shown once; after a configuration change it has already been handled. */
    private var openText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null && intent?.action == FavoriteShortcuts.ACTION_SHOW) {
            openText = intent.getStringExtra(FavoriteShortcuts.EXTRA_TEXT)
        }
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun Page() = HistoryScreen(vm, onBack = ::finish, openText = openText, onOpened = { openText = null })
}

class GeneratorActivity : PageActivity() {
    @Composable
    override fun Page() = GeneratorScreen(onBack = ::finish)
}

class SettingsActivity : PageActivity() {
    @Composable
    override fun Page() = SettingsScreen(
        onBack = ::finish,
        onOpenFerry = { startActivity(Intent(this, FerrySettingsActivity::class.java)) },
    )
}

/** How codes reach other apps through the module, a page of its own under the settings. */
class FerrySettingsActivity : PageActivity() {
    @Composable
    override fun Page() = FerrySettingsScreen(onBack = ::finish)
}
