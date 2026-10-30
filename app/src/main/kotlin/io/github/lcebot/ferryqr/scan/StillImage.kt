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

/**
 * A still image is read only once, so every reader and the most thorough look run on it. Code-like
 * regions the detector proposes but neither reader decoded on the whole image get a deep read of
 * their own. When codes were read, mini program codes only located join them as located entries,
 * so the chooser offers every code; with none read, they come back alone in [StillResult.Read.miniCodes].
 */
suspend fun readStillImage(context: Context, uri: Uri, zxing: ZxingEngine, wechat: WeChatEngine): StillResult {
    val frame = withContext(Dispatchers.IO) { decodeStill(context, uri) } ?: return StillResult.Unreadable
    return withContext(Dispatchers.Default) {
        val found = zxing.read(frame).mergedWith(wechat.decode(frame))
        val survey = wechat.survey(frame, Look.THOROUGH)
        val known = found + survey.read
        val deep = survey.regions
            .filter { region -> known.none { region.contains(it.bounds.centerX().toInt(), it.bounds.centerY().toInt()) } }
            .flatMap { region ->
                val padded = region.paddedWithin(frame)
                zxing.readRegion(frame, padded).ifEmpty { wechat.decode(frame, padded) }
            }
        val detections = found.mergedWith(survey.read).mergedWith(deep)
        if (detections.isEmpty()) {
            StillResult.Read(frame, detections, survey.located)
        } else {
            StillResult.Read(frame, detections + survey.located.map { it.asLocatedDetection(frame.width, frame.height) })
        }
    }
}
