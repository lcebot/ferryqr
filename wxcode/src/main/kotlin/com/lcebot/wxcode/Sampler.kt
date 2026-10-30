package com.lcebot.wxcode

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Angular sample offsets within a cell, a quarter sector each way, and radial ones in modules. */
private val ANGULAR_OFFSETS = doubleArrayOf(-1.25, 0.0, 1.25)
private val RADIAL_OFFSETS = doubleArrayOf(-0.3, 0.0, 0.3)
private const val SAMPLES_PER_CELL = 9

/** Brightness given to a cell that lies entirely outside the image. */
private const val OUTSIDE = 128f

/**
 * Reads cell brightness through a [Homography].
 *
 * A dark cell is drawn as a radial dash narrower than its sector, so each cell reads as the darkest
 * of three angular samples, each the mean of three radial ones. Small angular misalignment then
 * still finds the dash, while the neighbouring sectors' dashes stay out of reach.
 */
internal class Sampler(private val image: LumaImage) {

    /**
     * Writes the brightness of [count] rings from [first] for every sector into
     * `out[sector * count + ring - first]`.
     */
    fun sample(h: Homography, first: Int, count: Int, out: FloatArray) {
        val m = h.m
        val m0 = m[0]; val m1 = m[1]; val m2 = m[2]
        val m3 = m[3]; val m4 = m[4]; val m5 = m[5]
        val m6 = m[6]; val m7 = m[7]; val m8 = m[8]
        for (s in 0 until SECTORS) {
            for (i in 0 until count) {
                var point = ((s * RINGS) + first + i) * SAMPLES_PER_CELL
                var darkest = Float.POSITIVE_INFINITY
                repeat(ANGULAR_OFFSETS.size) {
                    var sum = 0f
                    var n = 0
                    repeat(RADIAL_OFFSETS.size) {
                        val u = U[point]
                        val v = V[point]
                        point++
                        val w = m6 * u + m7 * v + m8
                        val value = image.sample((m0 * u + m1 * v + m2) / w, (m3 * u + m4 * v + m5) / w)
                        if (!value.isNaN()) {
                            sum += value
                            n++
                        }
                    }
                    if (n > 0) darkest = min(darkest, sum / n)
                }
                out[s * count + i] = if (darkest.isFinite()) darkest else OUTSIDE
            }
        }
    }

    private companion object {
        /** Code coordinates of every sample point, cell by cell in sector-major order. */
        val U = DoubleArray(SECTORS * RINGS * SAMPLES_PER_CELL)
        val V = DoubleArray(SECTORS * RINGS * SAMPLES_PER_CELL)

        init {
            var point = 0
            for (s in 0 until SECTORS) for (r in 0 until RINGS) {
                for (da in ANGULAR_OFFSETS) {
                    val angle = sectorRadians(s) + Math.toRadians(da)
                    for (dr in RADIAL_OFFSETS) {
                        val radius = ringRadius(r) + dr * MODULE
                        U[point] = radius * cos(angle)
                        V[point] = radius * sin(angle)
                        point++
                    }
                }
            }
        }
    }
}

internal enum class Threshold {
    /** Otsu's split of each ring's brightness, which suits rings with both dark and light cells. */
    OTSU,

    /** Each ring's mean, which tolerates a ring that is nearly all one colour. */
    MEAN,
}

/**
 * Sets the ring masks in [cells] for [count] rings from [first], from brightness laid out as
 * [Sampler.sample] writes it. Each ring gets its own threshold, which absorbs uneven lighting
 * across the code. Darker than the threshold is dark.
 */
internal fun binarize(
    values: FloatArray,
    first: Int,
    count: Int,
    method: Threshold,
    cells: IntArray,
    scratch: FloatArray = FloatArray(SECTORS),
) {
    val clear = (((1 shl count) - 1) shl first).inv()
    for (s in 0 until SECTORS) cells[s] = cells[s] and clear
    for (i in 0 until count) {
        for (s in 0 until SECTORS) scratch[s] = values[s * count + i]
        val t = if (method == Threshold.OTSU) otsu(scratch) else scratch.average().toFloat()
        val bit = 1 shl (first + i)
        for (s in 0 until SECTORS) if (values[s * count + i] < t) cells[s] = cells[s] or bit
    }
}

/** The threshold that best separates [values] into two classes, between two sorted neighbours. */
private fun otsu(values: FloatArray): Float {
    values.sort()
    val n = values.size
    val total = values.sum().toDouble()
    var below = 0.0
    var bestVariance = -1.0
    var best = values[0]
    for (i in 0 until n - 1) {
        below += values[i]
        val nb = (i + 1).toDouble()
        val nf = (n - i - 1).toDouble()
        val gap = below / nb - (total - below) / nf
        val variance = nb * nf * gap * gap
        if (variance > bestVariance) {
            bestVariance = variance
            best = (values[i] + values[i + 1]) / 2
        }
    }
    return best
}
