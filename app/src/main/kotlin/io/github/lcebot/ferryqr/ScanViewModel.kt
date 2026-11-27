package io.github.lcebot.ferryqr

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lcebot.ferryqr.content.Payload
import io.github.lcebot.ferryqr.dispatch.CardFactory
import io.github.lcebot.ferryqr.dispatch.CardSource
import io.github.lcebot.ferryqr.dispatch.Dispatcher
import io.github.lcebot.ferryqr.dispatch.LaunchPlan
import io.github.lcebot.ferryqr.dispatch.Origin
import io.github.lcebot.ferryqr.dispatch.ReadContent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Phase {
    /**
     * The confirmation of a live scan on screen: playing while [Confirming], then kept on the code
     * while it is routed and its card shows, until the scanner moves on.
     */
    val confirmation: Confirming? get() = null

    data object Scanning : Phase
    data object Decoding : Phase
    /** [alreadyShown] holds codes whose markers were on screen before the frame froze. */
    data class Picking(
        val frame: Bitmap,
        val detections: List<Detection>,
        val alreadyShown: Set<String> = emptySet(),
        val origin: Origin = Origin.CAMERA,
    ) : Phase

    /**
     * A live scan just succeeded; the screen confirms it on the code before handing it off, after
     * which [handedOn] is set.
     */
    data class Confirming(
        val detection: Detection,
        val frameWidth: Int,
        val frameHeight: Int,
        val wasShown: Boolean = false,
        val handedOn: Boolean = false,
    ) : Phase {
        override val confirmation: Confirming get() = this
    }

    /** A code is on its way: to the app it belongs to, or to its card. */
    data class Routing(override val confirmation: Confirming? = null) : Phase

    /**
     * The card for [source] shows, and the screen builds it again whenever an app's readiness
     * changes. [card], when set, is the one built first, for the readiness [builtFor]; a mini
     * program code located but not read comes without one, its card built as it shows.
     */
    data class Showing(
        val source: CardSource,
        val card: ResultCard? = null,
        val builtFor: Set<String>? = null,
        override val confirmation: Confirming? = null,
    ) : Phase
}

/**
 * A code that may go straight to its app, which only an activity can start: the screen tries
 * [plan] on its way, and shows [card], built for [source], when no app takes the code. [id] tells
 * it from the codes routed before it, which the user may have moved on from.
 */
class RouteEffect(
    val id: Long,
    val payload: Payload,
    val origin: Origin,
    val plan: LaunchPlan,
    val card: ResultCard,
    val source: CardSource,
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val zxing = Engines.zxing
    private val wechat = Engines.wechat(app)
    private val history = HistoryStore.get(app)

    private val _phase = MutableStateFlow<Phase>(Phase.Scanning)
    val phase = _phase.asStateFlow()

    private val _sighting = MutableStateFlow<Sighting?>(null)
    val sighting = _sighting.asStateFlow()

    private val _messages = Channel<Int>(Channel.BUFFERED)

    /**
     * Messages for the screen to show, each once. Kept until the screen takes them, so a message
     * sent while the activity is made again, as when an image finishes decoding, still shows.
     */
    val messages = _messages.receiveAsFlow()

    /** Bumped by [manualReset] so the camera is bound again from scratch. */
    private val _cameraSession = MutableStateFlow(0)
    val cameraSession = _cameraSession.asStateFlow()

    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val autoZoom = AutoZoom()
    val analyzer = LiveAnalyzer(
        zxing, wechat, autoZoom,
        onSighting = { _sighting.value = it },
        onHit = ::onLiveHit,
        onMiniCode = { crop ->
            if (!_phase.compareAndSet(Phase.Scanning, miniCodeFound(crop, live = true))) crop.recycle()
        },
    )

    init {
        viewModelScope.launch { OpenInModule.refresh() }
        // Frames are read only while scanning. The phase reaches this collector a moment after it
        // changes, so from a hit until then the analyzer holds back by itself.
        _phase.onEach { analyzer.paused = it != Phase.Scanning }.launchIn(viewModelScope)
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

    /**
     * The screen has confirmed [detection] and hands it on. A confirmation still leaving the screen
     * as the next code is confirmed is not the one in the phase, and only ends.
     */
    fun confirmed(detection: Detection) {
        val confirming = _phase.value as? Phase.Confirming ?: return
        if (confirming.detection != detection) return
        route(detection, Origin.CAMERA, confirming.copy(handedOn = true))
    }

    fun pick(detection: Detection) {
        val picking = _phase.value as? Phase.Picking
        val origin = picking?.origin ?: Origin.CAMERA
        if (detection.isLocatedMiniCode) {
            // A mini program code that could not be read is offered to WeChat as its picture.
            val crop = picking?.frame?.let { detection.locatedCrop(it) }
            if (crop != null) _phase.value = miniCodeFound(crop, live = origin == Origin.CAMERA) else resume()
            return
        }
        route(detection, origin)
    }

    private val _routes = Channel<RouteEffect>(Channel.BUFFERED)

    /** The latest code routed, read and written on the main thread only. */
    private var lastRoute = 0L

    /**
     * Codes on their way, each to be tried once by the screen on the main thread. Kept until the
     * screen takes them, so a code routed while the activity is made again is still tried once.
     */
    val routes = _routes.receiveAsFlow()

    /**
     * Takes a code that was read where it goes: its content is read once and kept in history at
     * once, and the same reading gives, off the main thread, its way to its app and its card, which
     * both ask the package manager. Both are then handed to the screen to try. [confirmation] is the
     * live scan's, which stays on the code meanwhile.
     */
    private fun route(detection: Detection, origin: Origin, confirmation: Phase.Confirming? = null) {
        val id = ++lastRoute
        _phase.value = Phase.Routing(confirmation)
        val app = getApplication<Application>()
        // Kept in history at once, so a scan leaving the scanner straight away is kept all the same.
        val content = ReadContent.of(detection.text, detection.format)
        history.record(content)
        viewModelScope.launch {
            // Read once, so the way the code goes and the card shown if it goes nowhere agree.
            val ready = OpenInModule.readyApps.value
            val effect = withContext(Dispatchers.Default) {
                val source = CardSource.Read(content, origin)
                val plan = Dispatcher.plan(app, content.payload, ready)
                RouteEffect(id, content.payload, origin, plan, CardFactory.build(app, source, ready), source)
            }
            _routes.send(effect)
        }
    }

    /**
     * Whether [effect] is still the code on its way: a reset, an image picked or another code
     * routed since leaves it behind, and it must neither open an app nor show its card.
     */
    fun isCurrent(effect: RouteEffect): Boolean = effect.id == lastRoute && _phase.value is Phase.Routing

    /** No app took the routed code, so its card shows. */
    fun show(effect: RouteEffect) {
        val routing = _phase.value as? Phase.Routing ?: return
        if (effect.id != lastRoute) return
        val showing = Phase.Showing(effect.source, effect.card, effect.plan.ready, routing.confirmation)
        _phase.compareAndSet(routing, showing)
    }

    /**
     * A mini program code located but not read, shown as [crop]. [live] is set when the camera saw
     * it, so WeChat's own scanner can still see it too.
     */
    private fun miniCodeFound(crop: Bitmap, live: Boolean) = Phase.Showing(CardSource.LocatedMini(crop, live))

    /**
     * Back to scanning, muting what the phase left showed: the codes of a card or of the picker,
     * and a mini program code the camera saw, so it is not offered again while it stays in view.
     */
    fun resume() {
        val left = _phase.value
        val shown = (left as? Phase.Showing)?.source
        val dismissed = when {
            shown is CardSource.Read -> setOf(shown.content.text)
            left is Phase.Picking -> left.detections.mapTo(HashSet()) { it.text }
            else -> emptySet()
        }
        val miniCode = shown is CardSource.LocatedMini && shown.live
        autoZoom.reset()
        // The phase turns before the analyzer resumes: a result reported in between would find the
        // scanner not scanning and be dropped, and the analyzer would then hold back for good.
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
        _sighting.value = null
        // The phase turns before the analyzer starts over, as in [resume].
        _phase.value = Phase.Scanning
        analyzer.reset()
        _cameraSession.value++
        message(R.string.reset_done)
    }

    fun message(@StringRes text: Int) {
        _messages.trySend(text)
    }

    fun decodeImage(uri: Uri) {
        if (_phase.value is Phase.Decoding) return
        _phase.value = Phase.Decoding
        viewModelScope.launch {
            when (val read = readStillImage(getApplication<Application>(), uri, zxing, wechat)) {
                StillResult.Unreadable -> {
                    message(R.string.image_unreadable)
                    resume()
                }
                // The image is decoded at up to 3000 pixels a side and is shown only by the picker,
                // so otherwise it goes as soon as the code is taken from it, rather than whenever
                // garbage collection comes round to it.
                is StillResult.Read -> when (read.detections.size) {
                    0 -> {
                        val crop = read.miniCodes.firstOrNull()?.crop(read.frame)
                        read.frame.recycle()
                        if (crop != null) {
                            _phase.value = miniCodeFound(crop, live = false)
                        } else {
                            message(R.string.no_code_found)
                            resume()
                        }
                    }
                    1 -> {
                        read.frame.recycle()
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
        // Queued behind the frame being analysed, so the buffer goes only once nothing reads it.
        analysisExecutor.execute { analyzer.release() }
        analysisExecutor.shutdown()
    }
}
