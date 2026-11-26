package io.github.lcebot.ferryqr

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.lcebot.ferryqr.dispatch.Launch
import io.github.lcebot.ferryqr.history.HistoryStore
import io.github.lcebot.ferryqr.openin.OpenInModule
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.Engines
import io.github.lcebot.ferryqr.scan.StillResult
import io.github.lcebot.ferryqr.scan.isLocatedMiniCode
import io.github.lcebot.ferryqr.scan.locatedCrop
import io.github.lcebot.ferryqr.scan.readStillImage
import io.github.lcebot.ferryqr.scan.thumbnail
import io.github.lcebot.ferryqr.ui.FerryTheme
import io.github.lcebot.ferryqr.ui.ImageScanSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/**
 * A shared or opened image as its sheet shows it. Read, it keeps only the pictures the sheet needs,
 * cut from the image as soon as it is read: the image itself, up to a few dozen megabytes, goes.
 */
sealed interface ImageScan {
    data object Unreadable : ImageScan

    /**
     * The codes read, each with its [thumbnails] entry for the chooser and, for a mini program code
     * only located, its [locatedCrops] entry for WeChat, null where there is none; [miniCrop] is the
     * one mini program code located when nothing was read.
     */
    class Read(
        val detections: List<Detection>,
        val thumbnails: List<Bitmap?>,
        val locatedCrops: List<Bitmap?>,
        val miniCrop: Bitmap?,
    ) : ImageScan

    companion object {
        fun of(read: StillResult.Read): Read {
            val frame = read.frame
            val many = read.detections.size > 1
            // A cut covering the whole image is the image itself, which is about to go, so it is copied.
            fun Bitmap?.own(): Bitmap? = this?.let { if (it === frame) it.copy(Bitmap.Config.ARGB_8888, false) else it }
            return Read(
                detections = read.detections,
                thumbnails = read.detections.map { if (many) it.thumbnail(frame).own() else null },
                locatedCrops = read.detections.map { if (it.isLocatedMiniCode) it.locatedCrop(frame).own() else null },
                miniCrop = if (read.detections.isEmpty()) read.miniCodes.firstOrNull()?.crop(frame).own() else null,
            ).also { frame.recycle() }
        }
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
    private val _result = MutableStateFlow<ImageScan?>(null)
    val result = _result.asStateFlow()

    /** The image is read once per activity, so a rotation shows the same result. */
    fun load(uri: Uri) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            _result.value = when (val read = readStillImage(getApplication<Application>(), uri, zxing, wechat)) {
                StillResult.Unreadable -> ImageScan.Unreadable
                is StillResult.Read -> withContext(Dispatchers.Default) { ImageScan.of(read) }
            }
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
