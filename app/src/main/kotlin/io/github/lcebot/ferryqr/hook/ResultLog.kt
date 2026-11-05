package io.github.lcebot.ferryqr.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface

/*
 * TEMPORARY, for learning what WeChat's QBar puts in its results for mini program codes, so they
 * could be returned as results rather than shown as pictures, and for checking what FerryQR returns
 * for other codes. To remove: delete this file, and the ResultLog.attach call and both
 * ResultLog.log calls in OpenInHooks.kt.
 */

/** Logs constructor arguments to the LSPosed log under the tag FerryQR-result. */
internal object ResultLog {
    @Volatile
    private var framework: XposedInterface? = null

    fun attach(xposed: XposedInterface) {
        framework = xposed
    }

    /** [label] tells a result WeChat decoded from one FerryQR replaced it with. */
    fun log(label: String, args: List<Any?>) {
        try {
            framework?.log(Log.INFO, "FerryQR-result", "$label: " + args.joinToString(", ") { describe(it) })
        } catch (_: Throwable) {
        }
    }

    private fun describe(value: Any?): String = when (value) {
        is String -> "\"$value\" (UTF-8 ${value.toByteArray().hex()})"
        is ByteArray -> "bytes[${value.size}] ${value.hex()}"
        else -> value.toString()
    }

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
