package io.github.lcebot.ferryqr.generate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.lcebot.wxcode.EcLevel
import io.github.lcebot.wxcode.Point
import io.github.lcebot.wxcode.WxEncoder
import io.github.lcebot.wxcode.WxStyle
import io.github.lcebot.wxcode.WxSymbol

/** Blank space around the code, in units of the eyes' circumradius. */
private const val MARGIN = 0.15

private const val SIZE = 720

private val LOGO = Color.rgb(0xE6, 0xE6, 0xE6)
private val BADGE = Color.rgb(0x07, 0xC1, 0x60)

/**
 * Draws a mini program code from its content the way WeChat draws its codes, so that WeChat's
 * scanner reads the drawing as it reads the original.
 */
object MiniCodeArt {

    /** A [size] pixel square drawing of the code holding [text], or null when no code can. */
    fun draw(text: String, size: Int = SIZE): Bitmap? {
        // Codes WeChat issues use level M; a code read at level L may hold more than M allows.
        val symbol = listOf(EcLevel.M, EcLevel.L).firstNotNullOfOrNull {
            runCatching { WxEncoder.encode(text, it) }.getOrNull()
        } ?: return null
        return draw(symbol, size)
    }

    private fun draw(symbol: WxSymbol, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val scale = size / 2f / (WxStyle.EXTENT + MARGIN).toFloat()
        val centre = size / 2f
        fun x(p: Point) = centre + p.x.toFloat() * scale
        fun y(p: Point) = centre - p.y.toFloat() * scale
        fun disc(p: Point, radius: Double, colour: Int, paint: Paint) {
            paint.color = colour
            canvas.drawCircle(x(p), y(p), radius.toFloat() * scale, paint)
        }

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = WxStyle.DASH_WIDTH.toFloat() * scale
        }
        for (dash in symbol.dashes) canvas.drawLine(x(dash.from), y(dash.from), x(dash.to), y(dash.to), stroke)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        for (eye in WxStyle.eyes) {
            disc(eye, WxStyle.EYE_OUTER, Color.BLACK, fill)
            disc(eye, WxStyle.EYE_HOLE, Color.WHITE, fill)
            disc(eye, WxStyle.EYE_DOT, Color.BLACK, fill)
        }
        disc(Point(0.0, 0.0), WxStyle.LOGO_RADIUS, LOGO, fill)
        disc(WxStyle.badge, WxStyle.BADGE_RADIUS, BADGE, fill)
        return bitmap
    }
}
