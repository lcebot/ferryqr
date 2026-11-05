package io.github.lcebot.ferryqr.ui

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
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
    // One page at a time: a second tap before the first page covers the scanner is ignored, and
    // the scanner takes taps again once it is back in front.
    var opening by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        opening = false
        onPauseOrDispose {}
    }
    fun open(page: Class<*>) {
        if (opening) return
        opening = true
        context.startActivity(Intent(context, page))
    }
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
