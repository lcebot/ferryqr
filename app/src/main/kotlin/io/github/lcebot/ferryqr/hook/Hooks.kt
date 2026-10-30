package io.github.lcebot.ferryqr.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap

/*
 * Thin wrappers over the libxposed hook builder. module.prop sets exceptionMode=protective, so an
 * exception thrown by a hooker is handled by the framework and the original call still runs.
 * Every hook gets an id, so installing the same one again replaces it instead of stacking.
 */

private const val ID_PREFIX = "ferry:"

internal fun XposedInterface.after(target: Executable, id: String, action: (Chain, Any?) -> Unit): HookHandle =
    hook(target).setId(ID_PREFIX + id).intercept { chain ->
        val result = chain.proceed()
        action(chain, result)
        result
    }

internal fun XposedInterface.before(target: Executable, id: String, action: (Chain) -> Unit): HookHandle =
    hook(target).setId(ID_PREFIX + id).intercept { chain ->
        action(chain)
        chain.proceed()
    }

internal fun XposedInterface.around(target: Executable, id: String, hooker: (Chain) -> Any?): HookHandle =
    hook(target).setId(ID_PREFIX + id).intercept { chain -> hooker(chain) }

/**
 * Problems only, never progress, and each message once per process: several of them come from
 * per-frame paths, which would otherwise flood the LSPosed log.
 */
internal object HookLog {
    private const val TAG = "FerryQR"

    @Volatile
    private var framework: XposedInterface? = null
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun attach(xposed: XposedInterface) {
        framework = xposed
    }

    fun w(message: String, error: Throwable? = null) = report(Log.WARN, message, error)

    fun e(message: String, error: Throwable? = null) = report(Log.ERROR, message, error)

    private fun report(priority: Int, message: String, error: Throwable?) {
        if (!reported.add(message)) return
        // Logging runs inside WeChat and must never be what breaks it.
        try {
            framework?.log(priority, TAG, message, error)
        } catch (_: Throwable) {
        }
    }
}
