package io.github.lcebot.ferryqr

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
    }
}
