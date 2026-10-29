package com.lcebot.ferryqr

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
import androidx.lifecycle.viewModelScope
import com.lcebot.ferryqr.helper.WeChatHelper
import com.lcebot.ferryqr.scan.StillResult
import com.lcebot.ferryqr.scan.WeChatEngine
import com.lcebot.ferryqr.scan.ZxingEngine
import com.lcebot.ferryqr.scan.readStillImage
import com.lcebot.ferryqr.ui.FerryTheme
import com.lcebot.ferryqr.ui.ImageScanSheet
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

    private fun imageUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }
}

class ImageScanViewModel(app: Application) : AndroidViewModel(app) {

    private val zxing = ZxingEngine()
    private val wechat = WeChatEngine(app)
    private var loaded = false

    /** Null while the image is still being read. */
    private val _result = MutableStateFlow<StillResult?>(null)
    val result = _result.asStateFlow()

    /** Whether the WeChat helper answered, which decides how WeChat codes are handed over. */
    val helperReady = WeChatHelper.ready

    /** The image is read once per activity, so a rotation shows the same result. */
    fun load(uri: Uri) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            _result.value = readStillImage(getApplication<Application>(), uri, zxing, wechat)
        }
        viewModelScope.launch { WeChatHelper.probe(getApplication()) }
    }
}
