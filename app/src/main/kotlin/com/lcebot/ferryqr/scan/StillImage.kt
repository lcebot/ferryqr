package com.lcebot.ferryqr.scan

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface StillResult {
    data object Unreadable : StillResult

    /** [detections] may be empty when the image holds no code. */
    data class Read(val frame: Bitmap, val detections: List<Detection>) : StillResult
}

/** A still image is decoded only once, so both engines always run on it. */
suspend fun readStillImage(context: Context, uri: Uri, zxing: ZxingEngine, wechat: WeChatEngine): StillResult {
    val frame = withContext(Dispatchers.IO) { decodeStill(context, uri) } ?: return StillResult.Unreadable
    val found = withContext(Dispatchers.Default) { zxing.read(frame).mergedWith(wechat.decode(frame)) }
    return StillResult.Read(frame, found)
}
