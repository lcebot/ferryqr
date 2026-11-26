package io.github.lcebot.ferryqr

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.dispatch.CardFactory
import io.github.lcebot.ferryqr.dispatch.CardSource
import io.github.lcebot.ferryqr.dispatch.Dispatcher
import io.github.lcebot.ferryqr.dispatch.LaunchPlan
import io.github.lcebot.ferryqr.dispatch.Origin
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.history.HistoryStore
import io.github.lcebot.ferryqr.openin.OpenInModule
import io.github.lcebot.ferryqr.scan.AutoZoom
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.Engines
import io.github.lcebot.ferryqr.scan.LiveAnalyzer
import io.github.lcebot.ferryqr.scan.Sighting
import io.github.lcebot.ferryqr.scan.StillResult
import io.github.lcebot.ferryqr.scan.isLocatedMiniCode
import io.github.lcebot.ferryqr.scan.locatedCrop
import io.github.lcebot.ferryqr.scan.readStillImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
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
    /** A code is on its way: to the app it belongs to, or to its card. */
    data object Routing : Phase

    /**
     * Probably a mini program code, located but not read, shown as [crop]. [live] is set when the
     * camera saw it, so WeChat's own scanner can still see it too.
     */
    data class MiniCodeFound(val crop: Bitmap, val live: Boolean) : Phase
    /** [card] is built for [source], and again for it whenever an app's readiness changes. */
    data class Showing(val card: ResultCard, val source: CardSource) : Phase
}

/**
 * A code that may go straight to its app, which only an activity can start: the screen tries
 * [plan] on its way, and shows [card] when no app takes the code.
 */
class RouteEffect(val payload: Payload, val origin: Origin, val plan: LaunchPlan, val card: ResultCard)

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
            val found = Phase.MiniCodeFound(crop, live = true)
            if (_phase.compareAndSet(Phase.Scanning, found)) {
                showingLiveMiniCode = true
                viewModelScope.launch { foundMiniCode(found) }
            } else {
                crop.recycle()
            }
        },
    )

    init {
        viewModelScope.launch { OpenInModule.refresh() }
        // What a card offers depends on which apps take content, which the framework may report
        // only after the card is up.
        viewModelScope.launch { OpenInModule.readyApps.drop(1).collect { rebuildShown() } }
    }

    /**
     * Takes a live hit only while the scanner is still scanning: a frame read just as an image from
     * the gallery started decoding, or as anything else took over, is dropped with its frame.
     */
    private fun onLiveHit(frame: Bitmap, detections: List<Detection>, alreadyShown: Set<String>) {
        val next = if (detections.size == 1) {
            val single = detections.single()
            Phase.Confirming(single, frame.width, frame.height, single.text in alreadyShown)
        } else {
            Phase.Picking(frame, detections, alreadyShown)
        }
        val taken = _phase.compareAndSet(Phase.Scanning, next)
        if (!taken || next is Phase.Confirming) frame.recycle()
    }

    fun confirmed(detection: Detection) {
        if (_phase.value is Phase.Confirming) route(detection, Origin.CAMERA)
    }

    fun pick(detection: Detection) {
        val picking = _phase.value as? Phase.Picking
        val origin = picking?.origin ?: Origin.CAMERA
        if (detection.isLocatedMiniCode) {
            // A mini program code that could not be read is offered to WeChat as its picture.
            val crop = picking?.frame?.let { detection.locatedCrop(it) }
            if (crop != null) foundMiniCode(Phase.MiniCodeFound(crop, live = origin == Origin.CAMERA)) else resume()
            return
        }
        route(detection, origin)
    }

    private val _routes = Channel<RouteEffect>(Channel.BUFFERED)

    /**
     * Codes on their way, each to be tried once by the screen on the main thread. Kept until the
     * screen takes them, so a code routed while the activity is made again is still tried once.
     */
    val routes = _routes.receiveAsFlow()

    /**
     * Takes a code that was read where it goes: it is kept in history at once, then its way to its
     * app and its card are worked out off the main thread, since both ask the package manager, and
     * handed to the screen to try.
     */
    private fun route(detection: Detection, origin: Origin) {
        _phase.value = Phase.Routing
        history.record(detection)
        val app = getApplication<Application>()
        viewModelScope.launch {
            val source = CardSource.Read(detection.text, detection.format, origin)
            val payload = Classifier.classify(detection.text, detection.format)
            val (plan, card) = withContext(Dispatchers.Default) {
                Dispatcher.plan(app, payload) to CardFactory.build(app, source)
            }
            shownSource = source
            _routes.send(RouteEffect(payload, origin, plan, card))
        }
    }

    /** No app took the routed code, so its card shows. */
    fun show(effect: RouteEffect) {
        val source = shownSource ?: return
        if (_phase.value == Phase.Routing) _phase.value = Phase.Showing(effect.card, source)
    }

    /** The source of the card being routed or shown, for building it again. */
    private var shownSource: CardSource? = null

    /** A mini program code located but not read: its card is built off the main thread, then shown. */
    private fun foundMiniCode(found: Phase.MiniCodeFound) {
        _phase.value = found
        val source = CardSource.LocatedMini(found.crop, found.live)
        viewModelScope.launch {
            val card = withContext(Dispatchers.Default) { CardFactory.build(getApplication(), source) }
            if (_phase.value == found) _phase.value = Phase.Showing(card, source)
        }
    }

    /** Builds the card shown again for the apps ready now, keeping what it is shown for. */
    private suspend fun rebuildShown() {
        val showing = _phase.value as? Phase.Showing ?: return
        val card = withContext(Dispatchers.Default) { CardFactory.build(getApplication(), showing.source) }
        if (_phase.value === showing) _phase.value = Phase.Showing(card, showing.source)
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
                            foundMiniCode(Phase.MiniCodeFound(crop, live = false))
                        } else {
                            message(R.string.no_code_found)
                            resume()
                        }
                    }
                    1 -> {
                        route(read.detections.single(), Origin.IMAGE)
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
