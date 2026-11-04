package io.github.lcebot.ferryqr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import io.github.lcebot.ferryqr.history.FavoriteShortcuts
import io.github.lcebot.ferryqr.ui.FerryApp
import io.github.lcebot.ferryqr.ui.FerryTheme

class MainActivity : ComponentActivity() {

    private val vm by lazy { ViewModelProvider(this)[ScanViewModel::class.java] }

    /** A favorite's content from its app shortcut, waiting to be shown. */
    private val openFavorite = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        // After a configuration change the shortcut has already been handled.
        if (savedInstanceState == null) openFavorite.value = favoriteText(intent)
        setContent {
            FerryTheme {
                FerryApp(vm, openFavorite.value) { openFavorite.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        favoriteText(intent)?.let { openFavorite.value = it }
    }

    private fun favoriteText(intent: Intent?): String? =
        if (intent?.action == FavoriteShortcuts.ACTION_SHOW) intent.getStringExtra(FavoriteShortcuts.EXTRA_TEXT) else null
}
