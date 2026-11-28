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
import io.github.lcebot.ferryqr.dispatch.FerryEnv
import io.github.lcebot.ferryqr.dispatch.LaunchPlan
import io.github.lcebot.ferryqr.dispatch.Origin
import io.github.lcebot.ferryqr.dispatch.ReadContent
import io.github.lcebot.ferryqr.history.HistoryStore
import io.github.lcebot.ferryqr.openin.OpenInModule
import io.github.lcebot.ferryqr.scan.AutoZoom
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.Engines
import io.github.lcebot.ferryqr.scan.Found
import io.github.lcebot.ferryqr.scan.FrozenFrame
import io.github.lcebot.ferryqr.scan.LiveAnalyzer
import io.github.lcebot.ferryqr.scan.Sighting
import io.github.lcebot.ferryqr.scan.StillOutcome
import io.github.lcebot.ferryqr.scan.readStillImage
import io.github.lcebot.ferryqr.scan.soleRead
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val codes: List<Found>,
        val alreadyShown: Set<String> = emptySet(),
        val origin: Origin = Origin.CAMERA,
    ) : Phase

    /** A code is on its way: to the app it belongs to, or to its card. */
    data object Routing : Phase

    /**
     * The card for [source] shows, as [ScanViewModel.card] holds it. A code that was routed has its
     * card there as the phase turns; a mini program code located but not read has its card built as
     * it shows.
     */
    data class Showing(val source: CardSource) : Phase
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
 * [plan] on its way, and shows the card for [source] when no app takes the code. [id] tells it from
 * the codes routed before it, which the user may have moved on from.
 */
class RouteEffect(
    val id: Long,
    val payload: Payload,
    val plan: LaunchPlan,
    val source: CardSource,
)

/**
 * Something that happened once, for the screen to show or to let the user feel as it takes it up,
 * so a screen made again never plays it a second time.
 */
sealed interface ScanEvent {
    class Message(@StringRes val text: Int) : ScanEvent

    /** A mini program code was located but not read, and its card shows. */
    data object MiniCodeFound : ScanEvent
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    /** What the analyzer reports from its own thread, for the main thread to take up. */
    private sealed interface Report {
        /** Lets go of what the report holds, once the scanner has moved on from it. */
        fun release() {}

        class Sighted(val sighting: Sighting?) : Report

        class Hit(val frame: Bitmap, val codes: List<Found>, val alreadyShown: Set<String>) : Report {
            override fun release() = frame.recycle()
        }

        class MiniCode(val crop: Bitmap) : Report {
            override fun release() = crop.recycle()
        }
    }

    private val zxing = Engines.zxing
    private val wechat = Engines.wechat(app)
    private val history = HistoryStore.get(app)

    private val _phase = MutableStateFlow<Phase>(Phase.Scanning)
    val phase = _phase.asStateFlow()

    private val cards = CardHolder(app, viewModelScope)

    /** The card of the code [Phase.Showing] shows. */
    val card = cards.card

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

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)

    /**
     * Events for the screen, each taken up once. Kept until the screen takes them, so an event sent
     * while the activity is made again, as when an image finishes decoding, still shows.
     */
    val events = _events.receiveAsFlow()

    /** Bumped by [manualReset] so the camera is bound again from scratch. */
    private val _cameraSession = MutableStateFlow(0)
    val cameraSession = _cameraSession.asStateFlow()

    /**
     * Set by [manualReset] until the camera it binds again shows its picture or fails, while the
     * frames of the camera on its way out would still show the codes the reset forgets.
     */
    private var awaitingCamera = false

    /**
     * The analyzer's reports, taken up in order on the main thread, which alone changes the phase
     * and the confirmation. Unlimited, so no report that holds a frame is turned away unreleased.
     */
    private val reports = Channel<Report>(Channel.UNLIMITED)

    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val autoZoom = AutoZoom()
    val analyzer = LiveAnalyzer(
        zxing, wechat, autoZoom,
        onSighting = { report(Report.Sighted(it)) },
        onHit = { frame, codes, alreadyShown -> report(Report.Hit(frame, codes, alreadyShown)) },
        onMiniCode = { report(Report.MiniCode(it)) },
    )

    init {
        warmUp()
        viewModelScope.launch { OpenInModule.refresh() }
        viewModelScope.launch {
            for (report in reports) take(report)
        }
    }

    /**
     * Loads the WeChat engine off the analysis thread, which reads frames with zxing-cpp alone until
     * it is ready, so a code in view as the scanner opens is not held up by the loading.
     */
    private fun warmUp() {
        viewModelScope.launch(Dispatchers.Default) { wechat.warmUp() }
    }

    /** Sent from the analysis thread; a report turned away once the scanner is gone lets go of its frame. */
    private fun report(report: Report) {
        if (reports.trySend(report).isFailure) report.release()
    }

    /** Whether the scanner takes what the camera reads: scanning, with nothing confirmed and no reset under way. */
    private val reading: Boolean
        get() = _phase.value == Phase.Scanning && _confirmation.value == null && !awaitingCamera

    /**
     * Every change of the phase or the confirmation, made on the main thread. The analyzer is paused
     * or let read in the same step, so it reads no frame the scanner would not take, and a scanner
     * that stops reading lets go of the sighting, so its brackets and hint never stay over a decode,
     * a result or a reset.
     */
    private fun move(phase: Phase = _phase.value, confirmation: Confirmation? = _confirmation.value) {
        _phase.value = phase
        _confirmation.value = confirmation
        analyzer.paused = !reading
        if (!reading) _sighting.value = null
    }

    /**
     * Takes up a report while the scanner reads. One read from a frame the analyzer had already
     * started on as the scanner moved on, to a reset, a gallery image or the picker, is let go.
     */
    private fun take(report: Report) {
        if (!reading) {
            report.release()
            return
        }
        when (report) {
            is Report.Sighted -> _sighting.value = report.sighting
            is Report.Hit -> onLiveHit(report.frame, report.codes, report.alreadyShown)
            is Report.MiniCode -> showMiniCode(report.crop, Origin.CAMERA)
        }
    }

    /** A single code read is confirmed on screen before it goes on; several are offered to pick from. */
    private fun onLiveHit(frame: Bitmap, codes: List<Found>, alreadyShown: Set<String>) {
        val single = codes.soleRead()
        if (single == null) {
            move(Phase.Picking(FrozenFrame(frame), codes, alreadyShown))
            return
        }
        // The confirmation keeps only the frame's size.
        val confirmation = Confirmation(single, frame.width, frame.height, single.text in alreadyShown)
        frame.recycle()
        move(confirmation = confirmation)
    }

    /**
     * The screen has confirmed [detection] and hands it on. A confirmation the scanner has let go of
     * meanwhile, as for a gallery image, or one still leaving the screen as the next code is
     * confirmed, hands nothing on.
     */
    fun confirmed(detection: Detection) {
        val confirming = _confirmation.value ?: return
        if (confirming.detection != detection || confirming.handedOn) return
        move(confirmation = confirming.copy(handedOn = true))
        route(detection, Origin.CAMERA)
    }

    /** A tap on a picker already leaving, after a pick or a close, goes nowhere. */
    fun pick(code: Found) {
        val picking = _phase.value as? Phase.Picking ?: return
        when (code) {
            is Found.Read -> route(code.detection, picking.origin)
            // A mini program code that could not be read is offered to WeChat as its picture.
            is Found.Located -> {
                val crop = code.crop(picking.frame.bitmap)
                if (crop != null) showMiniCode(crop, picking.origin) else resume()
            }
        }
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
     * both ask the package manager. The way is handed to the screen to try, and the card is kept for
     * when no app takes the code. The card is built at once all the same, so it shows the moment
     * the code goes nowhere.
     */
    private fun route(detection: Detection, origin: Origin) {
        val id = ++lastRoute
        move(Phase.Routing)
        val app = getApplication<Application>()
        // Kept in history at once, so a scan leaving the scanner straight away is kept all the same.
        val content = ReadContent.of(detection.text, detection.format)
        history.record(content)
        viewModelScope.launch {
            // One env for both, so the way the code goes and the card shown if it goes nowhere agree,
            // and the apps are ranked and looked up once.
            val env = FerryEnv(app)
            val source = CardSource.Read(content, origin)
            val (plan, built) = withContext(Dispatchers.Default) {
                Dispatcher.plan(env, content.payload, origin) to CardFactory.build(env, source)
            }
            // The holder takes the card at once, though the sheet shows it only once the phase
            // turns, so the sheet has it from its first frame.
            if (id == lastRoute && _phase.value == Phase.Routing) cards.show(source, built, env)
            _routes.send(RouteEffect(id, content.payload, plan, source))
        }
    }

    /**
     * The confirmation of [detection] has been held long enough and leaves the screen, whatever its
     * code has gone on to. It is let go from here on, so nothing shows it again, as when the activity
     * is made again while the card shows.
     */
    fun releaseConfirmation(detection: Detection) {
        if (_confirmation.value?.detection == detection) move(confirmation = null)
    }

    /**
     * Whether [effect] is still the code on its way: a reset, an image picked or another code
     * routed since leaves it behind, and it must neither open an app nor show its card.
     */
    fun isCurrent(effect: RouteEffect): Boolean = effect.id == lastRoute && _phase.value is Phase.Routing

    /** No app took the routed code, so its card shows, unless the scanner has moved on from it. */
    fun show(effect: RouteEffect) {
        if (isCurrent(effect)) move(Phase.Showing(effect.source))
    }

    /**
     * Shows a mini program code located but not read, as [crop], from [origin], and tells the screen,
     * which lets the user feel it.
     */
    private fun showMiniCode(crop: Bitmap, origin: Origin) {
        val source = CardSource.LocatedMini(crop, origin)
        cards.show(source)
        move(Phase.Showing(source))
        _events.trySend(ScanEvent.MiniCodeFound)
    }

    /** Lets go of the cards of the codes left behind, so none of them shows for the next code. */
    private fun forgetCards() {
        cards.show(null)
    }

    /**
     * Back to scanning, muting what the phase left showed: the codes of a card or of the picker,
     * and a mini program code the camera saw, so it is not offered again while it stays in view.
     */
    fun resume() {
        val left = _phase.value
        val shown = (left as? Phase.Showing)?.source
        val dismissed = when {
            shown is CardSource.Read -> setOf(shown.content.text)
            left is Phase.Picking -> left.codes.filterIsInstance<Found.Read>().mapTo(HashSet()) { it.detection.text }
            else -> emptySet()
        }
        val miniCode = shown is CardSource.LocatedMini && shown.origin == Origin.CAMERA
        autoZoom.reset()
        decoding?.cancel()
        forgetCards()
        analyzer.resume(dismissed, miniCode)
        move(Phase.Scanning, null)
    }

    /**
     * For a scanner that keeps failing: back to the base zoom even if the user zoomed, forget the
     * codes that were just dismissed, and bind the camera again. No frame is read until the new
     * camera's picture shows, as [cameraSettled] tells, since the camera on its way out would still
     * show the codes forgotten; the first frame read after that starts the analyzer over.
     */
    fun manualReset() {
        autoZoom.forceReset()
        wechat.retryIfFailed()
        warmUp()
        decoding?.cancel()
        forgetCards()
        awaitingCamera = true
        move(Phase.Scanning, null)
        _cameraSession.value++
        message(R.string.reset_done)
    }

    /**
     * The camera's picture shows, or the camera failed to show one. A reset waiting for it starts the
     * analyzer over from here, which also clears a result the camera on its way out still reported,
     * and frames are read again.
     */
    fun cameraSettled() {
        if (!awaitingCamera) return
        awaitingCamera = false
        analyzer.reset()
        move()
    }

    /**
     * The camera has stopped, as when a page opens over the scanner, and the viewfinder is covered
     * until it opens again. The sighting goes with the picture, so no brackets stay over the cover.
     */
    fun cameraStopped() {
        _sighting.value = null
    }

    fun message(@StringRes text: Int) {
        _events.trySend(ScanEvent.Message(text))
    }

    fun decodeImage(uri: Uri) {
        if (_phase.value == Phase.Decoding) return
        // A confirmation not yet handed on would route its code from under the image, and one
        // already handed on was held for the scanner the image replaces.
        move(Phase.Decoding, null)
        forgetCards()
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
                is StillOutcome.LocatedMini -> showMiniCode(outcome.crop, Origin.IMAGE)
                // The image is decoded at up to 3000 pixels a side and is shown only by the picker,
                // so for a single code it goes at once rather than whenever garbage collection comes
                // round to it.
                is StillOutcome.Codes -> {
                    val single = outcome.codes.soleRead()
                    if (single != null) {
                        outcome.release()
                        route(single, Origin.IMAGE)
                    } else {
                        move(Phase.Picking(FrozenFrame(outcome.frame), outcome.codes, origin = Origin.IMAGE))
                    }
                }
            }
        }
    }

    override fun onCleared() {
        // The picker leaving with the activity never reports that it has left.
        (_phase.value as? Phase.Picking)?.frame?.release()
        // Nothing takes up reports any longer: those waiting let go of their frames here, and those
        // the analyzer still sends are turned away and let go as they come.
        reports.close()
        generateSequence { reports.tryReceive().getOrNull() }.forEach { it.release() }
        // Queued behind the frame being analysed, so the buffer goes only once nothing reads it.
        analysisExecutor.execute { analyzer.release() }
        analysisExecutor.shutdown()
    }
}
