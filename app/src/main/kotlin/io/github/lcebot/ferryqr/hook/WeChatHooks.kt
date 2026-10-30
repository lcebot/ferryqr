package io.github.lcebot.ferryqr.hook

import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.lcebot.ferryqr.helper.HelperProtocol
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sets the module up in one WeChat process. Only the main process, which hosts the scanner, gets
 * the camera hooks; every process answers probes, because the main one is often not running or
 * frozen while FerryQR is in front, and the module loads there just the same once it starts.
 */
internal object WeChatHooks {

    fun install(xposed: XposedInterface, mainProcess: Boolean) {
        if (mainProcess) {
            try {
                CameraHooks.install(xposed)
            } catch (t: Throwable) {
                // Without the camera hooks a handoff would be accepted and then do nothing, so
                // this process stays silent and FerryQR falls back to the plain scanner.
                HookLog.e("camera hooks failed", t)
                return
            }
        }
        whenApplicationReady(xposed) { register(it, mainProcess) }
    }

    /**
     * Receivers need a Context, which does not exist yet when the module loads. The hook removes
     * itself once the Application is created, so nothing of it stays behind in WeChat.
     */
    private fun whenApplicationReady(xposed: XposedInterface, action: (Context) -> Unit) {
        val target = Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
        val done = AtomicBoolean(false)
        var handle: XposedInterface.HookHandle? = null
        handle = xposed.after(target, "application-create") { chain, _ ->
            val app = chain.getArg(0) as? Application ?: return@after
            if (!done.compareAndSet(false, true)) return@after
            // Removing a hook from inside its own callback is left to the next main loop turn.
            Handler(Looper.getMainLooper()).post { handle?.unhook() }
            try {
                action(app)
            } catch (t: Throwable) {
                HookLog.e("receiver setup failed", t)
            }
        }
    }

    private fun register(context: Context, mainProcess: Boolean) {
        val filter = IntentFilter(HelperProtocol.ACTION_PROBE)
        if (mainProcess) filter.addAction(HelperProtocol.ACTION_HANDOFF)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    HelperProtocol.ACTION_PROBE -> resultCode = HelperProtocol.RESULT_ACCEPTED
                    HelperProtocol.ACTION_HANDOFF -> {
                        val armed = FrameFeed.arm(
                            intent.getStringExtra(HelperProtocol.EXTRA_TEXT),
                            intent.getByteArrayExtra(HelperProtocol.EXTRA_IMAGE),
                            intent.getLongExtra(HelperProtocol.EXTRA_ID, 0L),
                        )
                        resultCode = if (armed) HelperProtocol.RESULT_ACCEPTED else HelperProtocol.RESULT_DECLINED
                    }
                }
            }
        }
        // The permission is FerryQR's own signature permission, so only FerryQR can reach these.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, HelperProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, HelperProtocol.PERMISSION, null)
        }
    }
}
