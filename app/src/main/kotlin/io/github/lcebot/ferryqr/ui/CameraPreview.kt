@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import androidx.camera.camera2.interop.camera2Interop
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.TorchState
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.lcebot.ferryqr.scan.AutoZoom
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 1080p keeps small and distant codes readable while zxing-cpp stays fast on the luma plane. */
private val TARGET_SIZE = Size(1920, 1080)

/**
 * The veil's opacity over the frozen picture while a reset binds the camera again. It is
 * Material's opacity for disabled content, since nothing under the veil can be scanned meanwhile.
 */
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * How long a reset waits for a frame to freeze on. The analysis thread hands it over once it is
 * done with the frame before, and a camera that sends none in this time has no picture worth keeping.
 */
private const val STILL_WAIT_MS = 500L

/**
 * Prefers a logical multi-camera, whose zoom ratio moves across the physical lenses, then the
 * camera closest to the main lens, then the widest zoom range.
 */
private fun ProcessCameraProvider.preferredBackCamera(): CameraSelector {
    val best = availableCameraInfos
        .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        .maxWithOrNull(
            compareBy<CameraInfo> { it.isLogicalMultiCameraSupported }
                .thenBy { -abs(ln(it.intrinsicZoomRatio)) }
                .thenBy { info -> info.zoomState.value?.let { it.maxZoomRatio / it.minZoomRatio } ?: 1f }
        )
    return best?.cameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
}

/**
 * The preview and analysis as one session, whose capture results also go to [autoZoom], which
 * reads the camera's own exposure state and scene change detection from them for a tapped focus.
 * [onCapture] hears of each completed capture, on the camera's thread.
 */
private fun sessionReporting(
    autoZoom: AutoZoom,
    preview: Preview,
    analysis: ImageAnalysis,
    onCapture: () -> Unit,
): SessionConfig =
    SessionConfig.Builder(preview, analysis)
        .camera2Interop {
            repeatingCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    autoZoom.captureCallback.onCaptureCompleted(session, request, result)
                    onCapture()
                }
            }
        }
        .build()

/**
 * The bound session's completed captures, which tell when its picture reaches the viewfinder.
 * [onCapture] is called on the camera's thread.
 */
private class Captures {
    @Volatile
    private var next = CompletableDeferred<Unit>()

    fun onCapture() {
        next.complete(Unit)
    }

    /** Waits for a capture since the session was bound, or since the last [forget]. */
    suspend fun await() = next.await()

    /** Counts only the captures that follow, as for a camera that closed and opens again. */
    fun forget() {
        next = CompletableDeferred()
    }
}

/**
 * Passes each frame on to [analyzer], first making a bitmap of it when one is asked for. The
 * viewfinder's SurfaceView shows the camera on a child surface, which PixelCopy cannot read through
 * the view, so the still a reset freezes on comes from the analysis stream, whose field of view the
 * preview shares as both use the same resolution selector.
 */
private class StillTaker(private val analyzer: ImageAnalysis.Analyzer) : ImageAnalysis.Analyzer {
    @Volatile
    private var wanted: CompletableDeferred<Bitmap?>? = null

    /**
     * The next frame in the camera's own orientation, or null when the camera sends none in time
     * or the frame cannot be converted.
     */
    suspend fun take(): Bitmap? {
        val still = CompletableDeferred<Bitmap?>()
        wanted = still
        try {
            return withTimeoutOrNull(STILL_WAIT_MS) { still.await() }
        } finally {
            if (wanted === still) wanted = null
            // A still made once the wait is over finds the request cancelled and is recycled at once.
            still.cancel()
        }
    }

    override fun analyze(image: ImageProxy) {
        wanted?.let { still ->
            wanted = null
            val bitmap = try {
                image.toBitmap()
            } catch (e: RuntimeException) {
                null
            }
            if (!still.complete(bitmap)) bitmap?.recycle()
        }
        analyzer.analyze(image)
    }

    override fun getDefaultTargetResolution(): Size? = analyzer.defaultTargetResolution

    override fun getTargetCoordinateSystem(): Int = analyzer.targetCoordinateSystem

    override fun updateTransform(matrix: android.graphics.Matrix?) = analyzer.updateTransform(matrix)
}

/**
 * A still of the camera's picture in the camera's own orientation. The image is stretched over the
 * surface's [resolution], which [toViewfinder] places in the viewfinder as the picture was placed
 * when the still was taken, so the still lies exactly over the picture it replaces.
 */
private class Still(val bitmap: Bitmap, val resolution: IntSize, val toViewfinder: Matrix) {
    val image = bitmap.asImageBitmap()
}

/**
 * The bound camera's torch. [on] follows the camera's own torch state rather than the last request,
 * since the camera turns the torch off by itself when it closes, as it does whenever the scanner
 * leaves the screen.
 */
@Stable
class Torch {
    /** Whether the bound camera has a flash unit to light. */
    var available by mutableStateOf(false)
        internal set

    var on by mutableStateOf(false)
        internal set

    internal var camera: Camera? = null

    fun set(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }
}

/**
 * The camera's picture over the whole of the bounds [modifier] gives it. [contentPadding] is the
 * part the page's bars cover, so the indicator a reset shows sits in the middle of what stays in view.
 */
@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    executor: Executor,
    autoZoom: AutoZoom,
    session: Int,
    torch: Torch,
    onZoom: (Float) -> Unit,
    onUnavailable: () -> Unit,
    scanning: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val transformer = remember { MutableCoordinateTransformer() }
    val haptics = rememberHaptics()
    // Until the camera delivers its first frame, and again once it opens after the scanner stopped,
    // the viewfinder is black, and then the picture would appear at once. A black cover fades off it
    // instead of the viewfinder fading itself, which works however the viewfinder draws its surface.
    // It starts opaque, as the viewfinder starts black.
    val cover = remember { Animatable(1f) }
    // While a reset binds the camera again, the viewfinder holds a still of the picture it showed,
    // under a white veil that tells the picture is not live.
    var still by remember { mutableStateOf<Still?>(null) }
    val frozen = remember { Animatable(0f) }
    val veil = remember { Animatable(0f) }
    var restarting by remember { mutableStateOf(false) }
    // Whether the camera bound last cannot run, which the page has been told.
    var failed by remember { mutableStateOf(false) }
    // The cover, the still and the veil span the whole viewfinder, which the scheme's slow springs
    // move; the indicator is a small part, which its fast ones move.
    val slowEffects = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val fastSpatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val currentSession by rememberUpdatedState(session)

    LaunchedEffect(owner) {
        // Besides a device without a back camera, CameraX fails here when the camera service or
        // another app holds the camera in a way it cannot recover from; either way there is no preview.
        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onUnavailable()
            return@LaunchedEffect
        }
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(TARGET_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        val stills = StillTaker(analyzer)
        // The session bound last stays bound across a new session until the still or the cover
        // hides it, and is let go then, or as the effect ends.
        var bound: SessionConfig? = null
        var analyzing: ImageAnalysis? = null
        fun release() {
            val config = bound ?: return
            autoZoom.camera = null
            analyzing?.clearAnalyzer()
            provider.unbind(config)
            bound = null
            analyzing = null
            camera = null
        }
        // The still is a full camera frame, so it goes as soon as it has left the screen.
        fun dropStill() {
            val shown = still ?: return
            still = null
            shown.bitmap.recycle()
        }
        // Whether the bound camera's picture is in view, so a reset has a picture to freeze.
        var live = false
        // Whether the still in place waits for a camera being bound again, rather than leaving.
        var holding = false

        suspend fun freeze(): Unit = coroutineScope {
            // The reset answers at once: the veil and the indicator come while the still is taken, as
            // the next frame may wait behind a closer look, and the still shows the picture they are
            // already over.
            restarting = true
            launch { veil.animateTo(1f, slowEffects) }
            // The still of a reset whose camera has not shown its picture yet stays, so the frozen
            // picture never jumps to a later one.
            if (!holding) {
                val shown = request
                val taken = if (live && !failed && shown != null) {
                    stills.take()?.let { bitmap ->
                        val toViewfinder = Matrix().apply {
                            setFrom(transformer.transformMatrix)
                            invert()
                        }
                        Still(bitmap, IntSize(shown.resolution.width, shown.resolution.height), toViewfinder)
                    }
                } else {
                    null
                }
                if (taken != null) {
                    dropStill()
                    still = taken
                    frozen.snapTo(1f)
                    holding = true
                }
            }
            live = false
            // The camera on its way out does not count, so its controls and observers go at once,
            // and a failure of its own neither keeps the indicator away nor tells the page again.
            camera = null
            failed = false
            restarting = true
            // The camera is let go once the veil is in, by when the still has long been drawn over
            // the picture it replaces.
            coroutineScope {
                launch { veil.animateTo(1f, slowEffects) }
                // Without a picture to freeze the veil lies over black, and a still an earlier reset
                // left on its way out fades into the black.
                if (!holding) {
                    launch { cover.animateTo(1f, slowEffects) }
                    launch { frozen.animateTo(0f, slowEffects) }
                }
            }
            if (!holding) dropStill()
        }

        // On the picture's first frame the cover, the still and the veil leave together, and the
        // indicator with them.
        suspend fun reveal() {
            live = true
            holding = false
            restarting = false
            coroutineScope {
                launch { cover.animateTo(0f, slowEffects) }
                launch { veil.animateTo(0f, slowEffects) }
                frozen.animateTo(0f, slowEffects)
            }
            dropStill()
        }

        try {
            var first = true
            snapshotFlow { currentSession }.collectLatest {
                if (!first) freeze()
                first = false
                release()
                failed = false
                val preview = Preview.Builder().setResolutionSelector(selector).build()
                preview.setSurfaceProvider { request = it }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor, stills)
                val captures = Captures()
                val config = sessionReporting(autoZoom, preview, analysis, captures::onCapture)
                provider.unbindAll()
                val started = try {
                    provider.bindToLifecycle(owner, provider.preferredBackCamera(), config)
                } catch (e: Exception) {
                    analysis.clearAnalyzer()
                    failed = true
                    onUnavailable()
                    return@collectLatest
                }
                bound = config
                analyzing = analysis
                camera = started
                autoZoom.camera = started
                // CameraX closes the camera as the scanner stops, as when a page opens over it, and
                // the camera opens black as the scanner starts again. The cover goes up meanwhile
                // and leaves on the first frame after, as at the first launch.
                var stopped = false
                owner.lifecycle.currentStateFlow
                    .map { it.isAtLeast(Lifecycle.State.STARTED) }
                    .distinctUntilChanged()
                    .collectLatest { running ->
                        if (running) {
                            // Captures from before the camera closed say nothing of the picture it opens with.
                            if (stopped) captures.forget()
                            captures.await()
                            reveal()
                        } else {
                            stopped = true
                            live = false
                            cover.snapTo(1f)
                        }
                    }
            }
        } finally {
            release()
            dropStill()
        }
    }

    DisposableEffect(camera, owner) {
        val bound = camera ?: return@DisposableEffect onDispose { }
        val zoomObserver = Observer<ZoomState> { onZoom(it.zoomRatio) }
        val torchObserver = Observer<Int> { torch.on = it == TorchState.ON }
        // A camera that binds can still fail to open, as when another app holds it or a policy
        // disables it, and CameraX reports that here rather than from the binding. The page hears
        // of each failure once, and the camera counts as running again once it opens. A passing
        // error that CameraX retries at once is not worth a message.
        val stateObserver = Observer<CameraState> { state ->
            // A reset lets go of the camera before this observer is removed with it.
            if (camera !== bound) return@Observer
            val error = state.error
            if (error != null && error.code != CameraState.ERROR_OTHER_RECOVERABLE_ERROR) {
                if (!failed) {
                    failed = true
                    onUnavailable()
                }
            } else if (state.type == CameraState.Type.OPEN) {
                failed = false
            }
        }
        bound.cameraInfo.zoomState.observe(owner, zoomObserver)
        bound.cameraInfo.torchState.observe(owner, torchObserver)
        bound.cameraInfo.cameraState.observe(owner, stateObserver)
        torch.camera = bound
        torch.available = bound.cameraInfo.hasFlashUnit()
        onDispose {
            bound.cameraInfo.zoomState.removeObserver(zoomObserver)
            bound.cameraInfo.torchState.removeObserver(torchObserver)
            bound.cameraInfo.cameraState.removeObserver(stateObserver)
            torch.camera = null
            torch.on = false
        }
    }

    // Once the scan has an outcome, the tapped point no longer matters.
    LaunchedEffect(scanning) {
        if (!scanning) autoZoom.releaseTapFocus()
    }

    var tap by remember { mutableStateOf<FocusTap?>(null) }
    // A tap's place is in the view's own pixels, which run from the left edge whatever the layout
    // direction, so the focus box starts from the top left corner and moves by an absolute offset.
    Box(modifier, contentAlignment = AbsoluteAlignment.TopLeft) {
        request?.let { surface ->
            CameraXViewfinder(
                surfaceRequest = surface,
                coordinateTransformer = transformer,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val c = camera ?: return@detectTransformGestures
                            val state = c.cameraInfo.zoomState.value ?: return@detectTransformGestures
                            autoZoom.onUserZoom()
                            val next = (state.zoomRatio * zoom).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                            // A light detent at every whole zoom ratio, where logical cameras tend to switch lenses.
                            if (floor(next) != floor(state.zoomRatio)) haptics.step()
                            c.cameraControl.setZoomRatio(next)
                        }
                    }
                    .pointerInput(surface, scanning) {
                        detectTapGestures { at ->
                            if (!scanning) return@detectTapGestures
                            val onSurface = with(transformer) { at.transform() }
                            val point = SurfaceOrientedMeteringPointFactory(
                                surface.resolution.width.toFloat(),
                                surface.resolution.height.toFloat(),
                            ).createPoint(onSurface.x, onSurface.y)
                            if (autoZoom.focusAt(point)) {
                                haptics.tap()
                                tap = FocusTap(at, (tap?.id ?: 0) + 1)
                            }
                        }
                    },
            )
        }
        Spacer(
            Modifier
                .fillMaxSize()
                // The still overflows the viewfinder as the cropped picture does, and is cut the same way.
                .clipToBounds()
                .drawBehind {
                    drawRect(Color.Black, alpha = cover.value)
                    still?.let { shown ->
                        withTransform({ transform(shown.toViewfinder) }) {
                            drawImage(shown.image, dstSize = shown.resolution, alpha = frozen.value)
                        }
                    }
                    // White whatever the theme, since it lies on the camera's picture.
                    drawRect(Color.White, alpha = DISABLED_CONTENT_ALPHA * veil.value)
                },
        )
        FocusBox(tap, autoZoom.tapFocus)
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = restarting && !failed,
                enter = fadeIn(fastEffects) + scaleIn(fastSpatial),
                // Leaving with the frozen picture it fades on the picture's spec, and leaving alone,
                // as when the camera fails, it goes the way it came.
                exit = if (failed) fadeOut(fastEffects) + scaleOut(fastSpatial) else fadeOut(slowEffects),
            ) {
                // Contained, since the picture behind it may be as light as the indicator itself.
                ContainedLoadingIndicator(Modifier.size(64.dp))
            }
        }
    }
}

/** Where the user tapped to focus; [id] tells one tap from the next on the same spot. */
private data class FocusTap(val at: Offset, val id: Int)

/** The focus box's side, within the 48dp to 96dp the AOSP camera app draws its focus ring at. */
private val FOCUS_BOX_SIZE = 72.dp

/** How much larger the box starts before it closes in on the tapped point. */
private const val FOCUS_BOX_ENTER_SCALE = 1.4f

private val FOCUS_BOX_STROKE = 2.dp
private val FOCUS_BOX_CORNER = 4.dp

/** The ticks pointing in from the middle of each side, as in the iOS camera's focus square. */
private val FOCUS_BOX_TICK = 6.dp

/**
 * The square camera apps draw where a tap set the focus, with a short tick pointing in from the
 * middle of each side. It closes in on the point as it fades in, and fades out once the camera
 * lets go of that focus, whatever the reason. A new tap replaces it at once.
 */
@Composable
private fun FocusBox(tap: FocusTap?, held: StateFlow<Boolean>) {
    tap ?: return
    key(tap) {
        // Starts hidden, so the box closes in on the point as it appears.
        val shown = remember { MutableTransitionState(false) }
        LaunchedEffect(Unit) {
            shown.targetState = true
            // Waits on the focus itself rather than on what the page last saw of it, so a focus let
            // go before the page saw it held still fades the box.
            held.first { !it }
            shown.targetState = false
        }
        // A fixed colour keeps one light tone in both themes, which the camera image behind needs.
        val color = MaterialTheme.colorScheme.primaryFixed
        AnimatedVisibility(
            visibleState = shown,
            enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec(), initialScale = FOCUS_BOX_ENTER_SCALE) +
                fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            modifier = Modifier.absoluteOffset {
                val half = FOCUS_BOX_SIZE.toPx() / 2
                IntOffset((tap.at.x - half).roundToInt(), (tap.at.y - half).roundToInt())
            },
        ) {
            Canvas(Modifier.size(FOCUS_BOX_SIZE)) {
                val stroke = FOCUS_BOX_STROKE.toPx()
                // The stroke is centred on the outline, so the outline sits half a stroke in from the edge.
                val inset = stroke / 2
                drawRoundRect(
                    color,
                    topLeft = Offset(inset, inset),
                    size = size.copy(width = size.width - stroke, height = size.height - stroke),
                    cornerRadius = CornerRadius(FOCUS_BOX_CORNER.toPx()),
                    style = Stroke(stroke),
                )
                val tick = FOCUS_BOX_TICK.toPx()
                val mid = center
                drawLine(color, Offset(mid.x, inset), Offset(mid.x, inset + tick), stroke)
                drawLine(color, Offset(mid.x, size.height - inset), Offset(mid.x, size.height - inset - tick), stroke)
                drawLine(color, Offset(inset, mid.y), Offset(inset + tick, mid.y), stroke)
                drawLine(color, Offset(size.width - inset, mid.y), Offset(size.width - inset - tick, mid.y), stroke)
            }
        }
    }
}
