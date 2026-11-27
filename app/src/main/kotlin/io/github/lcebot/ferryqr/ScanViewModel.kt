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
import io.github.lcebot.ferryqr.scan.FrozenFrame
import io.github.lcebot.ferryqr.scan.LiveAnalyzer
import io.github.lcebot.ferryqr.scan.Sighting
import io.github.lcebot.ferryqr.scan.StillOutcome
import io.github.lcebot.ferryqr.scan.isLocatedMiniCode
import io.github.lcebot.ferryqr.scan.locatedCrop
import io.github.lcebot.ferryqr.scan.readStillImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the scanner is with the codes it found. The confirmation that plays on a live scan's code is
 * not part of it but [ScanViewModel.confirmation], since it only shows on screen.
 */
sealed interface Phase {
    data object Scanning : Phase
    data object Decoding : Phase

    /** [alreadyShown] holds codes whose markers were on screen before the frame froze. */
    data class Picking(
        val frame: FrozenFrame,
        val detections: List<Detection>,
        val alreadyShown: Set<String> = emptySet(),
        val origin: Origin = Origin.CAMERA,
    ) : Phase

    /** A code is on its way: to the app it belongs to, or to its card. */
    data object Routing : Phase

    /**
     * The card for [source] shows, and the screen builds it again whenever an app's readiness
     * changes. [card], when set, is the one built first, for the readiness [builtFor]; a mini
     * program code located but not read comes without one, its card built as it shows.
     */
    data class Showing(
        val source: CardSource,
        val card: ResultCard? = null,
        val builtFor: Set<String>? = null,
    ) : Phase
}

/**
 * A live scan just succeeded, and the screen confirms it on the code before handing the code on,
 * after which [handedOn] is set. It is then held on the code a moment as the code is routed and its
 * card or its app opens, until the screen releases it.
 */
data class Confirmation(
    val detection: Detection,
    val frameWidth: Int,
    val frameHeight: Int,
    val wasShown: Boolean = false,
    val handedOn: Boolean = false,
)

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

    /**
     * The confirmation on screen. Until it hands its code on, the phase stays [Phase.Scanning] and
     * the analyzer holds back.
     */
    private val _confirmation = MutableStateFlow<Confirmation?>(null)
    val confirmation = _confirmation.asStateFlow()

    /** The gallery image being read, given up when the user moves on before it is read. */
    private var decoding: Job? = null

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
            val taken = _confirmation.value == null &&
                _phase.compareAndSet(Phase.Scanning, miniCodeFound(crop, live = true))
            if (!taken) crop.recycle()
        },
    )

    init {
        viewModelScope.launch { OpenInModule.refresh() }
        // Frames are read only while scanning and no confirmation waits to hand its code on. Both
        // reach this collector a moment after they change, so from a hit until then the analyzer
        // holds back by itself.
        combine(_phase, _confirmation) { phase, confirmation ->
            phase != Phase.Scanning || confirmation?.handedOn == false
        }.onEach { analyzer.paused = it }.launchIn(viewModelScope)
    }

    /**
     * Takes a live hit only while the scanner is still scanning: a frame read just as an image from
     * the gallery started decoding, or as anything else took over, is dropped with its frame.
     */
    private fun onLiveHit(frame: Bitmap, detections: List<Detection>, alreadyShown: Set<String>) {
        if (detections.size == 1) {
            val single = detections.single()
            val next = Confirmation(single, frame.width, frame.height, single.text in alreadyShown)
            // The confirmation keeps only the frame's size.
            frame.recycle()
            if (_phase.value == Phase.Scanning && _confirmation.compareAndSet(null, next)) {
                // The main thread may have moved the scanner on between the check and the write.
                // It turns the phase before it clears the confirmation, so a move made meanwhile
                // shows here, and the confirmation is taken back before it plays over what came next.
                if (_phase.value != Phase.Scanning) _confirmation.compareAndSet(next, null)
            }
            return
        }
        val taken = _confirmation.value == null &&
            _phase.compareAndSet(Phase.Scanning, Phase.Picking(FrozenFrame(frame), detections, alreadyShown))
        if (!taken) frame.recycle()
    }

    /**
     * The screen has confirmed [detection] and hands it on. A confirmation still leaving the screen
     * as the next code is confirmed is not the one in view, and only ends; one whose scanner went on
     * to a gallery image meanwhile leaves without handing its code on.
     */
    fun confirmed(detection: Detection) {
        val confirming = _confirmation.value ?: return
        if (confirming.detection != detection || confirming.handedOn) return
        if (_phase.value != Phase.Scanning) {
            _confirmation.compareAndSet(confirming, null)
            return
        }
        _confirmation.value = confirming.copy(handedOn = true)
        route(detection, Origin.CAMERA)
    }

    /** A tap on a picker already leaving, after a pick or a close, goes nowhere. */
    fun pick(detection: Detection) {
        val picking = _phase.value as? Phase.Picking ?: return
        if (detection.isLocatedMiniCode) {
            // A mini program code that could not be read is offered to WeChat as its picture.
            val crop = detection.locatedCrop(picking.frame.bitmap)
            if (crop != null) _phase.value = miniCodeFound(crop, live = picking.origin == Origin.CAMERA) else resume()
            return
        }
        route(detection, picking.origin)
    }

    /** Back to scanning from the picker, unless the picker is already leaving after a pick. */
    fun closePicker() {
        if (_phase.value is Phase.Picking) resume()
    }

    /**
     * The picker showing [picking] has left the screen, its fade included. Its frozen frame goes
     * with it, unless the scanner still picks from it, as when the activity is made again.
     */
    fun pickerLeft(picking: Phase.Picking) {
        if (_phase.value != picking) picking.frame.release()
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
     * both ask the package manager. Both are then handed to the screen to try.
     */
    private fun route(detection: Detection, origin: Origin) {
        val id = ++lastRoute
        _phase.value = Phase.Routing
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
     * The confirmation of [detection] has been held long enough and leaves the screen, whatever its
     * code has gone on to. It is let go from here on, so nothing shows it again, as when the activity
     * is made again while the card shows.
     */
    fun releaseConfirmation(detection: Detection) {
        val shown = _confirmation.value ?: return
        if (shown.detection == detection) _confirmation.compareAndSet(shown, null)
    }

    /**
     * Whether [effect] is still the code on its way: a reset, an image picked or another code
     * routed since leaves it behind, and it must neither open an app nor show its card.
     */
    fun isCurrent(effect: RouteEffect): Boolean = effect.id == lastRoute && _phase.value is Phase.Routing

    /** No app took the routed code, so its card shows. */
    fun show(effect: RouteEffect) {
        if (effect.id != lastRoute) return
        _phase.compareAndSet(Phase.Routing, Phase.Showing(effect.source, effect.card, effect.plan.ready))
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
        decoding?.cancel()
        _confirmation.value = null
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
        decoding?.cancel()
        _confirmation.value = null
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
        if (_phase.value == Phase.Decoding) return
        // A confirmation not yet handed on would route its code from under the image, and one
        // already handed on was held for the scanner the image replaces. The phase turns first, so a
        // live hit confirmed in between finds the scanner decoding and takes its own back.
        _phase.value = Phase.Decoding
        _confirmation.value = null
        decoding = viewModelScope.launch {
            val outcome = readStillImage(getApplication<Application>(), uri, zxing, wechat)
            // The scanner went on to something else while the image was read, which leaves it unused.
            if (_phase.value != Phase.Decoding) {
                outcome.release()
                return@launch
            }
            when (outcome) {
                StillOutcome.Unreadable -> {
                    message(R.string.image_unreadable)
                    resume()
                }
                StillOutcome.Empty -> {
                    message(R.string.no_code_found)
                    resume()
                }
                is StillOutcome.LocatedMini -> _phase.value = miniCodeFound(outcome.crop, live = false)
                // The image is decoded at up to 3000 pixels a side and is shown only by the picker,
                // so for a single code it goes at once rather than whenever garbage collection comes
                // round to it.
                is StillOutcome.Codes -> if (outcome.detections.size == 1) {
                    outcome.release()
                    route(outcome.detections.single(), Origin.IMAGE)
                } else {
                    _phase.value = Phase.Picking(FrozenFrame(outcome.frame), outcome.detections, origin = Origin.IMAGE)
                }
            }
        }
    }

    override fun onCleared() {
        // The picker leaving with the activity never reports that it has left.
        (_phase.value as? Phase.Picking)?.frame?.release()
        // Queued behind the frame being analysed, so the buffer goes only once nothing reads it.
        analysisExecutor.execute { analyzer.release() }
        analysisExecutor.shutdown()
    }
}
