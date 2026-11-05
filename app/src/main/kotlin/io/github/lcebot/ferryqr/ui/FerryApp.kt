@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.lcebot.ferryqr.ScanViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

enum class Screen { Scan, History, Generate, Settings }

/**
 * The back transition a gesture scrubs. Over the first [GESTURE_SHARE] of it the leaving page
 * shrinks to [BACK_SCALE], rounds its corners and shifts away from the swiped edge, staying fully
 * visible with the camera appearing around it; the gesture maps onto that part alone. Once the
 * gesture commits, the page fades out over the rest.
 */
private const val BACK_MS = 450
private const val GESTURE_SHARE = 0.6f
private const val BACK_SCALE = 0.9f
private val BACK_CORNER = 28.dp

/** How far inside its shift the page stays, as in the system's own cross-activity back animation. */
private val BACK_SHIFT_MARGIN = 8.dp

/** How long a cancelled gesture takes to settle back from its furthest point. */
private const val SETTLE_MS = 300

/**
 * The camera only runs while the scan screen is shown, since the other screens leave composition.
 * [openFavorite] is a favorite's content from its app shortcut, shown in history without the
 * camera starting first; [onFavoriteOpened] clears it once shown.
 */
@Composable
fun FerryApp(vm: ScanViewModel, openFavorite: String? = null, onFavoriteOpened: () -> Unit = {}) {
    var saved by rememberSaveable { mutableStateOf(if (openFavorite != null) Screen.History else Screen.Scan) }
    val state = remember { SeekableTransitionState(saved) }
    val transition = rememberTransition(state, label = "screen")
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.currentState) { saved = state.currentState }

    fun open(screen: Screen) {
        scope.launch { state.animateTo(screen) }
    }

    // A shortcut used while the app is already open arrives here rather than at launch.
    LaunchedEffect(openFavorite) {
        if (openFavorite != null && state.currentState != Screen.History) state.animateTo(Screen.History)
    }

    // The swiped edge while a back gesture drives the transition, which then uses the gesture's own
    // motion; null for a back button or the app's own back arrow.
    var backEdge by remember { mutableStateOf<Int?>(null) }
    val gesture = remember { BackGesture() }

    // Runs a gesture's ending in the screen's own scope. It clears the edge however it ends,
    // including when the back arrow or a shortcut takes the transition over, unless a newer
    // gesture has started since and owns the edge.
    fun settle(ending: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                ending()
            } finally {
                if (gesture.settling === coroutineContext.job) {
                    gesture.settling = null
                    gesture.committing = false
                    backEdge = null
                }
            }
        }
        gesture.settling = job
        job.start()
    }

    // A gesture's end, committed or cancelled, cancels the coroutine running this block, and so does
    // a new gesture starting. Both endings therefore run in the screen's own scope: awaited in here,
    // they would be cancelled with the gesture and leave the transition partway.
    PredictiveBackHandler(enabled = state.currentState != Screen.Scan) { progress ->
        // A gesture while the page is already leaving is taken but ignored: handing it back to the
        // system would close the app, and following it would pull the page back into view.
        if (gesture.committing) {
            progress.collect {}
            return@PredictiveBackHandler
        }
        gesture.settling?.cancel()
        gesture.settling = null
        val from = state.currentState
        try {
            progress.collect { event ->
                backEdge = event.swipeEdge
                state.seekTo(event.progress * GESTURE_SHARE, targetState = Screen.Scan)
            }
            gesture.committing = true
            settle { state.animateTo(Screen.Scan) }
        } catch (e: CancellationException) {
            settle { state.seekBack(from) }
            throw e
        }
    }

    // The camera view is always dark; the other screens follow the theme.
    val window = LocalActivity.current?.window
    val view = LocalView.current
    val lightBars = state.currentState != Screen.Scan && !isSystemInDarkTheme()
    SideEffect {
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    val slide = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val shiftMargin = with(LocalDensity.current) { BACK_SHIFT_MARGIN.roundToPx() }
    transition.AnimatedContent(
        transitionSpec = {
            val edge = backEdge
            when {
                targetState != Screen.Scan ->
                    (slideInHorizontally(slide) { it / 4 } + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(slide) { -it / 4 } + fadeOut(fade))
                // Every part lasts the whole transition, so the gesture's fraction means the same
                // moment for all of them.
                edge != null -> {
                    val direction = if (edge == BackEventCompat.EDGE_LEFT) 1 else -1
                    fadeIn(tween(BACK_MS, easing = during(0f, GESTURE_SHARE / 2))) togetherWith
                        (scaleOut(tween(BACK_MS, easing = during(0f, GESTURE_SHARE)), targetScale = BACK_SCALE) +
                            slideOutHorizontally(tween(BACK_MS, easing = during(0f, GESTURE_SHARE))) {
                                direction * (it / 20 - shiftMargin).coerceAtLeast(0)
                            } +
                            fadeOut(tween(BACK_MS, easing = during(GESTURE_SHARE, 1f, FastOutLinearInEasing)))) using null
                }
                else -> fadeIn(fade) togetherWith
                    (scaleOut(scale, targetScale = BACK_SCALE) + slideOutHorizontally(slide) { it / 8 } + fadeOut(fade))
            }.apply {
                // The page going back stays above the camera, which shows around it as it shrinks.
                if (targetState == Screen.Scan) targetContentZIndex = -1f
            }
        },
    ) { target ->
        when (target) {
            Screen.Scan -> ScanScreen(
                vm = vm,
                onOpenHistory = { open(Screen.History) },
                onOpenGenerator = { open(Screen.Generate) },
                onOpenSettings = { open(Screen.Settings) },
            )
            Screen.History -> BackShape(gesture = backEdge != null) {
                HistoryScreen(
                    onBack = { open(Screen.Scan) },
                    openText = openFavorite,
                    onOpened = onFavoriteOpened,
                )
            }
            Screen.Generate -> BackShape(gesture = backEdge != null) {
                GeneratorScreen(onBack = { open(Screen.Scan) })
            }
            Screen.Settings -> BackShape(gesture = backEdge != null) {
                SettingsScreen(onBack = { open(Screen.Scan) })
            }
        }
    }
}

/** Only touched on the main thread, from the back handler and the endings it starts. */
private class BackGesture {
    /** The running ending of the last gesture, committed or cancelled. */
    var settling: Job? = null

    /** Whether the page is leaving after a committed gesture. */
    var committing = false
}

/** Rounds the page's corners as a back gesture shrinks it, in step with the shrinking. */
@Composable
private fun AnimatedContentScope.BackShape(gesture: Boolean, content: @Composable () -> Unit) {
    val corner by transition.animateDp(
        transitionSpec = { if (gesture) tween(BACK_MS, easing = during(0f, GESTURE_SHARE)) else snap() },
        label = "corner",
    ) { if (gesture && it == EnterExitState.PostExit) BACK_CORNER else 0.dp }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                shape = RoundedCornerShape(corner)
                clip = corner > 0.dp
            },
    ) { content() }
}

/** Runs [easing] between [start] and [end] of an animation's time, holding still before and after. */
private fun during(start: Float, end: Float, easing: Easing = LinearEasing) =
    Easing { t -> easing.transform(((t - start) / (end - start)).coerceIn(0f, 1f)) }

/**
 * Seeks a cancelled back gesture to its start, then drops the scan screen it revealed. Animating to
 * the current screen instead would replay that screen's opening from the camera.
 */
private suspend fun SeekableTransitionState<Screen>.seekBack(to: Screen) {
    val start = fraction
    val durationNanos = SETTLE_MS * 1_000_000L * (start / GESTURE_SHARE).coerceAtMost(1f)
    if (durationNanos > 0f) {
        val began = withFrameNanos { it }
        do {
            val t = ((withFrameNanos { it } - began) / durationNanos).coerceAtMost(1f)
            seekTo(start * (1f - LinearOutSlowInEasing.transform(t)))
        } while (t < 1f)
    }
    snapTo(to)
}
