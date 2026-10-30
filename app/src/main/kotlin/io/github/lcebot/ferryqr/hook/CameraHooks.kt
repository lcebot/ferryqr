@file:Suppress("DEPRECATION")

package io.github.lcebot.ferryqr.hook

import android.hardware.Camera
import android.media.Image
import android.media.ImageReader
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * Where camera frames reach WeChat's scanner. Only framework methods are hooked, and the methods
 * WeChat implements for framework interfaces, whose names minification cannot change, so nothing
 * here depends on a WeChat version. Every hooker returns at once unless [FrameFeed] is armed.
 */
internal object CameraHooks {
    @Volatile
    private var framework: XposedInterface? = null
    private val hooked = Collections.synchronizedSet(HashSet<Method>())

    /** Plane classes whose getBuffer could not be hooked, so they are not tried on every frame. */
    private val unhookable = Collections.synchronizedSet(HashSet<Class<*>>())

    fun install(xposed: XposedInterface) {
        framework = xposed
        // Camera1 hands frames to a callback the app registers; hooking the registration finds
        // the callback class without knowing its obfuscated name.
        for (name in listOf("setPreviewCallback", "setPreviewCallbackWithBuffer", "setOneShotPreviewCallback")) {
            val register = Camera::class.java.getMethod(name, Camera.PreviewCallback::class.java)
            xposed.before(register, "camera1:$name") { chain ->
                (chain.getArg(0) as? Camera.PreviewCallback)?.let { watchCallback(xposed, it.javaClass) }
            }
        }
        // Camera2 and CameraX analysis both take their frames from an ImageReader.
        for (name in listOf("acquireLatestImage", "acquireNextImage")) {
            xposed.after(ImageReader::class.java.getMethod(name), "image-reader:$name") { _, result ->
                (result as? Image)?.let { FrameFeed.onImage(it) }
            }
        }
    }

    private fun watchCallback(xposed: XposedInterface, type: Class<*>) {
        val onFrame = type.getMethod("onPreviewFrame", ByteArray::class.java, Camera::class.java)
        if (!hooked.add(onFrame)) return
        xposed.before(onFrame, "camera1:frame:" + onFrame.declaringClass.name) { chain ->
            FrameFeed.onPreviewFrame(chain.getArg(0) as? ByteArray, chain.getArg(1) as? Camera)
        }
    }

    /**
     * The plane class is a hidden framework class, known only once a frame arrives. Returns
     * whether its getBuffer goes through [FrameFeed.substitute].
     */
    fun watchPlane(type: Class<*>): Boolean {
        val xposed = framework ?: return false
        if (type in unhookable) return false
        val getBuffer = try {
            type.getMethod("getBuffer").takeUnless { Modifier.isAbstract(it.modifiers) }
        } catch (e: NoSuchMethodException) {
            null
        }
        if (getBuffer == null) {
            unhookable += type
            HookLog.w("no getBuffer to hook on ${type.name}")
            return false
        }
        if (getBuffer in hooked) return true
        return try {
            xposed.around(getBuffer, "image-plane:buffer:" + getBuffer.declaringClass.name) { chain ->
                FrameFeed.substitute(chain.thisObject) ?: chain.proceed()
            }
            hooked += getBuffer
            true
        } catch (t: Throwable) {
            unhookable += type
            HookLog.w("getBuffer hook failed on ${type.name}", t)
            false
        }
    }
}
