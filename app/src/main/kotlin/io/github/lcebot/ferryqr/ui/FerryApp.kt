package io.github.lcebot.ferryqr.ui

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.lcebot.ferryqr.GeneratorActivity
import io.github.lcebot.ferryqr.HistoryActivity
import io.github.lcebot.ferryqr.ScanViewModel
import io.github.lcebot.ferryqr.SettingsActivity

/**
 * The scanner. History, the create page and settings are activities of their own above it, so the
 * system plays its own predictive back animation between them, and the camera runs only while the
 * scanner is visible.
 */
@Composable
fun FerryApp(vm: ScanViewModel) {
    val context = LocalContext.current
    val once = rememberOpenOnce()
    fun open(page: Class<*>) = once { context.startActivity(Intent(context, page)) }
    // The camera view is always dark.
    SystemBars(light = false)
    ScanScreen(
        vm = vm,
        onOpenHistory = { open(HistoryActivity::class.java) },
        onOpenGenerator = { open(GeneratorActivity::class.java) },
        onOpenSettings = { open(SettingsActivity::class.java) },
    )
}

/** Bar icons dark on a light page and light on a dark one; pages other than the scanner follow the theme. */
@Composable
fun SystemBars(light: Boolean = !isSystemInDarkTheme()) {
    val window = LocalActivity.current?.window
    val view = LocalView.current
    SideEffect {
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
}
