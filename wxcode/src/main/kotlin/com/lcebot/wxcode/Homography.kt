package com.lcebot.wxcode

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A projective map from code coordinates to image pixels, as a row-major 3x3 matrix. A code
 * photographed off-axis is a planar perspective view, which this models exactly.
 */
class Homography internal constructor(internal val m: DoubleArray) {

    fun map(p: Point): Point {
        val w = m[6] * p.x + m[7] * p.y + m[8]
        return Point((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
    }

    /** Where the code's centre is in the image. */
    val center: Point get() = Point(m[2] / m[8], m[5] / m[8])

    /** The eyes' circumradius in pixels, as the map's mean scale at the centre. */
    val circumradius: Double get() = sqrt(abs(m[0] * m[4] - m[1] * m[3])) / abs(m[8])

    /** The code's clockwise rotation in degrees, 0 to 360, at its centre. */
    val orientation: Double
        get() = (Math.toDegrees(atan2(m[3], m[0])) + 360.0) % 360.0

    /** This map with the code moved by ([dx], [dy]) pixels and scaled by [scale] about its centre. */
    internal fun adjusted(dx: Double, dy: Double, scale: Double): Homography {
        val cx = m[2] / m[8]
        val cy = m[5] / m[8]
        val tx = cx * (1 - scale) + dx
        val ty = cy * (1 - scale) + dy
        return Homography(
            doubleArrayOf(
                scale * m[0] + tx * m[6], scale * m[1] + tx * m[7], scale * m[2] + tx * m[8],
                scale * m[3] + ty * m[6], scale * m[4] + ty * m[7], scale * m[5] + ty * m[8],
                m[6], m[7], m[8],
            )
        )
    }

    internal companion object {
        /** Inverse of the rows (u, v, 1) of the eyes, which fits a map's top rows through them. */
        private val EYE_FIT: DoubleArray = invert3(
            doubleArrayOf(
                EYE_U[0], EYE_V[0], 1.0,
                EYE_U[1], EYE_V[1], 1.0,
                EYE_U[2], EYE_V[2], 1.0,
            )
        )

        /**
         * The map with bottom row (p, q, 1) that takes the eyes at sectors 9, 27 and 63 onto
         * [eyes], given in that order. Three points fix the other six entries; p = q = 0 is the
         * affine fit, exact for a frontal or distant code.
         */
        fun throughEyes(eyes: Array<Point>, p: Double, q: Double): Homography {
            val m = DoubleArray(9)
            for (i in 0 until 3) {
                val w = 1 + p * EYE_U[i] + q * EYE_V[i]
                val x = eyes[i].x * w
                val y = eyes[i].y * w
                for (j in 0 until 3) {
                    m[j] += EYE_FIT[j * 3 + i] * x
                    m[3 + j] += EYE_FIT[j * 3 + i] * y
                }
            }
            m[6] = p
            m[7] = q
            m[8] = 1.0
            return Homography(m)
        }

        /** The map taking four code points [from] onto image points [to], or null if degenerate. */
        fun through(from: List<Point>, to: List<Point>): Homography? {
            // Unknowns h0..h7 with h8 = 1; each pair gives two linear equations.
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (u, v) = from[i]
                val (x, y) = to[i]
                a[2 * i] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x, x)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y, y)
            }
            val h = solve(a) ?: return null
            return Homography(DoubleArray(9) { if (it < 8) h[it] else 1.0 })
        }

        /** Gaussian elimination with partial pivoting on an augmented n x (n + 1) system. */
        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = a.size
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(a[it][col]) }
                if (abs(a[pivot][col]) < 1e-12) return null
                val t = a[pivot]; a[pivot] = a[col]; a[col] = t
                for (row in col + 1 until n) {
                    val f = a[row][col] / a[col][col]
                    if (f != 0.0) for (k in col..n) a[row][k] -= f * a[col][k]
                }
            }
            val x = DoubleArray(n)
            for (row in n - 1 downTo 0) {
                var s = a[row][n]
                for (k in row + 1 until n) s -= a[row][k] * x[k]
                x[row] = s / a[row][row]
            }
            return x
        }

        private fun invert3(a: DoubleArray): DoubleArray {
            val c00 = a[4] * a[8] - a[5] * a[7]
            val c01 = a[5] * a[6] - a[3] * a[8]
            val c02 = a[3] * a[7] - a[4] * a[6]
            val det = a[0] * c00 + a[1] * c01 + a[2] * c02
            return doubleArrayOf(
                c00 / det, (a[2] * a[7] - a[1] * a[8]) / det, (a[1] * a[5] - a[2] * a[4]) / det,
                c01 / det, (a[0] * a[8] - a[2] * a[6]) / det, (a[2] * a[3] - a[0] * a[5]) / det,
                c02 / det, (a[1] * a[6] - a[0] * a[7]) / det, (a[0] * a[4] - a[1] * a[3]) / det,
            )
        }
    }
}
