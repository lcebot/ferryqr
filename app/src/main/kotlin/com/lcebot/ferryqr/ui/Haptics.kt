package com.lcebot.ferryqr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Compose buttons give no haptic feedback of their own; every control goes through this. Each kind
 * of event has its own feel, and an action whose outcome gets feedback does not also buzz on press.
 */
class Haptics(private val feedback: HapticFeedback) {
    /** A plain button press or navigation. */
    fun tap() = feedback.performHapticFeedback(HapticFeedbackType.ContextClick)

    fun toggle(on: Boolean) =
        feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** Something finished successfully: a scan, a copy, a save, a reset. */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    /** Something could not be done. */
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)

    /** A code was found while the rest of the frame is still being read. */
    fun found() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** The lightest feedback, for selections and for passing a step while dragging. */
    fun step() = feedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
