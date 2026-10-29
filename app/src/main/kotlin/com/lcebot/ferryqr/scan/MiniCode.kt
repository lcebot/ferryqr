package com.lcebot.ferryqr.scan

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How far the code's marks reach, as a share of the eyes' circumradius, with a margin around them. */
private const val EXTENT = 1.45f

/** The longest side of the crop handed to WeChat, which is plenty for its scanner. */
private const val MAX_CROP_SIDE = 640

/**
 * A WeChat mini program code that was located but cannot be decoded, since no description of the
 * format exists. [center] and [radius], the circumradius of its three eyes, are in the pixel space
 * of the upright frame or image it was found in.
 */
data class MiniCode(val center: PointF, val radius: Float) {

    /** The square holding the whole code with a margin, clipped to a [width] by [height] image. */
    fun bounds(width: Int, height: Int): Rect {
        val half = radius * EXTENT
        val r = Rect(
            (center.x - half).roundToInt(), (center.y - half).roundToInt(),
            (center.x + half).roundToInt(), (center.y + half).roundToInt(),
        )
        if (!r.intersect(0, 0, width, height)) r.setEmpty()
        return r
    }

    /** The code's own region of [frame], small enough to hand to WeChat; never [frame] itself. */
    fun crop(frame: Bitmap): Bitmap? {
        val b = bounds(frame.width, frame.height)
        if (b.isEmpty) return null
        val scale = min(1f, MAX_CROP_SIDE.toFloat() / max(b.width(), b.height()))
        val w = max(1, (b.width() * scale).roundToInt())
        val h = max(1, (b.height() * scale).roundToInt())
        val region = Bitmap.createBitmap(frame, b.left, b.top, b.width(), b.height())
        val out = if (region.width == w && region.height == h) region else Bitmap.createScaledBitmap(region, w, h, true)
        if (out !== region && region !== frame) region.recycle()
        return if (out === frame) frame.copy(Bitmap.Config.ARGB_8888, false) else out
    }
}

/**
 * The structural check that tells a mini program code from anything else the WeChat detector
 * takes for a code, such as lens flares, concentric patterns and QR codes it cannot decode.
 *
 * A mini program code has three bullseye eyes, a dark ring around a light gap around a dark dot,
 * all of one size and on one circle around the code's centre, and around its round logo short dark
 * marks that point away from that centre. The eyes give the centre and scale without depending on
 * their angles, which differ between code styles; the marks are then counted in the ring where
 * they belong. The bounds were calibrated on rendered, photographed and synthetically degraded
 * codes against flares, wheels, fireworks, targets, dartboards, clocks, sunflowers, badges and
 * QR posters, none of which produced even an eye triple.
 */
internal object MiniCodeCheck {
    /** An eye's area over its enclosing circle's: near 1 for a disc, 2/pi for a QR finder square. */
    private const val MIN_EYE_FILL = 0.72

    /** Circumradius over eye radius; every calibration code fell between 8.4 and 9.9. */
    private const val MIN_SCALE = 7.0
    private const val MAX_SCALE = 11.5

    /** Calibration codes had at least 77 marks, nearly all elongated marks pointing outwards. */
    private const val MIN_MARKS = 60
    private const val MIN_ELONGATED = 5
    private const val MIN_RADIAL = 0.85

    /** Region checks run at this size, so the thresholds below are in a fixed scale. */
    private const val REGION_SIDE = 320.0

    /** Whole images are searched at most at this size; eyes get too small for detection below it. */
    private const val SEARCH_SIDE = 1200.0

    /** Bounds the triple search, which grows with the cube of the eye count. */
    private const val MAX_EYES = 24

    private val COS_RADIAL = cos(25.0 * PI / 180)

    private class Eye(val x: Double, val y: Double, val r: Double)

    /** Connected dark marks with their centroid, area and principal axis. */
    private class Marks(val count: Int, val x: DoubleArray, val y: DoubleArray, val area: IntArray, val axisX: DoubleArray, val axisY: DoubleArray, val elongated: BooleanArray)

    /** Checks the region of a grayscale [gray] around a detector [box]; the result is in [gray]'s pixels. */
    fun inRegion(gray: Mat, box: Rect): MiniCode? {
        val pad = 0.15 * max(box.width(), box.height())
        val x0 = max(0, (box.left - pad).toInt())
        val y0 = max(0, (box.top - pad).toInt())
        val x1 = min(gray.cols(), (box.right + pad).toInt())
        val y1 = min(gray.rows(), (box.bottom + pad).toInt())
        if (x1 - x0 < 16 || y1 - y0 < 16) return null
        val roi = gray.submat(y0, y1, x0, x1)
        val scale = REGION_SIDE / max(x1 - x0, y1 - y0)
        val region = Mat()
        Imgproc.resize(
            roi, region, Size((x1 - x0) * scale, (y1 - y0) * scale), 0.0, 0.0,
            if (scale < 1) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC,
        )
        roi.release()
        try {
            val block = max(15, (min(region.cols(), region.rows()) / 10) or 1)
            val found = find(region, block) ?: return null
            return MiniCode(
                PointF((x0 + found.center.x / scale).toFloat(), (y0 + found.center.y / scale).toFloat()),
                (found.radius / scale).toFloat(),
            )
        } finally {
            region.release()
        }
    }

    /** Searches a whole grayscale image, for still images where the detector proposed nothing. */
    fun inImage(gray: Mat): MiniCode? {
        val scale = min(1.0, SEARCH_SIDE / max(gray.cols(), gray.rows()))
        val image = if (scale < 1) Mat().also {
            Imgproc.resize(gray, it, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        } else gray
        try {
            // Eyes span a few to a few dozen pixels here, so several neighbourhood sizes are tried.
            val found = listOf(15, 31, 51).firstNotNullOfOrNull { find(image, it) } ?: return null
            return MiniCode(
                PointF((found.center.x / scale).toFloat(), (found.center.y / scale).toFloat()),
                (found.radius / scale).toFloat(),
            )
        } finally {
            if (image !== gray) image.release()
        }
    }

    private class Found(val center: Point, val radius: Double, val marks: Int)

    private fun find(gray: Mat, block: Int): Found? {
        val binary = Mat()
        // Dark ink becomes foreground.
        Imgproc.adaptiveThreshold(gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, block, 8.0)
        try {
            val eyes = eyes(binary)
            if (eyes.size < 3) return null
            return ring(marks(binary), eyes)
        } finally {
            binary.release()
        }
    }

    private fun eyes(binary: Mat): List<Eye> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_NONE)
        val found = ArrayList<Eye>()
        try {
            if (hierarchy.empty()) return found
            for (i in contours.indices) {
                val child = hierarchy.get(0, i)[2].toInt()
                if (child < 0) continue
                val grandchild = hierarchy.get(0, child)[2].toInt()
                if (grandchild < 0) continue
                val outer = contours[i]
                val hole = contours[child]
                val dot = contours[grandchild]
                val ao = Imgproc.contourArea(outer)
                val ah = Imgproc.contourArea(hole)
                val ad = Imgproc.contourArea(dot)
                if (ao < 30 || ah <= 0 || ad <= 0) continue
                // Perspective squashes discs into ellipses, which the bound allows, but not squares.
                if (fill(outer) < MIN_EYE_FILL || fill(hole) < MIN_EYE_FILL - 0.05) continue
                if (ah / ao !in 0.2..0.8 || ad / ah !in 0.02..0.7) continue
                val co = centroid(outer) ?: continue
                val ch = centroid(hole) ?: continue
                val cd = centroid(dot) ?: continue
                val r = sqrt(ao / PI)
                if (dist(co, ch) > 0.2 * r || dist(co, cd) > 0.25 * r) continue
                found += Eye(co.x, co.y, r)
            }
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
        }
        // The largest eyes are the likeliest to belong to a code big enough to check.
        return found.sortedByDescending { it.r }.take(MAX_EYES)
    }

    private fun fill(contour: MatOfPoint): Double {
        val points = MatOfPoint2f(*contour.toArray())
        val center = Point()
        val radius = FloatArray(1)
        Imgproc.minEnclosingCircle(points, center, radius)
        points.release()
        val r = radius[0].toDouble()
        return if (r > 0) Imgproc.contourArea(contour) / (PI * r * r) else 0.0
    }

    private fun centroid(contour: MatOfPoint): Point? {
        val m = Imgproc.moments(contour)
        return if (m.m00 != 0.0) Point(m.m10 / m.m00, m.m01 / m.m00) else null
    }

    private fun marks(binary: Mat): Marks {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids, 8)
        val w = labels.cols()
        val h = labels.rows()
        val label = IntArray(w * h)
        labels.get(0, 0, label)
        // One pass gathers the second moments of every mark for its principal axis.
        val sx = DoubleArray(n)
        val sy = DoubleArray(n)
        val sxx = DoubleArray(n)
        val syy = DoubleArray(n)
        val sxy = DoubleArray(n)
        val area = IntArray(n)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = label[row + x]
                if (l == 0) continue
                area[l]++
                sx[l] += x.toDouble()
                sy[l] += y.toDouble()
                sxx[l] += (x * x).toDouble()
                syy[l] += (y * y).toDouble()
                sxy[l] += (x * y).toDouble()
            }
        }
        val cx = DoubleArray(n)
        val cy = DoubleArray(n)
        val ax = DoubleArray(n)
        val ay = DoubleArray(n)
        val elongated = BooleanArray(n)
        for (l in 1 until n) {
            val a = area[l].toDouble()
            if (a == 0.0) continue
            cx[l] = sx[l] / a
            cy[l] = sy[l] / a
            if (area[l] < 5) continue
            val vxx = sxx[l] / a - cx[l] * cx[l]
            val vyy = syy[l] / a - cy[l] * cy[l]
            val vxy = sxy[l] / a - cx[l] * cy[l]
            val tr = vxx + vyy
            val det = vxx * vyy - vxy * vxy
            val disc = sqrt(max(0.0, tr * tr / 4 - det))
            val major = tr / 2 + disc
            val minor = tr / 2 - disc
            if (minor <= 0 || major / minor < 3.0) continue
            elongated[l] = true
            // The eigenvector of the larger eigenvalue.
            var ex = vxy
            var ey = major - vxx
            if (abs(ex) + abs(ey) < 1e-9) {
                ex = major - vyy
                ey = vxy
            }
            val len = hypot(ex, ey)
            ax[l] = ex / len
            ay[l] = ey / len
        }
        labels.release()
        stats.release()
        centroids.release()
        return Marks(n, cx, cy, area, ax, ay, elongated)
    }

    /** The eye triple whose ring holds the most marks, if that ring passes. */
    private fun ring(marks: Marks, eyes: List<Eye>): Found? {
        var best: Found? = null
        for (a in eyes.indices) for (b in a + 1 until eyes.size) for (c in b + 1 until eyes.size) {
            val e1 = eyes[a]
            val e2 = eyes[b]
            val e3 = eyes[c]
            val rMax = max(e1.r, max(e2.r, e3.r))
            val rMin = min(e1.r, min(e2.r, e3.r))
            if (rMax > 1.6 * rMin) continue
            val circle = circumcircle(e1, e2, e3) ?: continue
            val radius = circle.second
            val eyeRadius = (e1.r + e2.r + e3.r) / 3
            if (radius / eyeRadius !in MIN_SCALE..MAX_SCALE) continue
            val centre = circle.first
            val inner = 0.5 * radius
            val outer = 1.2 * radius
            val maxArea = 0.04 * radius * radius
            var count = 0
            var elongated = 0
            var radial = 0
            for (l in 1 until marks.count) {
                val x = marks.x[l]
                val y = marks.y[l]
                val d = hypot(x - centre.x, y - centre.y)
                if (d < inner || d > outer) continue
                if (near(x, y, e1) || near(x, y, e2) || near(x, y, e3)) continue
                if (marks.area[l] < 3 || marks.area[l] > maxArea) continue
                count++
                if (!marks.elongated[l]) continue
                elongated++
                val dot = (marks.axisX[l] * (x - centre.x) + marks.axisY[l] * (y - centre.y)) / max(d, 1e-6)
                if (abs(dot) > COS_RADIAL) radial++
            }
            if (count < MIN_MARKS || elongated < MIN_ELONGATED || radial < MIN_RADIAL * elongated) continue
            if (best == null || count > best.marks) best = Found(centre, radius, count)
        }
        return best
    }

    private fun near(x: Double, y: Double, eye: Eye) = hypot(x - eye.x, y - eye.y) < 2.0 * eye.r

    private fun circumcircle(a: Eye, b: Eye, c: Eye): Pair<Point, Double>? {
        val d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
        if (abs(d) < 1e-6) return null
        val a2 = a.x * a.x + a.y * a.y
        val b2 = b.x * b.x + b.y * b.y
        val c2 = c.x * c.x + c.y * c.y
        val ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d
        val uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d
        return Point(ux, uy) to hypot(ux - a.x, uy - a.y)
    }

    private fun dist(p: Point, q: Point) = hypot(p.x - q.x, p.y - q.y)
}
