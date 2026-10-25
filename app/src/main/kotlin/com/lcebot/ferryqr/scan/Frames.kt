package com.lcebot.ferryqr.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_STILL_SIDE = 3000

/**
 * Crops and rotates the frame the same way zxing-cpp does internally, so its positions map onto
 * the returned bitmap without further transformation.
 */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val raw = toBitmap()
    val crop = cropRect
    val cropped = if (crop.left == 0 && crop.top == 0 && crop.width() == raw.width && crop.height() == raw.height) raw
    else Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height())
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return cropped
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
}

fun decodeStill(context: Context, uri: Uri): Bitmap? = try {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > MAX_STILL_SIDE) {
            val scale = MAX_STILL_SIDE.toFloat() / longest
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }
    if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
} catch (e: Exception) {
    null
}
