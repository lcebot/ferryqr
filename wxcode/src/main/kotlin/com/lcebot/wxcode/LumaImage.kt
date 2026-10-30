package com.lcebot.wxcode

/**
 * An 8-bit grayscale image, such as a camera frame's Y plane. Pixel (x, y) is the unsigned byte at
 * `offset + y * rowStride + x` of [pixels].
 */
class LumaImage(
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int = width,
    val offset: Int = 0,
) {
    init {
        require(width > 0 && height > 0 && rowStride >= width)
        require(offset >= 0 && offset + (height - 1) * rowStride + width <= pixels.size)
    }

    operator fun get(x: Int, y: Int): Int = pixels[offset + y * rowStride + x].toInt() and 0xFF

    /** Bilinear interpolation, or NaN where a neighbour falls outside the image. */
    internal fun sample(x: Double, y: Double): Float {
        if (!(x >= 0 && y >= 0 && x < width - 1 && y < height - 1)) return Float.NaN
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        val i = offset + y0 * rowStride + x0
        val p00 = (pixels[i].toInt() and 0xFF).toFloat()
        val p01 = (pixels[i + 1].toInt() and 0xFF).toFloat()
        val p10 = (pixels[i + rowStride].toInt() and 0xFF).toFloat()
        val p11 = (pixels[i + rowStride + 1].toInt() and 0xFF).toFloat()
        val top = p00 + (p01 - p00) * fx
        val bottom = p10 + (p11 - p10) * fx
        return top + (bottom - top) * fy
    }
}
