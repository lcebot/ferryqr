@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.ReadContent
import io.github.lcebot.ferryqr.scan.Found
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STAGGER_MS = 70L

/**
 * Freezes the frame that contained several codes and lets the user tap the one they meant. Only
 * while [active] does it take taps and the back gesture, so the copy fading out after a pick or a
 * close stays inert. While active it also holds every touch that lands on it, so a tap beside the
 * markers or a pinch never reaches the scanner's controls or the camera's zoom beneath.
 * [contentOf] gives the content of a code read, which its marker describes.
 */
@Composable
fun PickOverlay(
    frame: Bitmap,
    codes: List<Found>,
    alreadyShown: Set<String>,
    contentOf: (Found.Read) -> ReadContent,
    active: Boolean,
    onPick: (Found) -> Unit,
    onClose: () -> Unit,
) {
    // How far a back gesture has shrunk the overlay, and apart from it how far it has dimmed it, so
    // a cancelled gesture springs back to full size while the dimming lifts without bouncing. Both
    // move on the slow springs, since the overlay covers the whole window.
    val shrink = remember { Animatable(0f) }
    val dim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    // Fading out after a pick, it no longer answers the back gesture, which would cancel the pick.
    PredictiveBackHandler(enabled = active) { progress ->
        try {
            progress.collect {
                shrink.snapTo(it.progress)
                dim.snapTo(it.progress)
            }
            onClose()
        } catch (e: CancellationException) {
            scope.launch {
                launch { dim.animateTo(0f, effects) }
                shrink.animateTo(0f, spatial)
            }
            throw e
        }
    }
    val haptics = rememberHaptics()
    val image = remember(frame) { frame.asImageBitmap() }
    // The markers sit on the codes in the frame's own pixels, which run from the left edge whatever
    // the layout direction, so they start from the top left corner and move by absolute offsets.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = 1f - dim.value * 0.6f
                scaleX = 1f - shrink.value * 0.08f
                scaleY = 1f - shrink.value * 0.08f
            }
            // Being hit is what keeps touches from the scanner beneath, so the events are awaited
            // and left unconsumed: consuming them here would cancel the taps on the markers inside.
            .then(if (active) Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } } else Modifier)
            .background(Color.Black),
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        val viewWidth = constraints.maxWidth.toFloat()
        val viewHeight = constraints.maxHeight.toFloat()
        val crop = CenterCrop(IntSize(frame.width, frame.height), Size(viewWidth, viewHeight))
        val markerSize = CameraOverlayStyle.markerSize
        val half = with(LocalDensity.current) { markerSize.width.toPx() } / 2f
        val popSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()

        Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = PICKER_SCRIM_ALPHA)))

        // Only read codes had markers while the camera ran, so a located one is always fresh.
        fun Found.wasShown() = this is Found.Read && detection.text in alreadyShown
        val fresh = codes.filterNot { it.wasShown() }
        codes.forEach { code ->
            val at = crop.toViewWithin(code.center.x, code.center.y, half)
            // A code that already had a marker while the camera ran keeps it without popping again;
            // codes that arrived with the frozen frame pop in one after another.
            val wasShown = code.wasShown()
            val pop = remember(code) { MutableTransitionState(wasShown) }
            LaunchedEffect(code) {
                if (wasShown) return@LaunchedEffect
                delay(fresh.indexOf(code) * STAGGER_MS)
                pop.targetState = true
            }
            AnimatedVisibility(
                visibleState = pop,
                enter = scaleIn(popSpec),
                modifier = Modifier.absoluteOffset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) },
            ) {
                val content = remember(code) { (code as? Found.Read)?.let(contentOf) }
                CodeMarker(
                    R.drawable.ic_arrow_forward,
                    contentDescription = codeDescription(content),
                    // The copy fading out after a pick or a close takes no taps.
                    enabled = active,
                    onClick = { haptics.tap(); onPick(code) },
                )
            }
        }

        Text(
            stringResource(R.string.pick_title),
            style = MaterialTheme.typography.titleMedium,
            color = CameraOverlayStyle.onPicture,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 24.dp, start = 24.dp, end = 24.dp),
        )
        FilledTonalIconButton(
            onClick = { haptics.tap(); onClose() },
            enabled = active,
            shapes = IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.mediumPressedShape),
            // Disabled in its enabled colours, as the code markers are.
            colors = IconButtonDefaults.filledTonalIconButtonColors().let {
                it.copy(disabledContainerColor = it.containerColor, disabledContentColor = it.contentColor)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
                .size(markerSize),
        ) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.pick_close),
                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
            )
        }
    }
}

/**
 * What a screen reader calls a code found in a picture, [content] when it was read: the title its tile
 * shows, which names a two-factor key by its issuer and account and is cut to a bounded length, so
 * kilobytes of content are never read out. Sensitive content that carries no name of its own, as an
 * eSIM code naming no server, would be titled by its secret, so it is called by its kind. A mini
 * program code only located has no content and is called what its card calls it.
 */
@Composable
internal fun codeDescription(content: ReadContent?): String {
    if (content == null) return stringResource(R.string.mini_headline)
    val context = LocalContext.current
    val languages = LocalConfiguration.current.locales
    return remember(content, languages) {
        val names = content.names(context, null)
        if (content.sensitiveKind != null && names.carried.name == null) context.getString(content.kind.label) else names.tileTitle
    }
}
