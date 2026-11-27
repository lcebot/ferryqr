package io.github.lcebot.ferryqr.dispatch

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import java.util.concurrent.atomic.AtomicInteger

/** Rendered size of an app glyph; buttons draw it at 18 to 24dp, so this stays sharp at any density. */
private const val GLYPH_PX = 96

/** An adaptive icon layer spans 108 units, of which the central 72 are visible. */
private const val LAYER_UNITS = 108f
private const val VISIBLE_UNITS = 72f

/** Enough for every app the result cards, the create page and the ferry page offer. */
private const val GLYPH_CACHE = 32

/**
 * An app's icon as a button shows it. A [themed] one is the layer Android tints for themed launcher
 * icons, which follows the content colour like any other icon; any other is the full-colour icon.
 */
internal class AppGlyph(val image: Bitmap, val themed: Boolean)

/**
 * What FerryQR keeps for the whole process about the apps installed: the browsers, which every link
 * card asks for, and the icons drawn of apps. It only has to be current when a page refreshes
 * itself, which calls [invalidate], since apps may have been installed, removed or updated, or a
 * default browser chosen, while FerryQR was away.
 */
internal object InstalledApps {

    @Volatile
    private var browsers: Set<String>? = null

    /** The browsers as last asked. */
    fun browsers(context: Context): Set<String> = browsers ?: Launch.findBrowsers(context).also { browsers = it }

    private class Drawn(val glyph: AppGlyph, val generation: Int)

    /** Glyphs drawn so far, by package; an app that is missing is asked again, as it may be installed meanwhile. */
    private val glyphs = LruCache<String, Drawn>(GLYPH_CACHE)

    /** Counts the calls to [invalidate], which tells a glyph drawn since the last one from an older one. */
    private val generation = AtomicInteger()

    /** The icon of [pkg] drawn since the last [invalidate], if any, which can be shown at once. */
    fun currentGlyph(pkg: String): AppGlyph? = glyphs.get(pkg)?.takeIf { it.generation == generation.get() }?.glyph

    /** The icon of [pkg] drawn last, which shows while [glyph] makes sure it is still the app's. */
    fun lastGlyph(pkg: String): AppGlyph? = glyphs.get(pkg)?.glyph

    /**
     * The icon of the installed app [pkg], or null for an app that is not visible or not installed.
     * One drawn before the last [invalidate] is drawn again, and when it looks the same it is given
     * as the same glyph, so whatever shows it has nothing to change. Asking the package manager and
     * drawing the icon happen here, so this runs off the main thread.
     */
    fun glyph(context: Context, pkg: String): AppGlyph? {
        val now = generation.get()
        val last = glyphs.get(pkg)
        if (last != null && last.generation == now) return last.glyph
        val drawn = draw(context, pkg)
        if (drawn == null) {
            glyphs.remove(pkg)
            return null
        }
        val glyph = last?.glyph?.takeIf { it.themed == drawn.themed && it.image.sameAs(drawn.image) } ?: drawn
        glyphs.put(pkg, Drawn(glyph, now))
        return glyph
    }

    /** Lets go of what was learned about the installed apps, for a page that refreshes itself. */
    fun invalidate() {
        browsers = null
        // The glyphs stay to be shown until each is drawn again, so a page refreshing itself never
        // empties the icons it shows.
        generation.incrementAndGet()
    }

    private fun draw(context: Context, pkg: String): AppGlyph? {
        val icon = try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        if (icon is AdaptiveIconDrawable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                icon.monochrome?.let { return AppGlyph(layer(it), themed = true) }
            }
        }
        return AppGlyph(render(icon), themed = false)
    }

    /** Draws only the visible centre of an adaptive icon layer, which carries 18 units of bleed per side. */
    private fun layer(drawable: Drawable): Bitmap {
        val full = (GLYPH_PX * LAYER_UNITS / VISIBLE_UNITS).toInt()
        val inset = (full - GLYPH_PX) / 2
        val bitmap = Bitmap.createBitmap(GLYPH_PX, GLYPH_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(-inset, -inset, full - inset, full - inset)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun render(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(GLYPH_PX, GLYPH_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, GLYPH_PX, GLYPH_PX)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
