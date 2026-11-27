@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import io.github.lcebot.ferryqr.Phase
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.Sighting
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val MARKER_PADDING = 14.dp
private val MIN_MARKER = 72.dp
private val BADGE_SIZE = 72.dp
private val LOADING_SIZE = 48.dp

/** Same size as the picker's markers, so a found code keeps its marker when the frame freezes. */
private val FOUND_SIZE = 56.dp

/** zxing-cpp often misses a code for a frame or two; the marker rides through such gaps. */
private const val HIDE_DELAY_MS = 250L

/** Frames further apart than this mean the value was left standing in between, not moving. */
private const val STALE_FRAME_NANOS = 100_000_000L

/**
 * How long the success badge stays settled on its code once the code has been handed on, a beat
 * after the badge settles, before it leaves with its brackets. It is long enough for the badge to
 * be seen at rest, and short, since its code has already gone on.
 */
private const val CONFIRMATION_HOLD_MS = 360L

/**
 * Feedback drawn where the code actually is, since codes are read anywhere in the frame: corner
 * brackets follow a located code, a loading indicator sits on it while the WeChat detector works,
 * and a short confirmation plays on it after a successful read. The confirmation, [confirming],
 * hands its code on through [onConfirmed], holds a moment and then asks through [onRelease] to
 * leave, which it does with its brackets whatever the code goes on to. It leaves at once when the
 * scanner moves on before then.
 */
@Composable
fun ScanOverlay(
    sighting: () -> Sighting?,
    lookingCloser: Boolean,
    confirming: Phase.Confirming?,
    onConfirmed: (Detection) -> Unit,
    onRelease: (Detection) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Camera pixels map onto the view from its left edge whatever the layout direction, so what is
    // placed on a code starts from the top left corner and is moved by absolute offsets.
    BoxWithConstraints(modifier, contentAlignment = AbsoluteAlignment.TopLeft) {
        // Read here rather than by the caller, so only the overlay follows the code frame by frame.
        val sighting = sighting()
        val density = LocalDensity.current
        val viewWidth = constraints.maxWidth.toFloat()
        val viewHeight = constraints.maxHeight.toFloat()
        val padding = with(density) { MARKER_PADDING.toPx() }
        val minSide = with(density) { MIN_MARKER.toPx() }
        val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()

        // The confirmation that has just ended still shows while it leaves.
        val lastConfirmation = remember { Last<Phase.Confirming?>(null) }
        SideEffect { if (confirming != null) lastConfirmation.value = confirming }
        val held = confirming ?: lastConfirmation.value
        val confirmation = remember { MutableTransitionState(false) }
        confirmation.targetState = confirming != null
        val confirmationShown = confirmation.currentState || confirmation.targetState

        val confirmedRect = held?.let { c ->
            val b = c.detection.bounds
            if (b.isEmpty) Rect(Offset(viewWidth / 2, viewHeight / 2), 0f)
            else toView(c.frameWidth, c.frameHeight, b.left, b.top, b.right, b.bottom, viewWidth, viewHeight)
        }?.squarePadded(padding, minSide)
        val sightedRect = sighting?.let { s ->
            val b = s.bounds
            toView(
                s.frameWidth, s.frameHeight,
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                viewWidth, viewHeight,
            )
        }?.squarePadded(padding, minSide)
        // While the confirmation shows, leaving included, the brackets stay on the code it confirmed.
        val target = if (confirmationShown) confirmedRect else sightedRect

        val rect = remember { Glide(Rect.Zero, Rect.VectorConverter, AnimationVector4D(0f, 0f, 0f, 0f), placed = false) }
        val moveSpec by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Rect>())
        val goal by rememberUpdatedState(target)
        val holding by rememberUpdatedState(confirmationShown)
        LaunchedEffect(rect) {
            var last: Rect? = null
            var wasHolding = false
            snapshotFlow { goal to holding }.collectLatest { (next, nowHolding) ->
                // A code sighted once a confirmation has left appears in place, as after any code
                // that has left the view.
                val released = wasHolding && !nowHolding
                wasHolding = nowHolding
                if (next != null) {
                    if (released) rect.clear(Rect.Zero)
                    last = next
                    rect.moveTo(next, moveSpec)
                } else {
                    // Through the frames that miss the code, the brackets carry on to where it was
                    // last seen. Once it has been gone for longer, the next code appears in place
                    // instead of sliding in from the last one.
                    coroutineScope {
                        last?.let { launch { rect.moveTo(it, moveSpec) } }
                        delay(2 * HIDE_DELAY_MS)
                    }
                    rect.clear(Rect.Zero)
                    last = null
                }
            }
        }

        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(sightedRect != null) {
            if (sightedRect != null) {
                visible = true
            } else {
                delay(HIDE_DELAY_MS)
                visible = false
            }
        }

        val alpha by animateFloatAsState(
            targetValue = if (visible && !confirmationShown) 1f else 0f,
            animationSpec = effects,
            label = "marker-alpha",
        )
        Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            // The confirmation draws the brackets in its own colour while it shows.
            if (!confirmationShown) drawBrackets(rect.value, Color.White)
        }

        // It comes later than the brackets, only once looking closer has gone on for a moment, so a
        // code read at the first closer look never makes it blink. When the code leaves, it stays
        // with the brackets through the frames that miss it and leaves with them.
        AnimatedVisibility(
            visible = !confirmationShown && lookingCloser && (sighting != null || visible),
            enter = fadeIn(effects) + scaleIn(spatial),
            exit = fadeOut(effects) + scaleOut(spatial),
            modifier = Modifier.centeredOn(LOADING_SIZE) { rect.value.center },
        ) {
            // Contained, since the camera image behind it may be as light as the indicator itself.
            ContainedLoadingIndicator(Modifier.size(LOADING_SIZE))
        }

        // Codes decoded while the rest of a multi-code frame is awaited get a marker as each one
        // arrives. When the sighting ends, the markers last shown stay as long as the brackets do and
        // leave with them, which also carries them through a frame or two that misses the code. A
        // confirmed code hands its marker to the success badge at once, since the badge grows from it.
        val spots = if (!confirmationShown && sighting != null) foundSpots(sighting, viewWidth, viewHeight) else null
        val lastSpots = remember { Last<List<FoundSpot>>(emptyList()) }
        SideEffect {
            when {
                spots != null -> lastSpots.value = spots
                // Markers handed to the badge are done with, and must not come back once it has gone.
                confirming != null -> lastSpots.value = emptyList()
            }
        }
        val shownSpots = spots ?: if (!confirmationShown) lastSpots.value else emptyList()
        shownSpots.forEach { spot ->
            key(spot.text) {
                FoundMarker(spot.center, visible = spots != null || visible)
            }
        }

        // Brackets and badge shrink away together about the code once the confirmation is released,
        // or once the scanner moves on before that. They come in at once while the brackets were
        // already on the code, since they only change colour there; a code read before its brackets
        // showed has them fade in.
        val origin = confirmedRect?.center
            ?.takeIf { viewWidth > 0f && viewHeight > 0f }
            ?.let { TransformOrigin(it.x / viewWidth, it.y / viewHeight) }
            ?: TransformOrigin.Center
        AnimatedVisibility(
            visibleState = confirmation,
            enter = if (visible) EnterTransition.None else fadeIn(effects),
            exit = fadeOut(effects) + scaleOut(spatial, transformOrigin = origin),
            modifier = Modifier.fillMaxSize(),
        ) {
            held?.let { shown ->
                key(shown.detection) {
                    Confirmation(
                        rect = { rect.value },
                        fromMarker = shown.wasShown,
                        handedOn = shown.handedOn,
                        onDone = { onConfirmed(shown.detection) },
                        onRelease = { onRelease(shown.detection) },
                    )
                }
            }
        }
    }
}

/**
 * The brackets on a confirmed code, which take the confirmation's colour from the white they had
 * while the code was sighted, and the success badge on them. Once the code has been [handedOn],
 * both show settled, and [onRelease] follows after [CONFIRMATION_HOLD_MS].
 */
@Composable
private fun Confirmation(
    rect: () -> Rect,
    fromMarker: Boolean,
    handedOn: Boolean,
    onDone: () -> Unit,
    onRelease: () -> Unit,
) {
    // A fixed colour keeps one light tone in both themes, which the camera image behind needs; the
    // dim one sets the brackets apart from the badge they hold.
    val toned = MaterialTheme.colorScheme.primaryFixedDim
    var arrived by remember { mutableStateOf(handedOn) }
    LaunchedEffect(Unit) { arrived = true }
    // Timed here rather than by the phase, so the badge has been seen at rest before it goes, and
    // it goes the same way whether the code then opens an app or shows its card.
    val release by rememberUpdatedState(onRelease)
    LaunchedEffect(handedOn) {
        if (!handedOn) return@LaunchedEffect
        delay(CONFIRMATION_HOLD_MS)
        release()
    }
    val color by animateColorAsState(
        targetValue = if (arrived) toned else Color.White,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "confirmed-color",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        Canvas(Modifier.fillMaxSize()) { drawBrackets(rect(), color) }
        SuccessBadge(center = { rect().center }, fromMarker = fromMarker, settled = handedOn, onDone = onDone)
    }
}

/** What was composed last, held outside the snapshot system, since keeping it is no reason to compose again. */
private class Last<T>(var value: T)

/** A decoded code's text and where its center falls on the view. */
private class FoundSpot(val text: String, val center: Offset)

/** The decoded codes of [sighting] that have a place in the frame, placed on a [viewWidth] by [viewHeight] view. */
private fun foundSpots(sighting: Sighting, viewWidth: Float, viewHeight: Float): List<FoundSpot> =
    sighting.found.filter { it.corners.isNotEmpty() }.map { detection ->
        val c = detection.center
        val at = toView(sighting.frameWidth, sighting.frameHeight, c.x, c.y, c.x, c.y, viewWidth, viewHeight)
        FoundSpot(detection.text, at.center)
    }

/**
 * Pops in with a light tick when its code is appended, follows the code as the camera moves, and
 * fades as it shrinks away once it is no longer [visible].
 */
@Composable
private fun FoundMarker(target: Offset, visible: Boolean) {
    val position = remember { Glide(target, Offset.VectorConverter, AnimationVector2D(0f, 0f), placed = true) }
    val moveSpec by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Offset>())
    val goal by rememberUpdatedState(target)
    LaunchedEffect(position) {
        snapshotFlow { goal }.collectLatest { position.moveTo(it, moveSpec) }
    }
    // Starts hidden, so a marker composed for a code just appended pops in.
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    AnimatedVisibility(
        visibleState = shown,
        enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
            scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()),
        modifier = Modifier.centeredOn(FOUND_SIZE) { position.value },
    ) {
        val haptics = rememberHaptics()
        LaunchedEffect(Unit) { haptics.found() }
        // A fixed colour keeps one light tone in both themes, which the camera image behind needs.
        Box(
            modifier = Modifier
                .size(FOUND_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryFixed),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryFixed,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * A burst that pops onto the code and settles into a circle with a check mark. Composed [settled],
 * as when the activity is made again after the code went on, it shows as it ended.
 */
@Composable
private fun SuccessBadge(center: () -> Offset, fromMarker: Boolean, settled: Boolean, onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val done by rememberUpdatedState(onDone)
    // A code that already wore a found marker grows from that marker instead of popping in again.
    val pop = remember { Animatable(if (settled) 1f else if (fromMarker) FOUND_SIZE / BADGE_SIZE else 0f) }
    val plays = remember { !settled }
    val popSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val morph = remember { Morph(MaterialShapes.SoftBurst, MaterialShapes.Circle) }
    // A fixed colour keeps one light tone in both themes, which the camera image behind needs.
    val fill = MaterialTheme.colorScheme.primaryFixed
    val tint = MaterialTheme.colorScheme.onPrimaryFixed
    val path = remember { android.graphics.Path() }
    val matrix = remember { android.graphics.Matrix() }

    LaunchedEffect(Unit) {
        if (!plays) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        pop.animateTo(1f, popSpec)
        delay(140)
        done()
    }

    Box(
        Modifier
            .centeredOn(BADGE_SIZE, center)
            .size(BADGE_SIZE)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                rotationZ = (1f - pop.value) * -45f
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            path.rewind()
            morph.toPath(pop.value.coerceIn(0f, 1f), path)
            matrix.setScale(size.width, size.height)
            path.transform(matrix)
            drawPath(path.asComposePath(), fill)
        }
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.align(Alignment.Center).size(36.dp),
        )
    }
}

/** Corner brackets around [r]; [Rect.Zero] stands for no place yet, and draws nothing. */
private fun DrawScope.drawBrackets(r: Rect, color: Color) {
    if (r == Rect.Zero) return
    val arm = min(r.width, r.height) * 0.3f
    val corner = min(arm * 0.45f, 10.dp.toPx())
    val brackets = Path().apply {
        moveTo(r.left, r.top + arm); lineTo(r.left, r.top + corner)
        quadraticTo(r.left, r.top, r.left + corner, r.top); lineTo(r.left + arm, r.top)
        moveTo(r.right - arm, r.top); lineTo(r.right - corner, r.top)
        quadraticTo(r.right, r.top, r.right, r.top + corner); lineTo(r.right, r.top + arm)
        moveTo(r.right, r.bottom - arm); lineTo(r.right, r.bottom - corner)
        quadraticTo(r.right, r.bottom, r.right - corner, r.bottom); lineTo(r.right - arm, r.bottom)
        moveTo(r.left + arm, r.bottom); lineTo(r.left + corner, r.bottom)
        quadraticTo(r.left, r.bottom, r.left, r.bottom - corner); lineTo(r.left, r.bottom - arm)
    }
    drawPath(brackets, color, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * A value of several dimensions, such as a point or a rect, that follows a target which may move
 * with every camera frame, the way [Follower] follows a single one. A new target retargets the
 * spring already running, stepped one frame at a time from where the value is and with the speed it
 * has, rather than starting it anew, which would hold the value still on the frame it starts. Until
 * it is [placed], and once [cleared][clear], the next target is taken at once.
 */
private class Glide<T, V : AnimationVector>(
    initial: T,
    private val converter: TwoWayConverter<T, V>,
    private val still: V,
    private var placed: Boolean,
) {
    var value by mutableStateOf(initial)
        private set

    private var velocity = still
    private var lastFrame = 0L

    /** Moves to [target] on [spec], returning once it is there. */
    suspend fun moveTo(target: T, spec: FiniteAnimationSpec<T>) {
        if (!placed) {
            placed = true
            value = target
            return
        }
        val animation = spec.vectorize(converter)
        val to = converter.convertToVector(target)
        while (true) {
            val now = withFrameNanos { it }
            var elapsed = if (lastFrame == 0L) 0L else now - lastFrame
            lastFrame = now
            if (elapsed > STALE_FRAME_NANOS) {
                velocity = still
                elapsed = 0L
            }
            if (elapsed <= 0L) continue
            val from = converter.convertToVector(value)
            if (animation.getDurationNanos(from, to, velocity) <= elapsed) {
                value = target
                velocity = still
                lastFrame = 0L
                return
            }
            value = converter.convertFromVector(animation.getValueFromNanos(elapsed, from, to, velocity))
            velocity = animation.getVelocityFromNanos(elapsed, from, to, velocity)
        }
    }

    /** Leaves the value at [to], with no place, so the next target is taken at once. */
    fun clear(to: T) {
        placed = false
        value = to
        velocity = still
        lastFrame = 0L
    }
}

/**
 * Positions a [size] box so its center follows [center], read at layout time to avoid recomposing.
 * The offset is absolute, as the camera's pixels are, so a right-to-left layout leaves it in place.
 */
private fun Modifier.centeredOn(size: Dp, center: () -> Offset): Modifier = absoluteOffset {
    val c = center()
    val half = size.roundToPx() / 2
    IntOffset(c.x.roundToInt() - half, c.y.roundToInt() - half)
}

/** Maps upright frame pixels onto the preview, which shows the frame center-cropped. */
private fun toView(
    frameWidth: Int, frameHeight: Int,
    left: Float, top: Float, right: Float, bottom: Float,
    viewWidth: Float, viewHeight: Float,
): Rect {
    val scale = max(viewWidth / frameWidth, viewHeight / frameHeight)
    val dx = (viewWidth - frameWidth * scale) / 2f
    val dy = (viewHeight - frameHeight * scale) / 2f
    return Rect(left * scale + dx, top * scale + dy, right * scale + dx, bottom * scale + dy)
}

private fun Rect.squarePadded(padding: Float, minSide: Float): Rect {
    val side = max(max(width, height) + 2 * padding, minSide)
    return Rect(center, side / 2)
}
