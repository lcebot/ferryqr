@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.LongCodeEnlarge
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.generate.CodeType

/** The code's height while small; a wide linear code grows sideways from it. */
private val CODE_HEIGHT = 160.dp

/** The white margin [TiltedCode] keeps around the code, so the small card shows it at full width. */
private val TILTED_FRAME = 16.dp

/** Room the sheet needs besides an enlarged code: its handle above, and the hint and spacing below. */
private val ENLARGE_RESERVE = 112.dp

/**
 * Where the code is on screen, read only when the full-window view opens, so following the sheet
 * as it moves recomposes nothing.
 */
private class Place {
    var coordinates: LayoutCoordinates? = null
}

/**
 * The code a card shows and how it is drawn. [tall] is the code drawn again for a stretched area,
 * null while it is drawn or when the code is not stretched.
 */
private data class CardDrawing(
    val format: CodeType,
    val bitmap: Bitmap,
    val tall: Bitmap?,
    val tilted: Boolean,
    val stretched: Boolean,
)

/**
 * The code on white, since readers expect dark modules on light. A tap grows it as large as the
 * sheet allows, so another phone can scan it from a distance, and a second tap returns it. The room
 * is measured from the window as it is, so a turned phone or a resized window never lets the code
 * outgrow the screen. A [long] code, a barcode or a PDF417, is tilted along the diagonal,
 * stretched taller, or shown level over the whole window, as the settings choose, and a tilted one
 * stays level when the area is wide anyway. [onChangeFormat], when given, is a long press.
 * [drawTall] draws the code again as tall as a stretched code has to be for every unit of width,
 * so it grows taller bars rather than a scaled picture; without it the picture is scaled.
 * [format] is what [bitmap] is drawn as: a drawing in another format crossfades in while the area
 * resizes to it, and one in the same format, as when the card is built again, simply replaces it.
 */
@Composable
internal fun CodeImage(
    bitmap: Bitmap,
    format: CodeType,
    long: Boolean,
    onChangeFormat: (() -> Unit)?,
    modifier: Modifier = Modifier,
    drawTall: ((Float) -> Bitmap?)? = null,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val settings = remember { AppSettings.of(context) }
    val mode by settings.longCodeEnlarge.flow.collectAsStateWithLifecycle()
    val brighten by settings.fullBrightness.flow.collectAsStateWithLifecycle()
    val aspect = bitmap.width.toFloat() / bitmap.height
    var enlarged by rememberSaveable { mutableStateOf(false) }
    var overlay by rememberSaveable { mutableStateOf(false) }
    var source by remember { mutableStateOf<Rect?>(null) }
    val place = remember { Place() }
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val bars = with(density) { (WindowInsets.systemBars.getTop(this) + WindowInsets.systemBars.getBottom(this)).toDp() }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val tilted = long && mode == LongCodeEnlarge.TILT
        val stretched = long && mode == LongCodeEnlarge.STRETCH
        val fullScreen = long && mode == LongCodeEnlarge.FULL_SCREEN
        val small = minOf(CODE_HEIGHT * maxOf(aspect, 1f), maxWidth)
        val room = maxOf(windowHeight - bars - ENLARGE_RESERVE, small / aspect)
        val bigWidth = if (long) maxWidth else minOf(maxWidth, room * aspect)
        val bigHeight = when {
            tilted -> room
            stretched -> minOf(room, maxWidth)
            else -> bigWidth / aspect
        }
        // Drawn for the size the code grows to rather than each size on the way, and again for a new
        // drawing, which changes with the format.
        val tall = rememberTallCode(bitmap, tallRatio(bigHeight, bigWidth), drawTall.takeIf { stretched && enlarged })
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
        val width by animateDpAsState(if (enlarged) bigWidth else small, spatial, label = "code-width")
        val height by animateDpAsState(
            targetValue = when {
                enlarged -> bigHeight
                tilted -> small / aspect + TILTED_FRAME
                else -> small / aspect
            },
            animationSpec = spatial,
            label = "code-height",
        )
        val clickLabel = stringResource(if (enlarged) R.string.code_shrink else R.string.code_enlarge)
        val longClickLabel = stringResource(R.string.code_change_format)
        fun tap() {
            haptics.tap()
            if (fullScreen) {
                source = place.coordinates?.takeIf { it.isAttached }?.boundsInWindow()
                overlay = true
            } else {
                enlarged = !enlarged
            }
        }
        val longPress: (() -> Unit)? = onChangeFormat
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val codeFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            // The area takes its size from the springs above, so the transition only crossfades
            // inside it and both pictures share the one resize.
            AnimatedContent(
                targetState = CardDrawing(format, bitmap, tall, tilted, stretched),
                contentKey = { it.format },
                transitionSpec = { fadeIn(codeFade) togetherWith fadeOut(codeFade) using null },
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width, height)
                    .onGloballyPositioned { place.coordinates = it },
                label = "card-code",
            ) { drawn ->
                if (drawn.tilted) {
                    // The card turns inside its area, so a ripple on that area would not match its outline.
                    TiltedCode(
                        drawn.bitmap,
                        Modifier.fillMaxSize().combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = clickLabel,
                            onLongClickLabel = if (longPress != null) longClickLabel else null,
                            onLongClick = longPress,
                            onClick = ::tap,
                        ),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.largeIncreased)
                            .background(Color.White)
                            .combinedClickable(
                                onClickLabel = clickLabel,
                                onLongClickLabel = if (longPress != null) longClickLabel else null,
                                onLongClick = longPress,
                                onClick = ::tap,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val picture = drawn.tall ?: drawn.bitmap
                        val shown = remember(picture) { picture.asImageBitmap() }
                        Image(
                            bitmap = shown,
                            contentDescription = null,
                            // Drawn at its new height, the stretched code fills the area exactly once it is there,
                            // and its bars only grow or shrink in height on the way.
                            contentScale = if (drawn.stretched) ContentScale.FillBounds else ContentScale.Fit,
                            filterQuality = FilterQuality.Medium,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            val resize = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
            // Unclipped, so a shorter hint keeps its edges while the spring undershoots its new width.
            AnimatedContent(
                targetState = enlarged,
                transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)) using SizeTransform(clip = false) { _, _ -> resize } },
                label = "code-hint",
            ) { big ->
                val hint = when {
                    big && longPress != null -> R.string.code_tap_shrink_switch
                    big -> R.string.code_tap_shrink
                    longPress != null -> R.string.code_tap_enlarge_switch
                    else -> R.string.code_tap_enlarge
                }
                Text(
                    stringResource(hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (brighten && enlarged) FullBrightness()
    if (overlay) CodeOverlay(bitmap, brighten, source, onDismiss = { overlay = false }, drawTall = drawTall)
}
