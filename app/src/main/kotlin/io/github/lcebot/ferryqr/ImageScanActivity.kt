package io.github.lcebot.ferryqr

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.lcebot.ferryqr.dispatch.Launch
import androidx.lifecycle.viewModelScope
import io.github.lcebot.ferryqr.history.HistoryStore
import io.github.lcebot.ferryqr.openin.OpenInModule
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.Engines
import io.github.lcebot.ferryqr.scan.StillResult
import io.github.lcebot.ferryqr.scan.readStillImage
import io.github.lcebot.ferryqr.ui.FerryTheme
import io.github.lcebot.ferryqr.ui.ImageScanSheet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Handles images shared to FerryQR or opened with it. It only shows a sheet over the app the image
 * came from; the scanner and the camera are never started.
 */
class ImageScanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        val uri = imageUri(intent)
        if (uri == null) {
            finish()
            return
        }
        val vm = ViewModelProvider(this)[ImageScanViewModel::class.java]
        vm.load(uri)
        setContent {
            FerryTheme {
                ImageScanSheet(vm, onClose = ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Launch.forgetApps()
    }

    private fun imageUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }
}

class ImageScanViewModel(app: Application) : AndroidViewModel(app) {

    private val zxing = Engines.zxing
    private val wechat = Engines.wechat(app)
    private val history = HistoryStore.get(app)
    private var loaded = false

    /** The code recorded last, which the activity shows again when it is recreated, as on rotation. */
    private var recorded: Detection? = null

    /** Null while the image is still being read. */
    private val _result = MutableStateFlow<StillResult?>(null)
    val result = _result.asStateFlow()

    /** The image is read once per activity, so a rotation shows the same result. */
    fun load(uri: Uri) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            _result.value = readStillImage(getApplication<Application>(), uri, zxing, wechat)
        }
        viewModelScope.launch { OpenInModule.refresh() }
    }

    /**
     * Records [detection] as the code the sheet shows. Switching to another code records that one,
     * as opening it would; showing the same code again, as a recreated activity does, records nothing.
     */
    fun shown(detection: Detection) {
        if (detection == recorded) return
        recorded = detection
        history.record(detection)
    }
}
