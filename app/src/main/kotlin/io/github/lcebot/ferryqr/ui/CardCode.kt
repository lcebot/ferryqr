@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.LongCodeEnlarge
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.generate.CodeDrawing
import io.github.lcebot.ferryqr.generate.CodeType

/** The code's height while small; a wide linear code grows sideways from it. */
private val CODE_HEIGHT = 160.dp

/** The white margin [TiltedCode] keeps around the code, so the small card shows it at full width. */
private val TILTED_FRAME = 16.dp

/**
 * Room the sheet needs besides an enlarged code and its hint: above the code, the sheet's 48dp drag
 * handle, the kind row as tall as its favorite toggle's 48dp touch target, and the 12dp gap under
 * it; below, the 8dp gap before the hint and the sheet's 24dp padding at the bottom. The hint grows
 * with the font and can wrap, so it is measured rather than counted here.
 */
private val ENLARGE_RESERVE = 48.dp + 48.dp + 12.dp + 8.dp + 24.dp

/**
 * Where the code is on screen, read only when the full-window view opens, so following the sheet
 * as it moves recomposes nothing.
 */
private class Place {
    var coordinates: LayoutCoordinates? = null
}

/**
 * The code a card shows and how it is drawn. [tall] is the code drawn again for a stretched area,
 * null while it is drawn or when the code is not stretched. A [picture] is not drawn from content.
 */
private data class CardDrawing(
    val format: CodeType,
    val bitmap: Bitmap,
    val tall: Bitmap?,
    val tilted: Boolean,
    val stretched: Boolean,
    val picture: Boolean,
)

/**
 * The code on white, since readers expect dark modules on light. A tap grows it as large as the
 * sheet allows, so another phone can scan it from a distance, and a second tap returns it. The room
 * is measured from the window as it is, so a turned phone or a resized window never lets the code
 * outgrow the screen. A [long] code, a barcode or a PDF417, is tilted along the diagonal,
 * stretched taller, or shown level over the whole window, as the settings choose, and a tilted one
 * stays level when the area is wide anyway. [onChangeFormat], when given, is a long press.
 * [drawing] is the code drawn from its content when [bitmap] is its base; a stretched code is drawn
 * again from it as tall as it has to be for every unit of width, so it grows taller bars rather
 * than a scaled picture. Without it, [bitmap] is a picture, such as a photographed code, scaled and
 * smoothed like one.
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
    drawing: CodeDrawing? = null,
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
    // The height of the hint for the size the code is going to, so an enlarged code leaves room for
    // the hint that tells how to shrink it.
    var hintHeight by remember { mutableIntStateOf(0) }
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val bars = with(density) { (WindowInsets.systemBars.getTop(this) + WindowInsets.systemBars.getBottom(this)).toDp() }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val tilted = long && mode == LongCodeEnlarge.TILT
        val stretched = long && mode == LongCodeEnlarge.STRETCH
        val fullScreen = long && mode == LongCodeEnlarge.FULL_SCREEN
        val small = minOf(CODE_HEIGHT * maxOf(aspect, 1f), maxWidth)
        val hintRoom = with(density) { hintHeight.toDp() }
        val room = maxOf(windowHeight - bars - ENLARGE_RESERVE - hintRoom, small / aspect)
        val bigWidth = if (long) maxWidth else minOf(maxWidth, room * aspect)
        val bigHeight = when {
            tilted -> room
            stretched -> minOf(room, maxWidth)
            else -> bigWidth / aspect
        }
        val areaWidth = if (enlarged) bigWidth else small
        val areaHeight = when {
            enlarged -> bigHeight
            tilted -> small / aspect + TILTED_FRAME
            else -> small / aspect
        }
        // Drawn for the size the code grows to rather than each size on the way, and again for new
        // content, which changes with the format. It is kept while the code is small, so enlarging
        // it again shows the same drawing at once.
        val tall = rememberTallCode(drawing, tallRatio(bigHeight, bigWidth), wanted = stretched && enlarged)
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
        // Read only while the area is laid out, so the springs resize it without composing it again.
        val width = animateDpAsState(areaWidth, spatial, label = "code-width")
        val height = animateDpAsState(areaHeight, spatial, label = "code-height")
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
                targetState = CardDrawing(format, bitmap, tall, tilted, stretched, picture = drawing == null),
                contentKey = { it.format },
                transitionSpec = { fadeIn(codeFade) togetherWith fadeOut(codeFade) using null },
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val shownWidth = constraints.constrainWidth(width.value.roundToPx())
                        val shownHeight = constraints.constrainHeight(height.value.roundToPx())
                        val placeable = measurable.measure(Constraints.fixed(shownWidth, shownHeight))
                        layout(shownWidth, shownHeight) { placeable.place(0, 0) }
                    }
                    .onGloballyPositioned { place.coordinates = it },
                label = "card-code",
            ) { drawn ->
                if (drawn.tilted) {
                    // The card turns inside its area, so a ripple on that area would not match its outline.
                    TiltedCode(
                        drawn.bitmap,
                        DpSize(areaWidth, areaHeight),
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
                    PlainCode(
                        drawn.tall ?: drawn.bitmap,
                        Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.largeIncreased)
                            .combinedClickable(
                                onClickLabel = clickLabel,
                                onLongClickLabel = if (longPress != null) longClickLabel else null,
                                onLongClick = longPress,
                                onClick = ::tap,
                            ),
                        // Drawn at its new height, the stretched code fills the area exactly once it is
                        // there, and its bars only grow or shrink in height on the way.
                        stretch = drawn.stretched,
                        picture = drawn.picture,
                    )
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
                    // Only the hint coming in is measured, not the one fading out.
                    modifier = if (big == enlarged) Modifier.onSizeChanged { hintHeight = it.height } else Modifier,
                )
            }
        }
    }
    // An enlarged code is there to be scanned from another device, so like the full-window one it
    // keeps the screen on, and bright where the settings ask for it.
    if (enlarged) KeepScreenOn()
    if (brighten && enlarged) FullBrightness()
    if (overlay) CodeOverlay(bitmap, brighten, source, onDismiss = { overlay = false }, drawing = drawing)
}
