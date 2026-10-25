package com.lcebot.ferryqr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.lcebot.ferryqr.ui.FerryApp
import com.lcebot.ferryqr.ui.FerryTheme

class MainActivity : ComponentActivity() {

    private val vm by lazy { ViewModelProvider(this)[ScanViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            FerryTheme {
                FerryApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        vm.decodeImage(uri)
    }
}
