package io.github.lcebot.ferryqr.scan

import android.content.Context

/**
 * The decoders, one of each for the whole process, shared by the scanner and the image sheet, so
 * the WeChat detector, a model of several megabytes, is loaded only once.
 */
object Engines {
    val zxing: ZxingEngine by lazy { ZxingEngine() }

    @Volatile
    private var wechatEngine: WeChatEngine? = null

    fun wechat(context: Context): WeChatEngine = wechatEngine ?: synchronized(this) {
        wechatEngine ?: WeChatEngine(context.applicationContext).also { wechatEngine = it }
    }
}
