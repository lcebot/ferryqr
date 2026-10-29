package com.lcebot.ferryqr.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lcebot.ferryqr.helper.HelperProtocol.ACTION_HANDOFF
import com.lcebot.ferryqr.helper.HelperProtocol.ACTION_PROBE
import com.lcebot.ferryqr.helper.HelperProtocol.EXTRA_ID
import com.lcebot.ferryqr.helper.HelperProtocol.EXTRA_TEXT
import com.lcebot.ferryqr.helper.HelperProtocol.RESULT_ACCEPTED
import com.lcebot.ferryqr.helper.HelperProtocol.RESULT_UNANSWERED
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** A frozen WeChat process holds the probe back, and the notice is not worth waiting longer for. */
private const val PROBE_TIMEOUT_MS = 2_000L

/** A cold WeChat registers its receiver only once its Application exists, so handoffs are retried. */
private const val HANDOFF_RETRY_MS = 250L
private const val HANDOFF_WINDOW_MS = 6_000L

/**
 * FerryQR's end of the optional LSPosed helper in WeChat. Without the module nothing answers, and
 * every call quietly comes to nothing.
 */
object WeChatHelper {
    private val main = Handler(Looper.getMainLooper())

    /** Whether a WeChat process with the module loaded answers right now. */
    suspend fun probe(context: Context): Boolean = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            send(context.applicationContext, Intent(ACTION_PROBE)) { continuation.resume(it == RESULT_ACCEPTED) }
        }
    } ?: false

    /**
     * Asks WeChat to show [text] to its scanner in place of the camera image. Called right after
     * the scanner is launched; a WeChat that is not running yet is asked again until it answers.
     */
    fun handoff(context: Context, text: String) {
        val app = context.applicationContext
        val id = SystemClock.elapsedRealtimeNanos()
        val giveUpAt = SystemClock.uptimeMillis() + HANDOFF_WINDOW_MS
        fun attempt() {
            send(app, Intent(ACTION_HANDOFF).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_ID, id)) { result ->
                if (result == RESULT_UNANSWERED && SystemClock.uptimeMillis() < giveUpAt) {
                    main.postDelayed({ attempt() }, HANDOFF_RETRY_MS)
                }
            }
        }
        attempt()
    }

    private fun send(context: Context, intent: Intent, onResult: (Int) -> Unit) {
        val collector = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onResult(resultCode)
        }
        try {
            context.sendOrderedBroadcast(
                intent.setPackage(HelperProtocol.WECHAT).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                null,
                collector,
                main,
                RESULT_UNANSWERED,
                null,
                null,
            )
        } catch (e: RuntimeException) {
            onResult(RESULT_UNANSWERED)
        }
    }
}
