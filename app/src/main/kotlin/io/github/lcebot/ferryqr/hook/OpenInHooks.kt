package io.github.lcebot.ferryqr.hook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import io.github.lcebot.ferryqr.openin.OpenInApp
import io.github.lcebot.ferryqr.openin.OpenInApps
import io.github.lcebot.ferryqr.openin.OpenInProtocol
import io.github.lcebot.ferryqr.openin.Strategy
import io.github.lcebot.ferryqr.openin.TypeMapping
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How long a handoff waits for the scanner to show up. It covers a cold start of the app, and for
 * a replaced result also the placeholder's wait and feed in [FrameFeed].
 */
private const val ARMED_MS = 12_000L

/**
 * Sets the module up in the main process of a supported app, the one that hosts its scanner and
 * takes handoffs. FerryQR learns whether the module is enabled from the framework, so the app's
 * other processes have no part in it.
 */
internal object OpenInHooks {
    private const val KEY_PACKAGE = "package"
    private const val KEY_CLASS_LOADER = "classLoader"
    private const val KEY_CONTEXT = "context"

    /** What this module generation set up in the process, to be let go of before a hot reload. */
    private class Installed(val app: OpenInApp, val classLoader: ClassLoader) {
        @Volatile
        var context: Context? = null

        @Volatile
        var receiver: BroadcastReceiver? = null

        /** Set once newer code took over; a registration still under way then stands down. */
        @Volatile
        var retired = false
    }

    @Volatile
    private var installed: Installed? = null

    /**
     * [context] is the app's Application when it already exists, as after a hot reload; otherwise
     * the receiver waits for it to be created.
     */
    fun install(
        xposed: XposedInterface,
        app: OpenInApp,
        classLoader: ClassLoader,
        mainProcess: Boolean,
        context: Context? = null,
    ) {
        if (!mainProcess) return
        val current = Installed(app, classLoader).also { installed = it }
        if (app.pkg == OpenInApps.WECHAT) TraceLog.probeWeChat(xposed, classLoader)
        val handoff = try {
            when (val strategy = app.strategy) {
                is Strategy.FinishWithResult -> FinishWithResult(xposed, app.pkg, strategy)
                is Strategy.ReplaceResult -> ReplaceResult(xposed, app.pkg, strategy, classLoader)
                Strategy.FeedCamera -> feedCamera(xposed)
            }
        } catch (t: Throwable) {
            // Without its hooks a handoff would be accepted and then do nothing; declining at once
            // spares FerryQR its retries.
            HookLog.e("hooks failed in ${app.pkg}", t)
            Handoff { false }
        }
        val application = context ?: currentApplication()
        if (application != null) {
            try {
                register(current, application, handoff)
            } catch (t: Throwable) {
                HookLog.e("receiver setup failed", t)
            }
        } else {
            whenApplicationReady(xposed, app.pkg) { register(current, it, handoff) }
        }
    }

    /**
     * The Application of a process already past its start, for a hot reload that came while the
     * old code still waited for it. Null where the framework's internals cannot be reached.
     */
    private fun currentApplication(): Context? = try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    } catch (t: Throwable) {
        null
    }

    /**
     * Lets go of the app before newer module code takes over in it, and returns what that code needs
     * to carry on. A handoff under way at that moment is dropped, and a Camera1 scanner left open
     * gets its frames fed again only once it is opened anew, since its callback is found as it is
     * registered. The state holds only framework and app objects, never this generation's own, so
     * nothing keeps the old code alive. Null when nothing was set up in this process.
     */
    fun release(): Map<String, Any>? {
        val current = installed ?: return null
        installed = null
        current.retired = true
        val context = current.context
        val receiver = current.receiver
        if (context != null && receiver != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already gone with its context.
            }
        }
        // A plain java.util.HashMap, since Kotlin's own map classes come with this generation's code.
        val state = java.util.HashMap<String, Any>()
        state[KEY_PACKAGE] = current.app.pkg
        state[KEY_CLASS_LOADER] = current.classLoader
        if (context != null) state[KEY_CONTEXT] = context
        return state
    }

    /** Sets this generation up from what the previous one handed over in [release]. */
    fun resume(xposed: XposedInterface, saved: Any?) {
        val state = saved as? Map<*, *> ?: return
        // A newer FerryQR may support fewer apps; one it dropped is simply left alone.
        val app = OpenInApps.find(state[KEY_PACKAGE] as? String ?: return) ?: return
        val classLoader = state[KEY_CLASS_LOADER] as? ClassLoader ?: return
        install(xposed, app, classLoader, mainProcess = true, context = state[KEY_CONTEXT] as? Context)
    }

    /**
     * Receivers need a Context, which does not exist yet when the module loads. The hook removes
     * itself once the Application is created, so nothing of it stays behind in the app.
     */
    private fun whenApplicationReady(xposed: XposedInterface, pkg: String, action: (Context) -> Unit) {
        val target = Instrumentation::class.java.getDeclaredMethod("callApplicationOnCreate", Application::class.java)
        val done = AtomicBoolean(false)
        var handle: XposedInterface.HookHandle? = null
        handle = xposed.after(target, "$pkg:application-create") { chain, _ ->
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

    private fun register(current: Installed, context: Context, handoff: Handoff) {
        val filter = IntentFilter(OpenInProtocol.ACTION_HANDOFF)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != OpenInProtocol.ACTION_HANDOFF) return
                val type = intent.getStringExtra(OpenInProtocol.EXTRA_TYPE)
                val text = intent.getStringExtra(OpenInProtocol.EXTRA_TEXT)
                TraceLog.openWindow()
                TraceLog.log(
                    "handoff received in ${current.app.pkg}: id=${intent.getLongExtra(OpenInProtocol.EXTRA_ID, 0L)}, " +
                        "type=$type, text=${if (type == null) "${text?.length} chars" else TraceLog.describe(text)}, " +
                        "image=${intent.getByteArrayExtra(OpenInProtocol.EXTRA_IMAGE)?.size} bytes, " +
                        "cameraFeed=${intent.getBooleanExtra(OpenInProtocol.EXTRA_CAMERA_FEED, false)}",
                )
                val accepted = handoff.accept(intent)
                TraceLog.log("handoff ${if (accepted) "accepted" else "declined"}")
                resultCode = if (accepted) OpenInProtocol.RESULT_ACCEPTED else OpenInProtocol.RESULT_DECLINED
            }
        }
        if (current.retired) return
        // The permission is FerryQR's own signature permission, so only FerryQR can reach these.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, OpenInProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, OpenInProtocol.PERMISSION, null)
        }
        current.context = context
        current.receiver = receiver
        TraceLog.log("receiver registered in ${current.app.pkg}")
        // Retired in between: newer code took over while this one registered, so it leaves at once.
        if (current.retired) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already gone.
            }
        }
    }

    private fun feedCamera(xposed: XposedInterface): Handoff {
        CameraHooks.install(xposed)
        return Handoff { intent ->
            FrameFeed.arm(
                intent.getStringExtra(OpenInProtocol.EXTRA_TEXT),
                intent.getByteArrayExtra(OpenInProtocol.EXTRA_IMAGE),
                intent.getLongExtra(OpenInProtocol.EXTRA_ID, 0L),
            )
        }
    }
}

/** Takes one handoff broadcast on the main thread. Returns whether the app will open its content. */
private fun interface Handoff {
    fun accept(intent: Intent): Boolean
}

/**
 * Closes the camera screen as soon as it is created, returning the content to the screen that
 * opened it. The screen is found through the framework's Instrumentation, so the hook holds even
 * where the screen inherits onCreate, and costs one name comparison per activity.
 */
private class FinishWithResult(
    xposed: XposedInterface,
    pkg: String,
    private val strategy: Strategy.FinishWithResult,
) : Handoff {
    private class Armed(val text: String, val until: Long)

    /** [at] is the elapsedRealtimeNanos of its creation, comparable with a handoff's id. */
    private class Shown(val activity: WeakReference<Activity>, val at: Long)

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var armed: Armed? = null
    private var lastId = 0L

    /**
     * The camera screen created last. A handoff that arrives just after it came up closes it, while
     * one created before the handoff began was opened by the user, and the handoff waits instead.
     */
    @Volatile
    private var shown: Shown? = null

    init {
        val created = listOf(
            Instrumentation::class.java.getMethod("callActivityOnCreate", Activity::class.java, Bundle::class.java),
            Instrumentation::class.java.getMethod(
                "callActivityOnCreate", Activity::class.java, Bundle::class.java, PersistableBundle::class.java,
            ),
        )
        for ((i, method) in created.withIndex()) {
            xposed.after(method, "$pkg:activity-create:$i") { chain, _ ->
                val activity = chain.getArg(0) as? Activity ?: return@after
                if (activity.javaClass.name !in strategy.activities) return@after
                if (activity.callingActivity?.className !in strategy.callers) return@after
                shown = Shown(WeakReference(activity), SystemClock.elapsedRealtimeNanos())
                val a = armed ?: return@after
                armed = null
                if (SystemClock.uptimeMillis() <= a.until) main.post { finish(activity, a.text) }
            }
        }
    }

    override fun accept(intent: Intent): Boolean {
        val text = intent.getStringExtra(OpenInProtocol.EXTRA_TEXT) ?: return false
        val id = intent.getLongExtra(OpenInProtocol.EXTRA_ID, 0L)
        // A retry of a handoff that was already taken must not close the scanner twice.
        if (id != 0L && id == lastId) return true
        lastId = id
        val open = shown
            ?.takeIf { id != 0L && it.at >= id }
            ?.activity?.get()
            ?.takeUnless { it.isFinishing || it.isDestroyed }
        if (open != null) {
            main.post { finish(open, text) }
        } else {
            armed = Armed(text, SystemClock.uptimeMillis() + ARMED_MS)
        }
        return true
    }

    private fun finish(activity: Activity, text: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val data = Intent()
        for ((key, value) in strategy.extras) data.putExtra(key, value.replace(Strategy.TEXT, text))
        activity.setResult(Activity.RESULT_OK, data)
        activity.finish()
    }
}

/**
 * Builds the scanner's result object with the content in place of what its decoder read. The
 * decoder only builds one once it reads a code, so a small placeholder code is shown to its camera
 * through [FrameFeed], and every result made from that placeholder carries the content instead.
 *
 * Pictures, and text while the camera feed is asked for, are shown to the camera as they are. Text
 * also goes that way when the result class is missing from this version of the app.
 */
private class ReplaceResult(
    xposed: XposedInterface,
    pkg: String,
    private val strategy: Strategy.ReplaceResult,
    classLoader: ClassLoader,
) : Handoff {
    private class Armed(val placeholder: String, val text: String, val data: ByteArray, val type: TypeMapping?, val until: Long)

    @Volatile
    private var armed: Armed? = null
    private var lastId = 0L

    /** Whether the result constructor is hooked; text is shown to the camera when it is not. */
    private val replaces: Boolean

    init {
        // The camera carries every picture and every placeholder, so without it nothing reaches the app.
        CameraHooks.install(xposed)
        replaces = try {
            val type = Class.forName(strategy.className, false, classLoader)
            val ctor = type.getDeclaredConstructor(*strategy.params.map { classOf(it, classLoader) }.toTypedArray())
            xposed.around(ctor, "$pkg:result") { chain -> replace(chain) }
            true
        } catch (t: Throwable) {
            HookLog.w("result of $pkg cannot be replaced, text goes through the camera", t)
            false
        }
    }

    override fun accept(intent: Intent): Boolean {
        val id = intent.getLongExtra(OpenInProtocol.EXTRA_ID, 0L)
        val text = intent.getStringExtra(OpenInProtocol.EXTRA_TEXT)
        if (text == null || !replaces || intent.getBooleanExtra(OpenInProtocol.EXTRA_CAMERA_FEED, false)) {
            armed = null
            // A picture shows the code as it is; text alone is drawn as a QR code.
            val picture = intent.getByteArrayExtra(OpenInProtocol.EXTRA_IMAGE)
            TraceLog.log("through the camera: ${if (picture != null) "picture" else "text"}, replaces=$replaces")
            return FrameFeed.arm(if (picture != null) null else text, picture, id)
        }
        // A retry of the handoff already taken keeps its placeholder, so a frame read in between still matches.
        if (id == 0L || id != lastId) {
            lastId = id
            val type = strategy.types[intent.getStringExtra(OpenInProtocol.EXTRA_TYPE) ?: OpenInApps.DEFAULT_TYPE]
                ?: strategy.types[OpenInApps.DEFAULT_TYPE]
            // Upper case letters and digits fit the smallest QR version, which any decoder reads at once.
            val serial = (if (id != 0L) id else SystemClock.elapsedRealtimeNanos()).toULong().toString(36).uppercase()
            armed = Armed("FERRYQR $serial", text, text.toByteArray(Charsets.UTF_8), type, SystemClock.uptimeMillis() + ARMED_MS)
            TraceLog.log("result armed: placeholder=\"FERRYQR $serial\", as ${type?.id} ${type?.name}")
        }
        val placeholder = armed?.placeholder ?: return false
        return FrameFeed.arm(placeholder, null, id)
    }

    private fun replace(chain: XposedInterface.Chain): Any? {
        val typeName = chain.args.getOrNull(strategy.typeNameArg)
        // Plain QR results, the most private, stay out of the log unless a handoff is under way.
        if (typeName != "QR_CODE") {
            TraceLog.openWindow()
            TraceLog.log("decoded: " + chain.args.joinToString(", ") { TraceLog.describe(it) })
            val built = chain.proceed()
            chain.thisObject?.let { TraceLog.dumpLater("decoded $typeName", it) }
            return built
        }
        val a = armed ?: return chain.proceed()
        if (chain.getArg(strategy.textArg) != a.placeholder) {
            TraceLog.log("result while armed, not the placeholder: $typeName")
            return chain.proceed()
        }
        if (SystemClock.uptimeMillis() > a.until) {
            TraceLog.log("placeholder read after the handoff expired")
            armed = null
            return chain.proceed()
        }
        // While the placeholder is fed the decoder may read it several times; each result is replaced.
        val args = chain.args.toTypedArray()
        args[strategy.textArg] = a.text
        args[strategy.dataArg] = a.data.copyOf()
        args[strategy.charsetArg] = Charsets.UTF_8.name()
        a.type?.let {
            args[strategy.typeIdArg] = it.id
            args[strategy.typeNameArg] = it.name
        }
        TraceLog.log("replaced: " + args.joinToString(", ") { TraceLog.describe(it) })
        val built = chain.proceed(args)
        chain.thisObject?.let { TraceLog.dumpLater("replaced", it) }
        return built
    }

    private fun classOf(name: String, classLoader: ClassLoader): Class<*> = when {
        name.endsWith("[]") -> java.lang.reflect.Array.newInstance(classOf(name.dropLast(2), classLoader), 0).javaClass
        else -> PRIMITIVES[name] ?: Class.forName(if ('.' in name) name else "java.lang.$name", false, classLoader)
    }

    private companion object {
        val PRIMITIVES: Map<String, Class<*>> = listOf(
            Boolean::class, Byte::class, Char::class, Short::class, Int::class, Long::class, Float::class, Double::class,
        ).associate { it.javaPrimitiveType!!.name to it.javaPrimitiveType!! }
    }
}
