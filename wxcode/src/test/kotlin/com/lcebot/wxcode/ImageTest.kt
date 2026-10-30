package com.lcebot.wxcode

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageTest {

    private val text = "EUH YM11\$1F 8/4*BRXG8Q.7D3MH-T/YW6.79ZJ"
    private val symbol = WxEncoder.encode(text)

    @Test
    fun frontal() = assertReads(view(radius = 150.0, degrees = 0.0))

    @Test
    fun rotated() = assertReads(view(radius = 120.0, degrees = 37.0))

    @Test
    fun smallAndBlurred() = assertReads(view(radius = 60.0, degrees = -70.0), blur = true)

    @Test
    fun tilted() = assertReads(view(radius = 140.0, degrees = 15.0, tilt = 0.12))

    @Test
    fun tiltedWithoutBadge() = assertReads(view(radius = 140.0, degrees = 100.0, tilt = 0.08), badge = false)

    @Test
    fun dimAndLowContrast() = assertReads(view(radius = 130.0, degrees = -20.0), light = 110, dark = 40)

    @Test
    fun noCodeWhereThereIsNone() {
        val image = LumaImage(ByteArray(400 * 400) { 200.toByte() }, 400, 400)
        val eyes = listOf(Point(100.0, 100.0), Point(300.0, 100.0), Point(100.0, 300.0))
        assertNull(WxDecoder.decode(image, eyes))
    }

    @Test
    fun eyeOrderDoesNotMatter() {
        val h = view(radius = 150.0, degrees = 200.0)
        val image = render(h)
        val eyes = WxStyle.eyes.map(h::map)
        for (order in listOf(listOf(0, 1, 2), listOf(2, 0, 1), listOf(1, 2, 0), listOf(2, 1, 0))) {
            assertEquals(text, WxDecoder.decode(image, order.map(eyes::get))?.text)
        }
    }

    private fun assertReads(h: Homography, badge: Boolean = true, blur: Boolean = false, light: Int = 245, dark: Int = 20) {
        val image = render(h, badge, blur, light, dark)
        val random = Random(4)
        // Eye centres as a detector would report them, off by up to a pixel.
        val eyes = WxStyle.eyes.map(h::map).map { Point(it.x + random.nextDouble(-1.0, 1.0), it.y + random.nextDouble(-1.0, 1.0)) }
        val code = assertNotNull(WxDecoder.decode(image, eyes))
        assertEquals(text, code.text)
        val centre = h.center
        assertTrue(hypot(code.homography.center.x - centre.x, code.homography.center.y - centre.y) < 0.05 * h.circumradius)
    }

    /**
     * The code with circumradius [radius] at the centre of a 640 x 480 frame, turned clockwise by
     * [degrees] and seen in perspective, shrinking towards its top by [tilt] per circumradius.
     */
    private fun view(radius: Double, degrees: Double, tilt: Double = 0.0): Homography {
        val a = degrees * PI / 180
        val c = cos(a) * radius
        val s = sin(a) * radius
        return Homography(doubleArrayOf(c, s, 320.0, s, -c, 240.0, 0.0, tilt, 1.0))
    }

    private fun render(h: Homography, badge: Boolean = true, blur: Boolean = false, light: Int = 245, dark: Int = 20): LumaImage {
        val w = 640
        val height = 480
        val inverse = invert(h.m)
        val bySector = Array(SECTORS) { ArrayList<Dash>() }
        for (d in symbol.dashes) {
            val sector = Math.floorMod(((180.0 - Math.toDegrees(atan2(d.to.y, d.to.x))) / DEGREES_PER_SECTOR).roundToInt(), SECTORS)
            bySector[sector] += d
        }
        val half = WxStyle.DASH_WIDTH / 2
        val levels = FloatArray(w * height)
        for (y in 0 until height) for (x in 0 until w) {
            val px = x + 0.5
            val py = y + 0.5
            val wq = inverse[6] * px + inverse[7] * py + inverse[8]
            val u = (inverse[0] * px + inverse[1] * py + inverse[2]) / wq
            val v = (inverse[3] * px + inverse[4] * py + inverse[5]) / wq
            levels[y * w + x] = if (isDark(u, v, bySector, half, badge)) 0f else 1f
        }
        val smoothed = if (blur) boxBlur(levels, w, height) else levels
        val pixels = ByteArray(w * height) { (dark + (light - dark) * smoothed[it]).roundToInt().toByte() }
        return LumaImage(pixels, w, height)
    }

    private fun isDark(u: Double, v: Double, bySector: Array<ArrayList<Dash>>, half: Double, badge: Boolean): Boolean {
        val r = hypot(u, v)
        if (r <= WxStyle.LOGO_RADIUS) return true
        for (eye in WxStyle.eyes) {
            val d = hypot(u - eye.x, v - eye.y)
            if (d <= WxStyle.EYE_DOT || (d > WxStyle.EYE_HOLE && d <= WxStyle.EYE_OUTER)) return true
        }
        if (badge && hypot(u - WxStyle.badge.x, v - WxStyle.badge.y) <= WxStyle.BADGE_RADIUS) return true
        val sector = Math.floorMod(((180.0 - Math.toDegrees(atan2(v, u))) / DEGREES_PER_SECTOR).roundToInt(), SECTORS)
        for (ds in -1..1) for (dash in bySector[Math.floorMod(sector + ds, SECTORS)]) {
            if (segmentDistance(u, v, dash) <= half) return true
        }
        return false
    }

    private fun segmentDistance(u: Double, v: Double, d: Dash): Double {
        val dx = d.to.x - d.from.x
        val dy = d.to.y - d.from.y
        val length2 = dx * dx + dy * dy
        val t = if (length2 == 0.0) 0.0 else (((u - d.from.x) * dx + (v - d.from.y) * dy) / length2).coerceIn(0.0, 1.0)
        return hypot(u - d.from.x - t * dx, v - d.from.y - t * dy)
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) {
                    sum += src[yy * w + xx]
                    n++
                }
            }
            out[y * w + x] = sum / n
        }
        return out
    }

    private fun invert(m: DoubleArray): DoubleArray {
        val c00 = m[4] * m[8] - m[5] * m[7]
        val c01 = m[5] * m[6] - m[3] * m[8]
        val c02 = m[3] * m[7] - m[4] * m[6]
        val det = m[0] * c00 + m[1] * c01 + m[2] * c02
        return doubleArrayOf(
            c00 / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
            c01 / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
            c02 / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
        )
    }
}
