package io.github.lcebot.ferryqr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Opens one page at a time from a screen: a second tap before the first page has covered the screen
 * is ignored, so a page never opens twice, and the screen takes taps again once it is back in front.
 */
@Stable
internal class OpenOnce {
    // Nothing is drawn from it, so it needs no snapshot state.
    private var opening = false

    /** Runs [open] unless a page opened earlier has not yet given the screen back. */
    operator fun invoke(open: () -> Unit) {
        if (opening) return
        opening = true
        open()
    }

    internal fun resumed() {
        opening = false
    }
}

/** An [OpenOnce] for the screen it is remembered in, ready again each time that screen resumes. */
@Composable
internal fun rememberOpenOnce(): OpenOnce {
    val once = remember { OpenOnce() }
    LifecycleResumeEffect(once) {
        once.resumed()
        onPauseOrDispose {}
    }
    return once
}
