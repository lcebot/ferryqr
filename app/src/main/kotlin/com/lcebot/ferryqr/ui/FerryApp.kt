@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.lcebot.ferryqr.ui

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
import com.lcebot.ferryqr.ScanViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

enum class Screen { Scan, History, Generate }

/** Scale the leaving page shrinks to while a back gesture is in progress. */
private const val BACK_SCALE = 0.9f

/** The camera only runs while the scan screen is shown, since the other screens leave composition. */
@Composable
fun FerryApp(vm: ScanViewModel) {
    var saved by rememberSaveable { mutableStateOf(Screen.Scan) }
    val state = remember { SeekableTransitionState(saved) }
    val transition = rememberTransition(state, label = "screen")
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.currentState) { saved = state.currentState }

    fun open(screen: Screen) {
        scope.launch { state.animateTo(screen) }
    }

    // The back gesture scrubs the real transition, so the scan screen is revealed as the user swipes.
    PredictiveBackHandler(enabled = state.currentState != Screen.Scan) { progress ->
        try {
            progress.collect { event -> state.seekTo(event.progress, targetState = Screen.Scan) }
            state.animateTo(Screen.Scan)
        } catch (e: CancellationException) {
            state.animateTo(state.currentState)
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
            Screen.History -> HistoryScreen(onBack = { open(Screen.Scan) })
            Screen.Generate -> GeneratorScreen(onBack = { open(Screen.Scan) })
        }
    }
}
