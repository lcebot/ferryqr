@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.core.view.WindowCompat
import io.github.lcebot.ferryqr.ScanViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

enum class Screen { Scan, History, Generate }

/** Scale the leaving page shrinks to while a back gesture is in progress. */
private const val BACK_SCALE = 0.9f

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

    // The back gesture scrubs the real transition, so the scan screen is revealed as the user swipes.
    // A cancelled gesture cancels the coroutine running this block, and so does a new gesture
    // starting, so both endings animate in the screen's own scope. Awaited in here, the animation
    // would be cancelled along with the gesture and leave the transition partway: a sliver of the
    // camera would stay visible, and later gestures would act on a transition that never settles.
    PredictiveBackHandler(enabled = state.currentState != Screen.Scan) { progress ->
        try {
            progress.collect { event -> state.seekTo(event.progress, targetState = Screen.Scan) }
            scope.launch { state.animateTo(Screen.Scan) }
        } catch (e: CancellationException) {
            scope.launch { state.animateTo(state.currentState) }
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
    transition.AnimatedContent(
        transitionSpec = {
            if (targetState != Screen.Scan) {
                (slideInHorizontally(slide) { it / 4 } + fadeIn(fade)) togetherWith
                    (slideOutHorizontally(slide) { -it / 4 } + fadeOut(fade))
            } else {
                fadeIn(fade) togetherWith
                    (scaleOut(scale, targetScale = BACK_SCALE) + slideOutHorizontally(slide) { it / 8 } + fadeOut(fade))
            }
        },
    ) { target ->
        when (target) {
            Screen.Scan -> ScanScreen(
                vm = vm,
                onOpenHistory = { open(Screen.History) },
                onOpenGenerator = { open(Screen.Generate) },
            )
            Screen.History -> HistoryScreen(
                onBack = { open(Screen.Scan) },
                openText = openFavorite,
                onOpened = onFavoriteOpened,
            )
            Screen.Generate -> GeneratorScreen(onBack = { open(Screen.Scan) })
        }
    }
}
