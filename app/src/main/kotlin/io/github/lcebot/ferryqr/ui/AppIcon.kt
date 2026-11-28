package io.github.lcebot.ferryqr.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import io.github.lcebot.ferryqr.dispatch.AppGlyph
import io.github.lcebot.ferryqr.dispatch.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The icon of the installed app [packageName], so an action that hands off to it shows that app.
 * Its themed layer, the one Android tints for themed launcher icons, follows the content colour
 * like any other icon; without one, the full-colour icon is shown as it is, with [fullColor] added
 * to it. [fallback] covers an app that is not visible or not installed.
 */
@Composable
fun AppIcon(packageName: String, @DrawableRes fallback: Int, modifier: Modifier = Modifier, fullColor: Modifier = Modifier) {
    val context = LocalContext.current
    // Asking the package manager and drawing the icon happen off the main thread, once per app until
    // a page refreshes itself; an icon never drawn takes its place empty and fades in, and one drawn
    // before the refresh shows until it is drawn again.
    val icon by produceState(InstalledApps.lastGlyph(packageName)?.let { IconLoad.Found(it) } ?: IconLoad.Loading, packageName) {
        InstalledApps.currentGlyph(packageName)?.let {
            value = IconLoad.Found(it)
            return@produceState
        }
        // The same place may come to show another app's icon, which then starts from that app's own.
        value = InstalledApps.lastGlyph(packageName)?.let { IconLoad.Found(it) } ?: IconLoad.Loading
        value = withContext(Dispatchers.IO) { InstalledApps.glyph(context, packageName) }?.let { IconLoad.Found(it) } ?: IconLoad.Missing
    }
    Crossfade(icon, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "app-icon") { shown ->
        when (shown) {
            IconLoad.Loading -> Box(modifier)
            IconLoad.Missing -> Icon(painterResource(fallback), contentDescription = null, modifier = modifier)
            is IconLoad.Found -> {
                val image = remember(shown.glyph) { shown.glyph.image.asImageBitmap() }
                if (shown.glyph.themed) Icon(image, contentDescription = null, modifier = modifier)
                else Image(image, contentDescription = null, modifier = modifier.then(fullColor))
            }
        }
    }
}

private sealed interface IconLoad {
    data object Loading : IconLoad
    data object Missing : IconLoad

    /** Equal for the same glyph, so an icon drawn again that looks the same does not fade into itself. */
    data class Found(val glyph: AppGlyph) : IconLoad
}
