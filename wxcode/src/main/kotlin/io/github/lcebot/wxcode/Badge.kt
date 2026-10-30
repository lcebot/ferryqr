package io.github.lcebot.wxcode

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The search window's half side, in R. */
private const val REACH = 0.45

/** The largest distance, in R, from the predicted position at which a blob counts as the badge. */
private const val MAX_OFFSET = 0.3

/** The blob's area relative to a full badge disc; the glyph on the badge takes some of it. */
private const val MIN_AREA = 0.35
private const val MAX_AREA = 1.6

private const val MIN_CONTRAST = 40

/**
 * Locates the round badge every WeChat code carries where a fourth eye would sit. With the three
 * eyes it gives four points, which fix the code's perspective exactly.
 */
internal object Badge {

    /**
     * The badge centre in [image], looked for near where [affine], fitted to the eyes, puts it.
     * The badge is a filled disc with clear space around it, so it is the one large blob there;
     * its centroid is the disc's centre since the glyph on it is point-symmetric.
     */
    fun locate(image: LumaImage, affine: Homography): Point? {
        val predicted = affine.map(Point(BADGE_U, BADGE_V))
        val radius = affine.circumradius
        val reach = REACH * radius
        val x0 = max(0, (predicted.x - reach).toInt())
        val y0 = max(0, (predicted.y - reach).toInt())
        val x1 = min(image.width, (predicted.x + reach).toInt() + 1)
        val y1 = min(image.height, (predicted.y + reach).toInt() + 1)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 8 || h < 8) return null

        val histogram = IntArray(256)
        for (y in y0 until y1) for (x in x0 until x1) histogram[image[x, y]]++
        val light = percentile(histogram, w * h, 0.90)
        val dark = percentile(histogram, w * h, 0.02)
        if (light - dark < MIN_CONTRAST) return null
        val threshold = light - (light - dark) / 4

        val foreground = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) foreground[y * w + x] = image[x0 + x, y0 + y] < threshold

        val expected = PI * WxStyle.BADGE_RADIUS * WxStyle.BADGE_RADIUS * radius * radius
        var best: Point? = null
        var bestDistance = MAX_OFFSET * radius
        val stack = IntArray(w * h)
        for (start in foreground.indices) {
            if (!foreground[start]) continue
            // Flood fill with 8-connectivity, clearing pixels as they are taken.
            foreground[start] = false
            var top = 0
            stack[top++] = start
            var area = 0
            var sumX = 0L
            var sumY = 0L
            while (top > 0) {
                val p = stack[--top]
                val px = p % w
                val py = p / w
                area++
                sumX += px
                sumY += py
                for (ny in max(0, py - 1)..min(h - 1, py + 1)) {
                    for (nx in max(0, px - 1)..min(w - 1, px + 1)) {
                        val q = ny * w + nx
                        if (foreground[q]) {
                            foreground[q] = false
                            stack[top++] = q
                        }
                    }
                }
            }
            if (area < MIN_AREA * expected || area > MAX_AREA * expected) continue
            val centre = Point(x0 + sumX.toDouble() / area, y0 + sumY.toDouble() / area)
            val distance = hypot(centre.x - predicted.x, centre.y - predicted.y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = centre
            }
        }
        return best
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
        val target = fraction * total
        var seen = 0
        for (v in histogram.indices) {
            seen += histogram[v]
            if (seen >= target) return v
        }
        return 255
    }
}
