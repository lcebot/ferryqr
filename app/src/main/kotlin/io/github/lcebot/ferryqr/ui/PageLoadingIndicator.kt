@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The size of the indicator a page shows while it waits for the camera or an image. */
private val PAGE_LOADING_SIZE = 64.dp

/**
 * The indicator the scanner's page shows over the camera's picture while [visible], as while a
 * reset binds the camera again or an image is read. It is contained, since the picture behind it
 * may be as light as the indicator itself. It is a small part of the page, so it comes in fading
 * and growing on the scheme's fast motion, and it leaves the same way unless [exit] says otherwise.
 */
@Composable
internal fun PageLoadingIndicator(visible: Boolean, modifier: Modifier = Modifier, exit: ExitTransition? = null) {
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(effects) + scaleIn(spatial),
        exit = exit ?: (fadeOut(effects) + scaleOut(spatial)),
    ) {
        ContainedLoadingIndicator(Modifier.size(PAGE_LOADING_SIZE))
    }
}
