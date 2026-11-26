package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rendered size of an app glyph; buttons draw it at 18 to 24dp, so this stays sharp at any density. */
private const val GLYPH_PX = 96

/** An adaptive icon layer spans 108 units, of which the central 72 are visible. */
private const val LAYER_UNITS = 108f
private const val VISIBLE_UNITS = 72f

private class Glyph(val image: ImageBitmap, val themed: Boolean)

/**
 * The icon of the installed app [packageName], so an action that hands off to it shows that app.
 * Its themed layer, the one Android tints for themed launcher icons, follows the content colour
 * like any other icon; without one, the full-colour icon is shown as it is. [fallback] covers an
 * app that is not visible or not installed.
 */
@Composable
fun AppIcon(packageName: String, @DrawableRes fallback: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Asking the package manager and drawing the icon happen off the main thread, once per app for
    // the whole process; an icon not drawn yet takes its place empty and fades in.
    val icon by produceState(glyphs.get(packageName)?.let { IconLoad.Found(it) } ?: IconLoad.Loading, packageName) {
        // The same place may come to show another app's icon, which then starts from that app's own.
        glyphs.get(packageName)?.let {
            value = IconLoad.Found(it)
            return@produceState
        }
        value = IconLoad.Loading
        value = withContext(Dispatchers.IO) {
            glyph(context, packageName)?.also { glyphs.put(packageName, it) }?.let { IconLoad.Found(it) } ?: IconLoad.Missing
        }
    }
    Crossfade(icon, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "app-icon") { shown ->
        when (shown) {
            IconLoad.Loading -> Box(modifier)
            IconLoad.Missing -> Icon(painterResource(fallback), contentDescription = null, modifier = modifier)
            is IconLoad.Found ->
                if (shown.glyph.themed) Icon(shown.glyph.image, contentDescription = null, modifier = modifier)
                else Image(shown.glyph.image, contentDescription = null, modifier = modifier)
        }
    }
}

/** Glyphs drawn so far, by package; an app that is missing is asked again, as it may be installed meanwhile. */
private val glyphs = LruCache<String, Glyph>(GLYPH_CACHE)

/** Enough for every app the result cards, the create page and the ferry page offer. */
private const val GLYPH_CACHE = 32

private sealed interface IconLoad {
    data object Loading : IconLoad
    data object Missing : IconLoad
    class Found(val glyph: Glyph) : IconLoad
}

private fun glyph(context: Context, packageName: String): Glyph? {
    val icon = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        return null
    }
    if (icon is AdaptiveIconDrawable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            icon.monochrome?.let { return Glyph(layer(it), themed = true) }
        }
    }
    return Glyph(render(icon), themed = false)
}

/** Draws only the visible centre of an adaptive icon layer, which carries 18 units of bleed per side. */
private fun layer(drawable: Drawable): ImageBitmap {
    val full = (GLYPH_PX * LAYER_UNITS / VISIBLE_UNITS).toInt()
    val inset = (full - GLYPH_PX) / 2
    val bitmap = Bitmap.createBitmap(GLYPH_PX, GLYPH_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(-inset, -inset, full - inset, full - inset)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}

private fun render(drawable: Drawable): ImageBitmap {
    val bitmap = Bitmap.createBitmap(GLYPH_PX, GLYPH_PX, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, GLYPH_PX, GLYPH_PX)
    drawable.draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
