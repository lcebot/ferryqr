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
import io.github.lcebot.ferryqr.dispatch.ReadContent
import io.github.lcebot.ferryqr.history.HistoryStore
import io.github.lcebot.ferryqr.openin.OpenInModule
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.Engines
import io.github.lcebot.ferryqr.scan.StillOutcome
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
     * The codes read, each with its [contents] entry, read once for its card, its tile and history,
     * its [thumbnails] entry for the chooser and, for a mini program code only located, its
     * [locatedCrops] entry for WeChat, null where there is none; [miniCrop] is the one mini program
     * code located when nothing was read.
     */
    class Read(
        val detections: List<Detection>,
        val contents: List<ReadContent>,
        val thumbnails: List<Bitmap?>,
        val locatedCrops: List<Bitmap?>,
        val miniCrop: Bitmap?,
    ) : ImageScan

    companion object {
        fun of(outcome: StillOutcome): ImageScan = when (outcome) {
            StillOutcome.Unreadable -> Unreadable
            StillOutcome.Empty -> Read(emptyList(), emptyList(), emptyList(), emptyList(), miniCrop = null)
            is StillOutcome.LocatedMini -> Read(emptyList(), emptyList(), emptyList(), emptyList(), miniCrop = outcome.crop)
            is StillOutcome.Codes -> {
                val frame = outcome.frame
                val detections = outcome.detections
                val many = detections.size > 1
                Read(
                    detections = detections,
                    contents = detections.map { ReadContent.of(it.text, it.format) },
                    thumbnails = detections.map { if (many) it.thumbnail(frame) else null },
                    locatedCrops = detections.map { if (it.isLocatedMiniCode) it.locatedCrop(frame) else null },
                    miniCrop = null,
                ).also { outcome.release() }
            }
        }
    }
}

class ImageScanViewModel(app: Application) : AndroidViewModel(app) {

    private val zxing = Engines.zxing
    private val wechat = Engines.wechat(app)
    private val history = HistoryStore.get(app)
    private var loaded = false

    /** The code recorded last, which the activity shows again when it is recreated, as on rotation. */
    private var recorded: ReadContent? = null

    /** Null while the image is still being read. */
    private val _result = MutableStateFlow<ImageScan?>(null)
    val result = _result.asStateFlow()

    /** The image is read once per activity, so a rotation shows the same result. */
    fun load(uri: Uri) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val outcome = readStillImage(getApplication<Application>(), uri, zxing, wechat)
            // Cutting the thumbnails and crops from the image takes a moment, so it stays off the main thread.
            _result.value = withContext(Dispatchers.Default) { ImageScan.of(outcome) }
        }
        viewModelScope.launch { OpenInModule.refresh() }
    }

    /**
     * Records [content] as the code the sheet shows. Switching to another code records that one,
     * as opening it would; showing the same code again, as a recreated activity does, records nothing.
     */
    fun shown(content: ReadContent) {
        if (content == recorded) return
        recorded = content
        history.record(content)
    }
}
