package io.github.lcebot.ferryqr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * [on] held steady for what it shows: it turns on only once [on] has stayed on for [showAfterMs],
 * so a state that passes quickly never shows, and off only once [on] has stayed off for [holdMs],
 * so a gap of a frame or two does not make it blink.
 */
@Composable
internal fun rememberSteady(on: Boolean, showAfterMs: Long, holdMs: Long): Boolean {
    var steady by remember { mutableStateOf(false) }
    LaunchedEffect(on) {
        if (on == steady) return@LaunchedEffect
        delay(if (on) showAfterMs else holdMs)
        steady = on
    }
    return steady
}
