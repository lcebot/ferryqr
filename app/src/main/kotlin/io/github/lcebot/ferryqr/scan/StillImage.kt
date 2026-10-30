package io.github.lcebot.ferryqr.scan

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface StillResult {
    data object Unreadable : StillResult

    /**
     * [detections] may be empty when the image holds no code; mini program codes that were read
     * are among them. [miniCodes] are those located but not read, kept only when nothing was read,
     * since a read code always takes precedence over a guess.
     */
    data class Read(
        val frame: Bitmap,
        val detections: List<Detection>,
        val miniCodes: List<MiniCode> = emptyList(),
    ) : StillResult
}

/** A still image is decoded only once, so both engines always run on it. */
suspend fun readStillImage(context: Context, uri: Uri, zxing: ZxingEngine, wechat: WeChatEngine): StillResult {
    val frame = withContext(Dispatchers.IO) { decodeStill(context, uri) } ?: return StillResult.Unreadable
    return withContext(Dispatchers.Default) {
        val found = zxing.read(frame).mergedWith(wechat.decode(frame))
        val mini = wechat.readMiniCodes(frame, search = found.isEmpty())
        val detections = found.mergedWith(mini.read)
        StillResult.Read(frame, detections, if (detections.isEmpty()) mini.located else emptyList())
    }
}
