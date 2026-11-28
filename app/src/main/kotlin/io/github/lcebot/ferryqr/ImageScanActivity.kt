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
import io.github.lcebot.ferryqr.dispatch.CardSource
import io.github.lcebot.ferryqr.dispatch.Origin
import io.github.lcebot.ferryqr.dispatch.ReadContent
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.history.HistoryStore
import io.github.lcebot.ferryqr.scan.Found
import io.github.lcebot.ferryqr.scan.thumbnail
import io.github.lcebot.ferryqr.ui.FerryTheme
import io.github.lcebot.ferryqr.ui.ImageScanSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
     * The [codes] found, each with its crop when it was only located and, when there are several, its
     * thumbnail; or with none, [miniCrop], the one mini program code located when nothing was read.
     */
    class Read(val codes: List<StillCode>, val miniCrop: Bitmap?) : ImageScan

    companion object {
        fun of(scan: StillScan): ImageScan = when (scan) {
            StillScan.Unreadable -> Unreadable
            StillScan.Empty -> Read(emptyList(), miniCrop = null)
            is StillScan.LocatedMini -> Read(emptyList(), miniCrop = scan.crop)
            is StillScan.Codes -> {
                val frame = scan.frame.bitmap
                val kept = scan.codes.mapNotNull { code ->
                    // A located code that cannot be cut from the image would leave WeChat nothing to scan.
                    val crop = (code.found as? Found.Located)?.let { it.crop(frame) ?: return@mapNotNull null }
                    code to crop
                }
                // Thumbnails are cut only for the chooser, which shows only for more than one code.
                val many = kept.size > 1
                Read(
                    codes = kept.map { (code, crop) ->
                        StillCode(code.found, code.content, if (many) code.found.thumbnail(frame) else null, crop)
                    },
                    miniCrop = null,
                ).also { scan.release() }
            }
        }
    }
}

class ImageScanViewModel(app: Application) : AndroidViewModel(app) {

    private val stills = StillReader(app)
    private val history = HistoryStore.get(app)
    private var loaded = false

    /**
     * The content of the code shown last, null for a mini program code only located, which a
     * recreated activity shows again, as after a change of theme or language, without recording it twice.
     */
    private var recorded: ReadContent? = null

    /** The read image and the index of the code its sheet shows, null for its mini program code. */
    private var shown: Pair<ImageScan.Read, Int?>? = null

    /** Null while the image is still being read. */
    private val _result = MutableStateFlow<ImageScan?>(null)
    val result = _result.asStateFlow()

    private val _outcomes = Channel<ImageScan>(Channel.BUFFERED)

    /**
     * The image as it is read, once, for the sheet to let the user feel how the reading went. Kept
     * until the sheet takes it, and never sent again, so a sheet made again, as after a change of theme or language, does
     * not play it a second time.
     */
    val outcomes = _outcomes.receiveAsFlow()

    private val cards = CardHolder(app, viewModelScope)

    /** The card of the code the sheet shows. */
    val card: StateFlow<ResultCard?> = cards.card

    /** The image is read once per activity, so an activity made again shows the same result. */
    fun load(uri: Uri) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val scan = stills.read(uri)
            // Cutting the thumbnails and crops from the image takes a moment, so it stays off the main thread.
            val read = withContext(Dispatchers.Default) { ImageScan.of(scan) }
            _result.value = read
            _outcomes.send(read)
        }
    }

    /**
     * Shows the card of the code at [index] among those [scan] read, or with no index, the card of
     * the mini program code located when nothing was read. Showing the same code again, as a
     * recreated activity does, neither builds its card again nor records it. Switching to another
     * code records that one, as opening it would, and keeps the card shown until the next is built,
     * so the sheet never empties. A mini program code that could not be read has no content to
     * route; WeChat gets its picture.
     */
    fun show(scan: ImageScan.Read, index: Int?) {
        if (shown?.let { (read, at) -> read === scan && at == index } == true) return
        shown = scan to index
        val source = if (index != null) {
            val code = scan.codes[index]
            val content = code.content
            if (content != recorded) {
                recorded = content
                if (content != null) history.record(content)
            }
            content?.let { CardSource.Read(it, Origin.IMAGE) } ?: code.crop?.let { CardSource.LocatedMini(it, Origin.IMAGE) }
        } else {
            scan.miniCrop?.let { CardSource.LocatedMini(it, Origin.IMAGE) }
        }
        cards.show(source, keep = true)
    }
}
