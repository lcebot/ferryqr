@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
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
 * hull of each glyph's ink is 0.540 of the side for save, 0.739 for copy and 0.591 for the star,
 * so save is drawn at 24 * 0.591 / 0.540 and copy at 24 * 0.591 / 0.739.
 */
private val SAVE_ICON = 26.dp
private val COPY_ICON = 19.dp

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

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            floatingActionButton = { ShareButton(enabled = code != null, onClick = ::share) },
        ) {
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
                    Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.code_copy), Modifier.size(COPY_ICON))
                }
            }
            extra()
        }
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
private fun ShareButton(enabled: Boolean, onClick: () -> Unit) {
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
            containerColor = container,
            contentColor = content,
        ) {
            Icon(painterResource(R.drawable.ic_share), stringResource(R.string.code_share))
        }
    }
}
