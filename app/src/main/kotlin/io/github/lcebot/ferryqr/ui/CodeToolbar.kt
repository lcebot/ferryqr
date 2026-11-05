@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.generate.CodeExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The save and copy glyphs are drawn at sizes that make them look as large as the 24dp star beside
 * them. Measured by rasterising each glyph on its 960 unit viewport, the square root of the convex
 * hull of each glyph's ink is 0.540 of the side for save and 0.591 for the star, so save is drawn
 * at 24 * 0.591 / 0.540. Its lines, 80 units wide like every glyph here, grow to 2.17dp with it,
 * close to the star's 2dp. Copy would shrink to 19dp and its lines to 1.58dp, which looks thin, so
 * its toolbar glyph adds a 23 unit outline stroke: that widens its hull to 0.762, so it is drawn at
 * 24 * 0.591 / 0.762, and its lines, 103 units at that size, come to 2dp.
 */
private val SAVE_ICON = 26.dp
private val COPY_ICON = 18.6.dp

/**
 * The share FAB and its gap from the toolbar, at the sizes the floating toolbar gives its own FAB
 * when expanded. The FAB sits beside the toolbar rather than in its FAB slot: that slot reserves
 * the toolbar's widest possible content, which counts every app still fading in or out at full
 * width, so the toolbar would jump sideways whenever the apps change.
 */
internal val SHARE_FAB_SIZE = 56.dp
internal val TOOLBAR_TO_FAB_GAP = 8.dp

/**
 * The actions on a code image, in one floating toolbar under the code: [favorite] when given,
 * then save and copy, then [extra] actions, with sharing as the floating action button. Results
 * are told through [onMessage], since a sheet hides the snackbar a page would use.
 */
@Composable
fun CodeToolbar(
    code: Bitmap?,
    onMessage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    favorite: (@Composable () -> Unit)? = null,
    extra: @Composable RowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    fun export(action: (Context, Bitmap) -> Boolean, @StringRes done: Int?, @StringRes failed: Int) {
        val bitmap = code ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { action(context, bitmap) }
            if (ok) haptics.confirm() else haptics.reject()
            (if (ok) done else failed)?.let(onMessage)
        }
    }

    fun share() {
        val bitmap = code ?: return
        scope.launch {
            val chooser = withContext(Dispatchers.IO) { CodeExport.share(context, bitmap) }
            if (chooser != null) {
                haptics.tap()
                context.startActivity(chooser)
            } else {
                haptics.reject()
                onMessage(R.string.code_share_failed)
            }
        }
    }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TOOLBAR_TO_FAB_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalFloatingToolbar(expanded = true) {
            favorite?.invoke()
            Labeled(R.string.code_save) {
                IconButton(
                    onClick = { export(CodeExport::save, R.string.code_saved, R.string.code_save_failed) },
                    enabled = code != null,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(painterResource(R.drawable.ic_download), stringResource(R.string.code_save), Modifier.size(SAVE_ICON))
                }
            }
            Labeled(R.string.code_copy) {
                IconButton(
                    onClick = {
                        // Android 13 and later preview clipboard contents themselves.
                        val quiet = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        export(CodeExport::copy, if (quiet) null else R.string.code_copied, R.string.code_copy_failed)
                    },
                    enabled = code != null,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(painterResource(R.drawable.ic_content_copy_small), stringResource(R.string.code_copy), Modifier.size(COPY_ICON))
                }
            }
            extra()
        }
        ShareButton(enabled = code != null, onClick = ::share, modifier = Modifier.size(SHARE_FAB_SIZE))
    }
}

/**
 * The colour of everything that hands the code to another app: groups of apps and the share FAB.
 * It marks what the actions do rather than how much they matter, so it is one neutral tone for
 * all of them, two steps from the toolbar's container, while the actions on the code itself stay
 * on the toolbar's own colour.
 */
@Composable
fun handoffContainer(): Color = MaterialTheme.colorScheme.surfaceContainerHighest

/**
 * Sharing is the toolbar's floating action button, in the same tone as any group of apps, since it
 * too hands the code to another app. A FAB has no disabled state of its own, so with no code yet it
 * takes the disabled colours and answers a tap only with a rejecting buzz.
 */
@Composable
private fun ShareButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val defaults = FloatingToolbarDefaults.standardFloatingToolbarColors()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(
        if (enabled) handoffContainer() else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        effects,
        label = "share-container",
    )
    val content by animateColorAsState(
        if (enabled) defaults.toolbarContentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        effects,
        label = "share-content",
    )
    Labeled(R.string.code_share) {
        FloatingToolbarDefaults.StandardFloatingActionButton(
            onClick = { if (enabled) onClick() else haptics.reject() },
            modifier = modifier,
            containerColor = container,
            contentColor = content,
        ) {
            Icon(painterResource(R.drawable.ic_share), stringResource(R.string.code_share))
        }
    }
}
