package io.github.lcebot.ferryqr.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/*
 * TEMPORARY, for following a handoff through a supported app while mini program codes fail to open
 * in WeChat. To remove: delete this file, every TraceLog call, all in the hook package, and the
 * FerryQR-trace line in OpenInModule.handoff.
 */

/**
 * Logs each step of a handoff to the LSPosed log under the tag FerryQR-trace. In WeChat it also
 * probes the scan handling the way a Frida script would: the methods that route a scan result are
 * logged with their arguments and results, and for a while after a handoff or a mini program
 * result, WeChat's own scan logging, which goes to its xlog files only, is copied here too.
 */
internal object TraceLog {
    private const val TAG = "FerryQR-trace"

    /** How long WeChat's own scan logging is copied after a handoff or a mini program result. */
    private const val WINDOW_MS = 20_000L

    /** WeChat's log tags around scanning and resolving codes. */
    private val XLOG_TAGS = listOf("QBar", "Scan", "scan", "A8Key", "a8key", "WxaCode", "AppBrandLaunch")

    /** QBarStringHandler, which routes a scan result by its text, code type and version. */
    private const val STRING_HANDLER = "r84.v"
    private val STRING_HANDLER_METHODS = setOf(
        "g", "h", "f", "l", "x", "k", "q", "o", "a", "b", "C", "r", "u", "v", "w", "onSceneEnd",
    )

    /** The code type checks, such as which types go to [STRING_HANDLER]. */
    private const val CODE_TYPES = "com.tencent.mm.plugin.scanner.j1"

    private const val XLOG = "com.tencent.mars.xlog.Log"

    private val windowUntil = AtomicLong(0)

    /** WeChat fills in a result's extra fields right after building it, so they are read later. */
    private val DUMP_DELAYS_MS = longArrayOf(50, 500)

    @Volatile
    private var framework: XposedInterface? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(xposed: XposedInterface) {
        framework = xposed
    }

    fun log(message: String) {
        // Logging runs inside the app and must never be what breaks it.
        try {
            framework?.log(Log.INFO, TAG, message, null)
        } catch (_: Throwable) {
        }
    }

    /** Starts or extends the window in which WeChat's own scan logging is copied. */
    fun openWindow() {
        windowUntil.set(SystemClock.uptimeMillis() + WINDOW_MS)
    }

    /** Sets up the probes in WeChat; each one that cannot be found is skipped and reported. */
    fun probeWeChat(xposed: XposedInterface, classLoader: ClassLoader) {
        probe(xposed, classLoader, STRING_HANDLER, STRING_HANDLER_METHODS, dumpArgs = setOf("onSceneEnd"))
        probe(xposed, classLoader, CODE_TYPES, setOf("a", "b", "c"))
        mirrorXlog(xposed, classLoader)
    }

    /** Logs each call of the [names] methods of [className], with arguments and result. */
    private fun probe(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        className: String,
        names: Set<String>,
        dumpArgs: Set<String> = emptySet(),
    ) {
        val type = try {
            Class.forName(className, false, classLoader)
        } catch (t: Throwable) {
            log("probe: $className not found")
            return
        }
        for ((i, method) in type.declaredMethods.filter { it.name in names }.withIndex()) {
            try {
                val label = "$className.${method.name}"
                xposed.around(method, "trace:$label:$i") { chain ->
                    log("call $label(" + chain.args.joinToString(", ") { describe(it) } + ")")
                    if (method.name in dumpArgs) {
                        chain.args.filterNotNull().filter { it !is String && it !is Number && it !is Boolean }
                            .forEach { log("  arg ${it.javaClass.name}: ${fields(it)}") }
                    }
                    try {
                        chain.proceed().also { result ->
                            if (method.returnType != Void.TYPE) log("  $label returned ${describe(result)}")
                        }
                    } catch (t: Throwable) {
                        log("  $label threw $t")
                        throw t
                    }
                }
            } catch (t: Throwable) {
                log("probe: ${method.name} of $className not hooked: $t")
            }
        }
        log("probe: $className hooked")
    }

    /** Copies WeChat's own log lines about scanning while the window is open. */
    private fun mirrorXlog(xposed: XposedInterface, classLoader: ClassLoader) {
        val type = try {
            Class.forName(XLOG, false, classLoader)
        } catch (t: Throwable) {
            log("probe: $XLOG not found")
            return
        }
        val levels = setOf("v", "d", "i", "w", "e", "f")
        val methods: List<Method> = type.declaredMethods.filter {
            it.name in levels && Modifier.isStatic(it.modifiers) && it.parameterTypes.firstOrNull() == String::class.java
        }
        for ((i, method) in methods.withIndex()) {
            try {
                xposed.before(method, "trace:xlog:${method.name}:$i") { chain ->
                    if (SystemClock.uptimeMillis() > windowUntil.get()) return@before
                    val tag = chain.args.getOrNull(0) as? String ?: return@before
                    if (XLOG_TAGS.none { it in tag }) return@before
                    val format = chain.args.getOrNull(1) as? String ?: return@before
                    val args = chain.args.getOrNull(2) as? Array<*>
                    val message = if (args.isNullOrEmpty()) format else try {
                        String.format(Locale.ROOT, format, *args)
                    } catch (t: Throwable) {
                        format + " | " + args.joinToString(", ")
                    }
                    log("xlog ${method.name}/$tag: $message")
                }
            } catch (t: Throwable) {
                log("probe: xlog ${method.name} not hooked: $t")
            }
        }
        log("probe: xlog mirrored through ${methods.size} methods")
    }

    /** Logs every field of [result] a little after it was built, under [label]. */
    fun dumpLater(label: String, result: Any) {
        val ref = WeakReference(result)
        for (delay in DUMP_DELAYS_MS) {
            main.postDelayed({ ref.get()?.let { log("$label +${delay}ms: ${fields(it)}") } }, delay)
        }
    }

    fun describe(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"$value\" (${value.length})"
        is ByteArray -> "bytes[${value.size}] " + value.take(48).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        is Number, is Boolean -> value.toString()
        else -> value.javaClass.name + " " + value.toString().take(200)
    }

    private fun fields(value: Any): String {
        val parts = mutableListOf<String>()
        var type: Class<*>? = value.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val shown = try {
                    field.isAccessible = true
                    describe(field.get(value))
                } catch (t: Throwable) {
                    "?"
                }
                parts += "${type.simpleName}.${field.name}=$shown"
            }
            type = type.superclass
        }
        return parts.joinToString(", ")
    }
}
