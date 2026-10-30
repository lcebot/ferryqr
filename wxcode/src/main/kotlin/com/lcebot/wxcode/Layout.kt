package com.lcebot.wxcode

import kotlin.math.PI
import kotlin.math.sqrt

/*
 * The layout of a WeChat mini program code.
 *
 * Positions are in code coordinates: the origin at the code's centre, x to the right, y up, and
 * the circumradius R of the three eyes as the unit. Cells form 72 sectors of 5 degrees and 16 rings.
 * Sector s is centred at 180 - 5s degrees, so sectors run clockwise from the left. Ring r is
 * centred FIRST_RING + r modules from the centre, where a module is R / MODULES_PER_RADIUS.
 */

internal const val SECTORS = 72
internal const val RINGS = 16
internal const val MODULES_PER_RADIUS = 22.0
internal const val FIRST_RING = 10
internal const val DEGREES_PER_SECTOR = 5.0

/** Ring 6 holds the format information on its odd sectors, two copies of 18 bits each. */
internal const val FORMAT_RING = 6

internal const val MODULE = 1.0 / MODULES_PER_RADIUS

private val HALF_SQRT2 = sqrt(0.5)

/** Eyes sit on the unit circle at sectors 9, 27 and 63; sector 9 is the triangle's right angle. */
internal val EYE_SECTORS = intArrayOf(9, 27, 63)
internal val EYE_U = doubleArrayOf(-HALF_SQRT2, HALF_SQRT2, -HALF_SQRT2)
internal val EYE_V = doubleArrayOf(HALF_SQRT2, HALF_SQRT2, -HALF_SQRT2)

/** The round badge takes the fourth corner, half a module outside the eyes' circle. */
internal const val BADGE_DISTANCE = 22.5 / 22.0
internal val BADGE_U = HALF_SQRT2 * BADGE_DISTANCE
internal val BADGE_V = -HALF_SQRT2 * BADGE_DISTANCE

/** Every code carries short fixed dashes on rings 4 and 5 beside each eye and the badge. */
internal val MARKER_SECTORS = intArrayOf(9, 11, 27, 29, 45, 47, 63, 65)
internal const val MARKER_RINGS = 0b110000

internal fun sectorRadians(sector: Int): Double = (180.0 - sector * DEGREES_PER_SECTOR) * PI / 180.0

internal fun ringRadius(ring: Int): Double = (FIRST_RING + ring) * MODULE

/**
 * Proportions of the drawn code, measured on codes WeChat issued, in units of R. They are what a
 * renderer needs to draw a [WxSymbol] the way WeChat does.
 */
object WxStyle {
    /** Outer radius, hole radius and centre dot radius of an eye. */
    const val EYE_OUTER = 0.111
    const val EYE_HOLE = 0.075
    const val EYE_DOT = 0.021

    const val LOGO_RADIUS = 0.43
    const val BADGE_RADIUS = 0.18

    /** A dash's width; its round caps keep a dash within its cells. */
    const val DASH_WIDTH = 0.85 * MODULE

    /** The farthest any mark reaches from the centre: the badge's outer edge. */
    const val EXTENT = BADGE_DISTANCE + BADGE_RADIUS

    /** Centres of the three eyes and of the badge. */
    val eyes: List<Point> = List(3) { Point(EYE_U[it], EYE_V[it]) }
    val badge: Point = Point(BADGE_U, BADGE_V)
}

/** A point in image pixels or in code coordinates, depending on where it is used. */
data class Point(val x: Double, val y: Double)
