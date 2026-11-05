package io.github.lcebot.ferryqr.openin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.edit
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.Targets
import io.github.lcebot.ferryqr.openin.OpenInProtocol.EXTRA_CAMERA_FEED
import io.github.lcebot.ferryqr.openin.OpenInProtocol.EXTRA_ID
import io.github.lcebot.ferryqr.openin.OpenInProtocol.EXTRA_IMAGE
import io.github.lcebot.ferryqr.openin.OpenInProtocol.EXTRA_TEXT
import io.github.lcebot.ferryqr.openin.OpenInProtocol.EXTRA_TYPE
import io.github.lcebot.ferryqr.openin.OpenInProtocol.RESULT_ACCEPTED
import io.github.lcebot.ferryqr.openin.OpenInProtocol.RESULT_UNANSWERED
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** A cold app registers its receiver only once its Application exists, so handoffs are retried. */
private const val HANDOFF_RETRY_MS = 250L
private const val HANDOFF_WINDOW_MS = 6_000L

private const val IMAGE_QUALITY = 92

private const val PREFS = "open_in"
private const val CAMERA_FEED_KEY = "camera_feed:"

/** What the LSPosed framework reports about the module in one app. */
enum class ModuleState {
    /** No framework service reached FerryQR: the module is not enabled, or LSPosed is not running. */
    Inactive,

    /** The module is enabled, but this app is not in its scope. */
    OutOfScope,

    /** The module is enabled for the app and loads whenever the app starts. */
    Enabled,

    /** The module is loaded in the running app. */
    Loaded,

    /** The running app still holds the FerryQR from before an update, until it restarts. */
    Outdated,
}

/**
 * FerryQR's end of the optional LSPosed module in other apps. Whether the module is enabled for an
 * app comes from the framework itself, through the libxposed service it hands FerryQR when FerryQR
 * starts, so it is known whether or not the app is running. Without the module there is no service,
 * nothing answers a handoff, and every call quietly comes to nothing.
 */
object OpenInModule {
    private val main = Handler(Looper.getMainLooper())
    private val service = MutableStateFlow<XposedService?>(null)
    private val states = ConcurrentHashMap<String, MutableStateFlow<ModuleState>>()
    private val readies = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    /**
     * Every question to the framework runs here, one at a time and off the main thread: each is a
     * binder call, and the answers must land in the order they were asked.
     */
    private val framework = Executors.newSingleThreadExecutor { Thread(it, "ferry-framework") }.asCoroutineDispatcher()

    init {
        // The framework may hand the service over before this runs; XposedServiceHelper keeps it until then.
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service.value = bound
                framework.dispatch(EmptyCoroutineContext) { update() }
            }

            override fun onServiceDied(dead: XposedService) {
                if (service.compareAndSet(dead, null)) framework.dispatch(EmptyCoroutineContext) { update() }
            }
        })
    }

    private fun stateFlow(pkg: String) = states.computeIfAbsent(pkg) { MutableStateFlow(ModuleState.Inactive) }

    private fun readyFlow(pkg: String) = readies.computeIfAbsent(pkg) { MutableStateFlow(false) }

    /** The module's state in [pkg]. Every call returns the same flow. */
    fun state(pkg: String): StateFlow<ModuleState> = stateFlow(pkg)

    /**
     * Whether [pkg] takes content from FerryQR: the module is enabled for it, whether or not the app
     * is running, unless the running app still holds an older FerryQR. Every call returns the same flow, so a composable collecting it is not restarted
     * on recomposition.
     */
    fun ready(pkg: String): StateFlow<Boolean> = readyFlow(pkg)

    /**
     * Asks the framework again. The scope and the running apps change while FerryQR stays open, as
     * when the user returns from LSPosed or an app restarts.
     */
    suspend fun refresh() = withContext(framework) { update() }

    private fun update() {
        val bound = service.value
        var scope = emptySet<String>()
        // Each main process, named after its package, and whether any copy of it runs current code.
        // A cloned app or a work profile runs a second process of the same name.
        var running = emptyMap<String, Boolean>()
        if (bound != null) {
            try {
                scope = bound.scope.toSet()
                running = try {
                    bound.runningTargets.groupBy({ it.processName }, { it.state == HookedTarget.State.UP_TO_DATE })
                        .mapValues { (_, current) -> current.any { it } }
                } catch (e: UnsupportedOperationException) {
                    // A framework older than API 102 cannot list running apps; scope alone still tells.
                    emptyMap()
                }
            } catch (e: RuntimeException) {
                // A dying service or a refused call leaves what is known as it is; a death notice
                // follows with its own update.
                return
            }
            // The service went away or was replaced while it was asked; the newer update decides.
            if (service.value !== bound) return
        }
        for (app in OpenInApps.all) {
            val state = when {
                bound == null -> ModuleState.Inactive
                app.pkg !in scope -> ModuleState.OutOfScope
                else -> when (running[app.pkg]) {
                    null -> ModuleState.Enabled
                    true -> ModuleState.Loaded
                    false -> ModuleState.Outdated
                }
            }
            stateFlow(app.pkg).value = state
            // A running app with an older FerryQR may not understand this one, so it waits for a restart.
            readyFlow(app.pkg).value = state == ModuleState.Enabled || state == ModuleState.Loaded
        }
    }

    /**
     * Asks [pkg] to open [text] as though its scanner had read it. Called right after the scanner
     * is launched; an app that is not running yet is asked again until it answers. [type] is a key
     * of the app's [Strategy.ReplaceResult.types], for apps whose result names the kind of code.
     * [picture] is the code drawn again, which the scanner is shown where the text cannot be returned
     * as a result; without it the text is shown as a QR code, which suits QR content only.
     */
    fun handoff(context: Context, pkg: String, text: String, type: String? = null, picture: Bitmap? = null) {
        val cameraFeed = cameraFeed(context, pkg)
        val jpeg = picture?.let(::jpeg)
        handoff(context, pkg) { intent ->
            intent.putExtra(EXTRA_TEXT, text).putExtra(EXTRA_CAMERA_FEED, cameraFeed)
            if (type != null) intent.putExtra(EXTRA_TYPE, type)
            if (jpeg != null) intent.putExtra(EXTRA_IMAGE, jpeg)
        }
    }

    /** Like [handoff], for a code that cannot be read: the app's scanner is shown [image] instead. */
    fun handoff(context: Context, pkg: String, image: Bitmap) {
        val jpeg = jpeg(image)
        handoff(context, pkg) { it.putExtra(EXTRA_IMAGE, jpeg) }
    }

    private fun jpeg(image: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        image.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, out)
        out.toByteArray()
    }

    /**
     * Whether text for [pkg] is shown to its camera instead of replacing its decoder's result.
     * Slower, but it works when the result cannot be replaced in that app's version. Only apps
     * whose result is replaced read it.
     */
    fun cameraFeed(context: Context, pkg: String): Boolean =
        prefs(context).getBoolean(CAMERA_FEED_KEY + pkg, false)

    fun setCameraFeed(context: Context, pkg: String, enabled: Boolean) {
        prefs(context).edit { putBoolean(CAMERA_FEED_KEY + pkg, enabled) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun handoff(context: Context, pkg: String, content: (Intent) -> Unit) {
        val app = context.applicationContext
        val id = SystemClock.elapsedRealtimeNanos()
        val giveUpAt = SystemClock.uptimeMillis() + HANDOFF_WINDOW_MS
        fun attempt() {
            val intent = Intent(OpenInProtocol.ACTION_HANDOFF).also(content).putExtra(EXTRA_ID, id)
            send(app, pkg, intent) { result ->
                // TEMPORARY, removed together with the hook package's TraceLog.
                android.util.Log.i("FerryQR-trace", "handoff $id to $pkg answered $result")
                when {
                    // The app has taken the content, which is the one moment FerryQR knows it arrived.
                    result == RESULT_ACCEPTED -> ferried(app, pkg)
                    result == RESULT_UNANSWERED && SystemClock.uptimeMillis() < giveUpAt ->
                        main.postDelayed({ attempt() }, HANDOFF_RETRY_MS)
                }
            }
        }
        attempt()
    }

    private fun ferried(app: Context, pkg: String) {
        val name = Targets.of(pkg)?.name ?: return
        Toast.makeText(app, app.getString(R.string.ferried_to_app, app.getString(name)), Toast.LENGTH_SHORT).show()
    }

    private fun send(context: Context, pkg: String, intent: Intent, onResult: (Int) -> Unit) {
        val collector = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onResult(resultCode)
        }
        try {
            context.sendOrderedBroadcast(
                intent.setPackage(pkg).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
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
