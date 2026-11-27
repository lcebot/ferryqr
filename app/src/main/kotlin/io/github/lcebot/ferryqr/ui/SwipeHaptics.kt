/*
 * The magnetic swipe of Android 16's notifications, after SystemUI's
 * MagneticNotificationRowManagerImpl, NotificationTargetsHelper and SwipeHelper.
 * Copyright (C) 2025 The Android Open Source Project. Licensed under the Apache License, Version 2.0:
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.pow

/**
 * The vibrations of a notification swiped: low ticks growing with the pull while it is attached,
 * felt at most every [PULL_INTERVAL_MS] however many rows are pulled at once, since they share one
 * vibrator, and a click where it comes loose or rejoins. They are touch
 * vibrations, so the system's touch feedback setting governs them. Without the primitives, the
 * click falls back to the gesture threshold feedback, and the pull to the lightest tick a view
 * gives, which cannot grow stronger and so comes more often instead.
 */
internal class SwipeHaptics(context: Context, private val fallback: Haptics) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    private val tick =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK
    private val ticks = vibrator?.areAllPrimitivesSupported(tick) == true
    private val clicks = vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) == true
    private var lastPull = 0L

    /** [pulled] is how far the row has moved, as a share of [DETACH_AT]. */
    fun pull(pulled: Float) {
        val now = SystemClock.elapsedRealtime()
        if (!ticks) {
            // From a tick every FALLBACK_SLOWEST_MS at rest to one every PULL_INTERVAL_MS where the row comes loose.
            val progress = (pulled / PULLED_SHARE).coerceIn(0f, 1f).pow(PULL_EXPONENT)
            val interval = FALLBACK_SLOWEST_MS - (FALLBACK_SLOWEST_MS - PULL_INTERVAL_MS) * progress
            if (now - lastPull < interval) return
            lastPull = now
            fallback.step()
            return
        }
        if (now - lastPull < PULL_INTERVAL_MS) return
        lastPull = now
        val scale = (PULL_SCALE * pulled.pow(PULL_EXPONENT)).pow(PERCEPTION_EXPONENT).coerceIn(0f, 1f)
        val effect = VibrationEffect.startComposition().apply { repeat(PULL_TICKS) { addPrimitive(tick, scale) } }.compose()
        play(effect)
    }

    fun threshold() {
        if (clicks) {
            play(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, CLICK_SCALE).compose())
        } else {
            fallback.threshold()
        }
    }

    private fun play(effect: VibrationEffect) {
        val vibrator = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            // Sonification is what older releases count as touch feedback.
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }

    private companion object {
        const val PULL_INTERVAL_MS = 60
        const val PULL_TICKS = 5
        const val PULL_SCALE = 0.2f
        // Grows the pull faster than linearly, then makes up for how weak vibrations are felt.
        const val PULL_EXPONENT = 1.27f
        const val PERCEPTION_EXPONENT = 1 / 0.89f
        const val CLICK_SCALE = 0.7f
        const val FALLBACK_SLOWEST_MS = 240f
    }
}
