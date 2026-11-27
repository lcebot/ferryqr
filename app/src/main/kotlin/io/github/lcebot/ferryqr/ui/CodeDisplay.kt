// No opt-in: Dialog, PredictiveBackHandler and MaterialTheme.motionScheme are all stable APIs.
package io.github.lcebot.ferryqr.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.generate.CodeDrawing
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Space kept clear around a code shown over the whole window, as its quiet zone and more. */
private val OVERLAY_MARGIN = 24.dp

/** How far a back gesture shrinks the full-window code before it lets go. */
private const val BACK_SHRINK = 0.2f

/**
 * The window [View] belongs to: a dialog's or a sheet's own window when it has one, else the
 * activity's. Brightness and keeping the screen on are set on it.
 */
internal fun View.hostWindow(): Window? {
    var current: Any? = this
    while (current is View) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent
    }
    return context.activity()?.window
}

/** The activity this context belongs to, through any wrappers such as a dialog's theme. */
private fun Context.activity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * Turns the screen to full brightness while in composition, so another device reads the code more
 * easily, and gives back the window's own setting after. Only this window changes, never the
 * system's brightness.
 */
@Composable
fun FullBrightness() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.hostWindow()
        val previous = window?.attributes?.screenBrightness
        if (window != null) {
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        onDispose {
            if (window != null && previous != null) window.attributes = window.attributes.apply { screenBrightness = previous }
        }
    }
}

/** Keeps the screen on while in composition, as a code on show must not go dark. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * A code shown over the whole window on white, as large as the window lets it be. It grows out of
 * the code at [source], in window coordinates, and returns into it when closed by a tap or a back
 * gesture, which shrinks it as it goes.
 *
 * A [long] code, a barcode or a PDF417, is shown level, one of the ways the settings offer to
 * enlarge it. The screen turns sideways while it shows, where the code is longest, and turns back
 * when it closes, even when the activity was recreated for the turn. A screen about to turn fades
 * the code in and out instead, since the card's code is where it was only in the window's earlier
 * shape.
 * [drawing], the code drawn from its content, is drawn again for a long code as tall as the window
 * lets it be, so its bars or rows grow rather than a scaled picture. Any other code keeps its own
 * shape and the screen its orientation.
 */
@Composable
fun CodeOverlay(
    code: Bitmap,
    brighten: Boolean,
    source: Rect?,
    onDismiss: () -> Unit,
    drawing: CodeDrawing? = null,
    long: Boolean = true,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
        ),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            // The white backdrop is the dialog's own; the platform's dimming and window motion would fight it.
            view.hostWindow()?.let {
                it.setDimAmount(0f)
                it.setWindowAnimations(0)
            }
            onDispose {}
        }
        if (brighten) FullBrightness()
        KeepScreenOn()
        val configuration = LocalConfiguration.current
        val turning = remember { long && configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        // Saved with the overlay, so an activity recreated while it shows gives back the orientation
        // it had before the overlay, rather than the landscape the overlay asked for.
        val original by rememberSaveable {
            mutableStateOf(if (long) view.context.activity()?.requestedOrientation else null)
        }
        DisposableEffect(view) {
            if (!long) return@DisposableEffect onDispose {}
            val activity = view.context.activity()
            val restore = original
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            onDispose {
                // An activity being recreated shows the overlay again, which keeps the screen turned.
                if (activity != null && restore != null && !activity.isChangingConfigurations) {
                    activity.requestedOrientation = restore
                }
            }
        }

        val scope = rememberCoroutineScope()
        // Where the code is between its source and the whole window moves on a spring, and how much
        // of the overlay shows fades on its own, so the fade never bounces with the geometry.
        val growth = remember { Animatable(0f) }
        val shown = remember { Animatable(0f) }
        val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
        val effects = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
        var closing by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            launch { shown.animateTo(1f, effects) }
            growth.animateTo(1f, spatial)
        }
        fun close() {
            if (closing) return
            closing = true
            scope.launch {
                launch { growth.animateTo(0f, spatial) }
                shown.animateTo(0f, effects)
                onDismiss()
            }
        }
        PredictiveBackHandler { events ->
            try {
                events.collect { event -> growth.snapTo(1f - BACK_SHRINK * event.progress) }
                close()
            } catch (e: CancellationException) {
                scope.launch { growth.animateTo(1f, spatial) }
                throw e
            }
        }

        var target by remember { mutableStateOf<Rect?>(null) }
        Box(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = shown.value }
                    .background(Color.White)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::close)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(OVERLAY_MARGIN),
                contentAlignment = Alignment.Center,
            ) {
                val codeModifier = Modifier
                    .onGloballyPositioned { target = it.boundsInWindow() }
                    .graphicsLayer {
                        val from = if (turning) null else source
                        val to = target
                        if (from == null || to == null || to.width <= 0f) return@graphicsLayer
                        val p = growth.value
                        val start = from.width / to.width
                        scaleX = lerp(start, 1f, p)
                        scaleY = lerp(start, 1f, p)
                        translationX = lerp(from.center.x - to.center.x, 0f, p)
                        translationY = lerp(from.center.y - to.center.y, 0f, p)
                    }
                if (long) {
                    // Bars taller than the code is wide add nothing a reader needs.
                    FilledTallCode(code, drawing, codeModifier.size(maxWidth, minOf(maxHeight, maxWidth)))
                } else {
                    // Sized to the code's own shape, so it grows from and back into the code it came from.
                    val aspect = code.width.toFloat() / code.height
                    val width = minOf(maxWidth, maxHeight * aspect)
                    PlainCode(code, codeModifier.size(width, width / aspect), stretch = true)
                }
            }
            Text(
                stringResource(R.string.code_tap_close),
                style = MaterialTheme.typography.labelMedium,
                // The backdrop is white in every theme.
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 8.dp)
                    .graphicsLayer { alpha = shown.value },
            )
        }
    }
}

/**
 * A code drawn on white, in the middle of the area [modifier] gives it; [stretch] fills the area
 * exactly, for a code drawn at the area's own shape. Drawn larger than its pixels, a code keeps the
 * edges of its modules sharp only unsmoothed, since smoothing a few pixels per module across a whole
 * screen blurs every edge; drawn smaller, smoothing keeps thin modules from dropping out. A
 * [picture], a photographed code or one drawn with curves at a size of its own, has no modules a
 * pixel wide to keep sharp, so it is smoothed at any size.
 *
 * The size is read only while drawing, so an area that resizes on every frame draws the code again
 * without composing it again.
 */
@Composable
internal fun PlainCode(code: Bitmap, modifier: Modifier, stretch: Boolean, picture: Boolean = false) {
    val image = remember(code) { code.asImageBitmap() }
    Spacer(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White)
            .drawBehind {
                val scale = minOf(size.width / image.width, size.height / image.height)
                val shown = if (stretch) size else Size(image.width * scale, image.height * scale)
                drawImage(
                    image = image,
                    dstOffset = IntOffset(
                        ((size.width - shown.width) / 2).roundToInt(),
                        ((size.height - shown.height) / 2).roundToInt(),
                    ),
                    dstSize = IntSize(shown.width.roundToInt(), shown.height.roundToInt()),
                    filterQuality = if (!picture && scale >= 1f) FilterQuality.None else FilterQuality.Medium,
                )
            },
    )
}
