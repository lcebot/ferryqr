package io.github.lcebot.ferryqr

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.helper.WeChatHelper
import io.github.lcebot.ferryqr.history.HistoryStore
import io.github.lcebot.ferryqr.scan.AutoZoom
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.LiveAnalyzer
import io.github.lcebot.ferryqr.scan.Sighting
import io.github.lcebot.ferryqr.scan.WeChatEngine
import io.github.lcebot.ferryqr.scan.ZxingEngine
import io.github.lcebot.ferryqr.scan.StillResult
import io.github.lcebot.ferryqr.scan.readStillImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A code that was just dismissed is ignored until it has been out of view this long. */
private const val SUPPRESS_MS = 1500L

/** How long the notice that the WeChat helper is ready stays on screen. */
private const val HELPER_NOTICE_MS = 3000L

sealed interface Phase {
    data object Scanning : Phase
    data object Decoding : Phase
    /** [alreadyShown] holds codes whose markers were on screen before the frame froze. */
    data class Picking(
        val frame: Bitmap,
        val detections: List<Detection>,
        val alreadyShown: Set<String> = emptySet(),
    ) : Phase

    /** A live scan just succeeded; the screen confirms it on the code before handing it off. */
    data class Confirming(
        val detection: Detection,
        val frameWidth: Int,
        val frameHeight: Int,
        val wasShown: Boolean = false,
    ) : Phase
    data class Routing(val detection: Detection) : Phase

    /**
     * Probably a mini program code, which FerryQR cannot read, shown as [crop]. [live] is set when
     * the camera saw it, so WeChat's own scanner can still see it too.
     */
    data class MiniCodeFound(val crop: Bitmap, val live: Boolean) : Phase
    data class Showing(val card: ResultCard) : Phase
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val zxing = ZxingEngine()
    private val wechat = WeChatEngine(app)
    private val history = HistoryStore.get(app)

    private val _phase = MutableStateFlow<Phase>(Phase.Scanning)
    val phase = _phase.asStateFlow()

    private val _sighting = MutableStateFlow<Sighting?>(null)
    val sighting = _sighting.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    /** Bumped by [manualReset] so the camera is bound again from scratch. */
    private val _cameraSession = MutableStateFlow(0)
    val cameraSession = _cameraSession.asStateFlow()

    /** True for a few seconds after the optional WeChat helper answered, once per scanner start. */
    private val _helperNotice = MutableStateFlow(false)
    val helperNotice = _helperNotice.asStateFlow()

    /** Whether the WeChat helper answered, which decides how WeChat codes are handed over. */
    val helperReady = WeChatHelper.ready

    /** Set while a card for a live mini program code is shown, so dismissing it mutes that code. */
    @Volatile
    private var showingLiveMiniCode = false

    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val autoZoom = AutoZoom()
    val analyzer = LiveAnalyzer(
        zxing, wechat, autoZoom,
        onSighting = { _sighting.value = it },
        onHit = ::onLiveHit,
        onMiniCode = { crop ->
            showingLiveMiniCode = true
            _phase.value = Phase.MiniCodeFound(crop, live = true)
        },
    )

    init {
        viewModelScope.launch {
            if (WeChatHelper.probe(app)) {
                _helperNotice.value = true
                delay(HELPER_NOTICE_MS)
                _helperNotice.value = false
            }
        }
    }

    @Volatile
    private var suppressed: Set<String> = emptySet()

    @Volatile
    private var suppressedSeenAt = 0L

    private fun onLiveHit(frame: Bitmap, detections: List<Detection>, alreadyShown: Set<String>) {
        val now = SystemClock.elapsedRealtime()
        if (detections.all { it.text in suppressed } && now - suppressedSeenAt < SUPPRESS_MS) {
            suppressedSeenAt = now
            analyzer.paused = false
            return
        }
        suppressed = emptySet()
        _phase.value = if (detections.size == 1) {
            val single = detections.single()
            Phase.Confirming(single, frame.width, frame.height, single.text in alreadyShown).also { frame.recycle() }
        } else {
            Phase.Picking(frame, detections, alreadyShown)
        }
    }

    fun confirmed(detection: Detection) {
        if (_phase.value is Phase.Confirming) _phase.value = Phase.Routing(detection)
    }

    fun pick(detection: Detection) {
        _phase.value = Phase.Routing(detection)
    }

    fun record(detection: Detection) {
        history.add(detection.text, detection.format)
    }

    fun show(card: ResultCard) {
        _phase.value = Phase.Showing(card)
    }

    fun resume() {
        suppressed = when (val current = _phase.value) {
            is Phase.Showing -> setOf(current.card.raw)
            is Phase.Picking -> current.detections.mapTo(HashSet()) { it.text }
            else -> suppressed
        }
        suppressedSeenAt = SystemClock.elapsedRealtime()
        if (showingLiveMiniCode) {
            showingLiveMiniCode = false
            analyzer.muteMiniCode()
        }
        autoZoom.reset()
        _phase.value = Phase.Scanning
        analyzer.paused = false
    }

    /**
     * For a scanner that keeps failing: back to the base zoom even if the user zoomed, forget the
     * codes that were just dismissed, and bind the camera again.
     */
    fun manualReset() {
        suppressed = emptySet()
        suppressedSeenAt = 0L
        autoZoom.forceReset()
        wechat.retryIfFailed()
        analyzer.reset()
        _sighting.value = null
        _phase.value = Phase.Scanning
        _cameraSession.value++
        message(R.string.reset_done)
    }

    fun message(@StringRes text: Int) {
        _messages.tryEmit(text)
    }

    fun decodeImage(uri: Uri) {
        if (_phase.value is Phase.Decoding) return
        analyzer.paused = true
        _phase.value = Phase.Decoding
        viewModelScope.launch {
            when (val read = readStillImage(getApplication<Application>(), uri, zxing, wechat)) {
                StillResult.Unreadable -> {
                    message(R.string.image_unreadable)
                    resume()
                }
                is StillResult.Read -> when (read.detections.size) {
                    0 -> {
                        val crop = read.miniCodes.firstOrNull()?.crop(read.frame)
                        if (crop != null) {
                            _phase.value = Phase.MiniCodeFound(crop, live = false)
                        } else {
                            message(R.string.no_code_found)
                            resume()
                        }
                    }
                    1 -> {
                        _phase.value = Phase.Routing(read.detections.single())
                    }
                    else -> {
                        _phase.value = Phase.Picking(read.frame, read.detections)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        analysisExecutor.shutdown()
    }
}
