@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import io.github.lcebot.ferryqr.Confirmation
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.Sighting
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private val MARKER_PADDING = 14.dp
private val MIN_MARKER = 72.dp
private val BADGE_SIZE = 72.dp
private val LOADING_SIZE = 48.dp

/** Same size as the picker's markers, so a found code keeps its marker when the frame freezes. */
private val FOUND_SIZE = 56.dp

/** zxing-cpp often misses a code for a frame or two; the marker rides through such gaps. */
private const val HIDE_DELAY_MS = 250L

/**
 * How long the success badge stays settled on its code once the code has been handed on, which it
 * is as the badge comes to rest, before it leaves with its brackets. It is long enough for the badge
 * to be seen at rest, and short, since its code has already gone on.
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
    confirming: Confirmation?,
    onConfirmed: (Detection) -> Unit,
    onRelease: (Detection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val placement = remember(viewSize, density) {
        with(density) { Placement(viewSize, MARKER_PADDING.toPx(), MIN_MARKER.toPx()) }
    }
    val placing by rememberUpdatedState(placement)

    // The sighting changes with nearly every camera frame. The overlay composes again only as a code
    // comes into view or leaves it and as its decoded codes change; where they are is read frame by
    // frame by the effects that move the brackets and the markers, outside composition.
    val sightingNow by rememberUpdatedState(sighting)
    val sighted by remember { derivedStateOf { sightingNow() != null } }
    val foundTexts by remember {
        derivedStateOf { sightingNow()?.found?.filter { it.corners.isNotEmpty() }?.map { it.text } }
    }

    // Started from none, so a confirmation already up as the overlay is composed comes in as any
    // other does. A confirmation that has ended stays the current state until its exit has played,
    // so it stays in place while it leaves.
    val confirmationState = remember { MutableTransitionState<Confirmation?>(null) }
    confirmationState.targetState = confirming
    val confirmation = rememberTransition(confirmationState, label = "confirmation")
    val confirmationShown = confirmation.targetState != null || confirmation.currentState != null

    val rect = remember { SpringFollower(Animatable(Rect.Zero, Rect.VectorConverter)) }
    val moveSpec by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Rect>())
    LaunchedEffect(rect) {
        var last: Rect? = null
        var wasHolding = false
        snapshotFlow {
            // While the confirmation shows, leaving included, the brackets stay on the code it confirmed.
            val held = confirmationState.targetState ?: confirmationState.currentState
            if (held != null) {
                placing.brackets(held) to true
            } else {
                sightingNow()?.let { placing.brackets(it) } to false
            }
        }.collectLatest { (next, nowHolding) ->
            // A code sighted once a confirmation has left appears in place, as after any code
            // that has left the view.
            val released = wasHolding && !nowHolding
            wasHolding = nowHolding
            if (next != null) {
                if (released) rect.unplace()
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
                rect.unplace()
                last = null
            }
        }
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(sighted) {
        if (sighted) {
            visible = true
        } else {
            delay(HIDE_DELAY_MS)
            visible = false
        }
    }

    // Camera pixels map onto the view from its left edge whatever the layout direction, so what is
    // placed on a code starts from the top left corner and is moved by absolute offsets.
    Box(modifier.onSizeChanged { viewSize = it }, contentAlignment = AbsoluteAlignment.TopLeft) {
        val alpha by animateFloatAsState(
            targetValue = if (visible && !confirmationShown) 1f else 0f,
            animationSpec = effects,
            label = "marker-alpha",
        )
        val pen = remember { BracketPen() }
        Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            // The confirmation draws the brackets in its own colour while it shows.
            if (!confirmationShown && rect.placed) drawBrackets(rect.value.value, Color.White, pen)
        }

        // It comes later than the brackets, only once looking closer has gone on for a moment, so a
        // code read at the first closer look never makes it blink. When the code leaves, it stays
        // with the brackets through the frames that miss it and leaves with them.
        AnimatedVisibility(
            visible = !confirmationShown && lookingCloser && (sighted || visible),
            enter = fadeIn(effects) + scaleIn(spatial),
            exit = fadeOut(effects) + scaleOut(spatial),
            modifier = Modifier.centeredOn(LOADING_SIZE) { rect.value.value.center },
        ) {
            // Contained, since the camera image behind it may be as light as the indicator itself.
            ContainedLoadingIndicator(Modifier.size(LOADING_SIZE))
        }

        // Codes decoded while the rest of a multi-code frame is awaited get a marker as each one
        // arrives. When the sighting ends, the markers last shown stay as long as the brackets do and
        // leave with them, which also carries them through a frame or two that misses the code. A
        // confirmed code hands its marker to the success badge at once, since the badge grows from it.
        // Markers wait for the view to have its size, so each starts where its code is.
        val spots = if (!confirmationShown && placement.laidOut) foundTexts else null
        val lastSpots = remember { Last<List<String>>(emptyList()) }
        SideEffect {
            when {
                spots != null -> lastSpots.value = spots
                // Markers handed to the badge are done with, and must not come back once it has gone.
                confirming != null -> lastSpots.value = emptyList()
            }
        }
        val shownSpots = spots ?: if (!confirmationShown) lastSpots.value else emptyList()
        shownSpots.forEach { text ->
            key(text) {
                FoundMarker(
                    target = { sightingNow()?.let { placing.center(it, text) } },
                    visible = spots != null || visible,
                )
            }
        }

        // Brackets and badge shrink away together about the code once the confirmation is released,
        // or once the scanner moves on before that. They come in at once while the brackets were
        // already on the code, since they only change colour there; a code read before its brackets
        // showed has them fade in. Each confirmed code is content of its own, which keeps the
        // confirmation it showed while it leaves; handing the code on only updates it in place.
        confirmation.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val enter = if (targetState == null || visible) EnterTransition.None else fadeIn(effects)
                val exit = initialState?.let { left ->
                    fadeOut(effects) + scaleOut(spatial, transformOrigin = placing.origin(left))
                } ?: ExitTransition.None
                (enter togetherWith exit) using null
            },
            contentKey = { it?.detection },
        ) { shown ->
            if (shown != null) {
                ConfirmedCode(
                    rect = rect,
                    fromMarker = shown.wasShown,
                    handedOn = shown.handedOn,
                    onDone = { onConfirmed(shown.detection) },
                    onRelease = { onRelease(shown.detection) },
                )
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
private fun ConfirmedCode(
    rect: SpringFollower<Rect, AnimationVector4D>,
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
    val pen = remember { BracketPen() }
    Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        Canvas(Modifier.fillMaxSize()) { if (rect.placed) drawBrackets(rect.value.value, color, pen) }
        SuccessBadge(center = { rect.value.value.center }, fromMarker = fromMarker, settled = handedOn, onDone = onDone)
    }
}

/** What was composed last, held outside the snapshot system, since keeping it is no reason to compose again. */
private class Last<T>(var value: T)

/**
 * Places what the overlay draws on a view of [size] from the pixels of the upright frame a code was
 * read in: the square of the brackets around a code, [padding] clear of it and at least [minSide]
 * across, and the centers of decoded codes. Nothing has a place before the view has a size.
 */
private class Placement(size: IntSize, private val padding: Float, private val minSide: Float) {
    private val width = size.width.toFloat()
    private val height = size.height.toFloat()
    val laidOut = size.width > 0 && size.height > 0

    fun brackets(confirmation: Confirmation): Rect? {
        if (!laidOut) return null
        val b = confirmation.detection.bounds
        val code = if (b.isEmpty) {
            Rect(Offset(width / 2, height / 2), 0f)
        } else {
            toView(confirmation.frameWidth, confirmation.frameHeight, b.left, b.top, b.right, b.bottom, width, height)
        }
        return code.squarePadded(padding, minSide)
    }

    fun brackets(sighting: Sighting): Rect? {
        if (!laidOut) return null
        val b = sighting.bounds
        return toView(
            sighting.frameWidth, sighting.frameHeight,
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            width, height,
        ).squarePadded(padding, minSide)
    }

    /** The center of the decoded code [text] of [sighting], or null while it has no place in the frame. */
    fun center(sighting: Sighting, text: String): Offset? {
        if (!laidOut) return null
        val code = sighting.found.firstOrNull { it.text == text && it.corners.isNotEmpty() } ?: return null
        val c = code.center
        return toView(sighting.frameWidth, sighting.frameHeight, c.x, c.y, c.x, c.y, width, height).center
    }

    /** Where on the view, as a fraction of it, the brackets of [confirmation] have their center. */
    fun origin(confirmation: Confirmation): TransformOrigin =
        brackets(confirmation)?.center?.let { TransformOrigin(it.x / width, it.y / height) } ?: TransformOrigin.Center
}

/**
 * Pops in with a light tick when its code is appended, follows the code at [target] as the camera
 * moves, and fades as it shrinks away once [visible] turns false. Through frames where [target]
 * has no place, it stays where the code was last seen.
 */
@Composable
private fun FoundMarker(target: () -> Offset?, visible: Boolean) {
    // Starts where the code is. The place is read without composing the marker again as it moves.
    val position = remember {
        SpringFollower(Animatable(Snapshot.withoutReadObservation { target() } ?: Offset.Zero, Offset.VectorConverter))
    }
    val moveSpec by rememberUpdatedState(MaterialTheme.motionScheme.fastSpatialSpec<Offset>())
    val goal by rememberUpdatedState(target)
    LaunchedEffect(position) {
        snapshotFlow { goal() }.filterNotNull().collectLatest { position.follow(it, moveSpec) }
    }
    // Starts hidden, so a marker composed for a code just appended pops in.
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    AnimatedVisibility(
        visibleState = shown,
        enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
            scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()),
        modifier = Modifier.centeredOn(FOUND_SIZE) { position.value.value },
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
    val haptics = rememberHaptics()
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
        haptics.confirm()
        // Handed on once the spring has come to rest, which is when animateTo returns, so the badge
        // is seen settled before its code goes on.
        pop.animateTo(1f, popSpec)
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

/**
 * What a canvas draws its brackets with, kept from one draw to the next, since brackets following a
 * code draw on every frame.
 */
private class BracketPen {
    val path = Path()
    var stroke = Stroke(width = 0f)
}

/** Corner brackets around [r] drawn with [pen]. */
private fun DrawScope.drawBrackets(r: Rect, color: Color, pen: BracketPen) {
    val width = 4.dp.toPx()
    if (pen.stroke.width != width) pen.stroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val arm = min(r.width, r.height) * 0.3f
    val corner = min(arm * 0.45f, 10.dp.toPx())
    pen.path.apply {
        rewind()
        moveTo(r.left, r.top + arm); lineTo(r.left, r.top + corner)
        quadraticTo(r.left, r.top, r.left + corner, r.top); lineTo(r.left + arm, r.top)
        moveTo(r.right - arm, r.top); lineTo(r.right - corner, r.top)
        quadraticTo(r.right, r.top, r.right, r.top + corner); lineTo(r.right, r.top + arm)
        moveTo(r.right, r.bottom - arm); lineTo(r.right, r.bottom - corner)
        quadraticTo(r.right, r.bottom, r.right - corner, r.bottom); lineTo(r.right - arm, r.bottom)
        moveTo(r.left + arm, r.bottom); lineTo(r.left + corner, r.bottom)
        quadraticTo(r.left, r.bottom, r.left, r.bottom - corner); lineTo(r.left, r.bottom - arm)
    }
    drawPath(pen.path, color, style = pen.stroke)
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
