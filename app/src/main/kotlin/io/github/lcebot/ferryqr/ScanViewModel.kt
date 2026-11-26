package io.github.lcebot.ferryqr

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.dispatch.Cards
import io.github.lcebot.ferryqr.dispatch.Dispatcher
import io.github.lcebot.ferryqr.dispatch.Origin
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.history.HistoryStore
import io.github.lcebot.ferryqr.openin.OpenInModule
import io.github.lcebot.ferryqr.scan.AutoZoom
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.Engines
import io.github.lcebot.ferryqr.scan.isLocatedMiniCode
import io.github.lcebot.ferryqr.scan.locatedCrop
import io.github.lcebot.ferryqr.scan.LiveAnalyzer
import io.github.lcebot.ferryqr.scan.Sighting
import io.github.lcebot.ferryqr.scan.StillResult
import io.github.lcebot.ferryqr.scan.readStillImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Phase {
    data object Scanning : Phase
    data object Decoding : Phase
    /** [alreadyShown] holds codes whose markers were on screen before the frame froze. */
    data class Picking(
        val frame: Bitmap,
        val detections: List<Detection>,
        val alreadyShown: Set<String> = emptySet(),
        val origin: Origin = Origin.CAMERA,
    ) : Phase

    /** A live scan just succeeded; the screen confirms it on the code before handing it off. */
    data class Confirming(
        val detection: Detection,
        val frameWidth: Int,
        val frameHeight: Int,
        val wasShown: Boolean = false,
    ) : Phase
    data class Routing(val detection: Detection, val origin: Origin) : Phase

    /**
     * Probably a mini program code, located but not read, shown as [crop]. [live] is set when the
     * camera saw it, so WeChat's own scanner can still see it too.
     */
    data class MiniCodeFound(val crop: Bitmap, val live: Boolean) : Phase
    data class Showing(val card: ResultCard) : Phase
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val zxing = Engines.zxing
    private val wechat = Engines.wechat(app)
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
        viewModelScope.launch { OpenInModule.refresh() }
    }

    private fun onLiveHit(frame: Bitmap, detections: List<Detection>, alreadyShown: Set<String>) {
        _phase.value = if (detections.size == 1) {
            val single = detections.single()
            Phase.Confirming(single, frame.width, frame.height, single.text in alreadyShown).also { frame.recycle() }
        } else {
            Phase.Picking(frame, detections, alreadyShown)
        }
    }

    fun confirmed(detection: Detection) {
        if (_phase.value is Phase.Confirming) _phase.value = Phase.Routing(detection, Origin.CAMERA)
    }

    fun pick(detection: Detection) {
        val picking = _phase.value as? Phase.Picking
        val origin = picking?.origin ?: Origin.CAMERA
        if (detection.isLocatedMiniCode) {
            // A mini program code that could not be read is offered to WeChat as its picture.
            val crop = picking?.frame?.let { detection.locatedCrop(it) }
            if (crop != null) _phase.value = Phase.MiniCodeFound(crop, live = origin == Origin.CAMERA) else resume()
            return
        }
        _phase.value = Phase.Routing(detection, origin)
    }

    /**
     * Takes a code that was read where it goes: it is kept in history, handed straight to the app
     * it belongs to when one takes it, and otherwise shown as a card, built off the main thread since
     * it asks the package manager which apps there are. True when another app took the code, so the
     * scanner can close. [activity] starts the other app and is not kept.
     */
    suspend fun route(activity: Activity, routing: Phase.Routing): Boolean {
        val detection = routing.detection
        history.record(detection)
        val payload = Classifier.classify(detection.text, detection.format)
        if (Dispatcher.autoLaunch(activity, payload, routing.origin)) return true
        val card = withContext(Dispatchers.Default) { Cards.build(activity, payload, detection.format, routing.origin) }
        if (_phase.value == routing) _phase.value = Phase.Showing(card)
        return false
    }

    /** Shows the card for a mini program code that was located but not read. */
    suspend fun showMiniCode(activity: Activity, found: Phase.MiniCodeFound) {
        val card = withContext(Dispatchers.Default) { Cards.miniProgram(activity, found.crop, live = found.live) }
        if (_phase.value == found) _phase.value = Phase.Showing(card)
    }

    fun resume() {
        val dismissed = when (val current = _phase.value) {
            is Phase.Showing -> setOf(current.card.raw)
            is Phase.Picking -> current.detections.mapTo(HashSet()) { it.text }
            else -> emptySet()
        }
        val miniCode = showingLiveMiniCode
        showingLiveMiniCode = false
        autoZoom.reset()
        _phase.value = Phase.Scanning
        analyzer.resume(dismissed, miniCode)
    }

    /**
     * For a scanner that keeps failing: back to the base zoom even if the user zoomed, forget the
     * codes that were just dismissed, and bind the camera again.
     */
    fun manualReset() {
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
                        _phase.value = Phase.Routing(read.detections.single(), Origin.IMAGE)
                    }
                    else -> {
                        _phase.value = Phase.Picking(read.frame, read.detections, origin = Origin.IMAGE)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        analysisExecutor.shutdown()
    }
}
