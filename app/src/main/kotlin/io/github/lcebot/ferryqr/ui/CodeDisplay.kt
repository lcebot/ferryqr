// No opt-in: Dialog, PredictiveBackHandler and MaterialTheme.motionScheme are all stable APIs.
package io.github.lcebot.ferryqr.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.lcebot.ferryqr.LongCodeEnlarge
import io.github.lcebot.ferryqr.R
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
    var context: Context? = context
    while (context is ContextWrapper) {
        if (context is Activity) return context.window
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
 * A code shown over the whole window on white, for when the card has too little room to make it
 * any larger, as in a wide window. It grows out of the card's own code at [source], in window
 * coordinates, and returns into it when closed by a tap or a back gesture, which shrinks it as it
 * goes. A [long] code is tilted or stretched as [mode] says; tilting keeps it level when the window
 * is wide enough anyway. [drawTall], when given, draws a stretched code again at its new height.
 */
@Composable
fun CodeOverlay(
    code: Bitmap,
    long: Boolean,
    mode: LongCodeEnlarge,
    brighten: Boolean,
    source: Rect?,
    onDismiss: () -> Unit,
    drawTall: ((Float) -> Bitmap?)? = null,
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

        val scope = rememberCoroutineScope()
        val progress = remember { Animatable(0f) }
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        var closing by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { progress.animateTo(1f, spatial) }
        fun close() {
            if (closing) return
            closing = true
            scope.launch {
                progress.animateTo(0f, effects)
                onDismiss()
            }
        }
        PredictiveBackHandler { events ->
            try {
                events.collect { event -> progress.snapTo(1f - BACK_SHRINK * event.progress) }
                close()
            } catch (e: CancellationException) {
                scope.launch { progress.animateTo(1f, spatial) }
                throw e
            }
        }

        var target by remember { mutableStateOf<Rect?>(null) }
        Box(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                    .background(Color.White)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::close)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(OVERLAY_MARGIN),
                contentAlignment = Alignment.Center,
            ) {
                val ratio = code.width.toFloat() / code.height
                val codeModifier = Modifier
                    .onGloballyPositioned { target = it.boundsInWindow() }
                    .graphicsLayer {
                        val from = source
                        val to = target
                        if (from == null || to == null || to.width <= 0f) return@graphicsLayer
                        val p = progress.value
                        val start = from.width / to.width
                        scaleX = lerp(start, 1f, p)
                        scaleY = lerp(start, 1f, p)
                        translationX = lerp(from.center.x - to.center.x, 0f, p)
                        translationY = lerp(from.center.y - to.center.y, 0f, p)
                    }
                when {
                    long && mode == LongCodeEnlarge.TILT -> TiltedCode(code, codeModifier.size(maxWidth, maxHeight))
                    long -> {
                        val height = minOf(maxHeight, maxWidth)
                        val ratio = ((height / maxWidth) * 20).roundToInt() / 20f
                        val tall = remember(ratio) { drawTall?.invoke(ratio) } ?: code
                        PlainCode(tall, codeModifier.size(maxWidth, height), stretch = true)
                    }
                    else -> {
                        val side = minOf(maxWidth, maxHeight * ratio)
                        PlainCode(code, codeModifier.size(side, side / ratio), stretch = false)
                    }
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
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) },
            )
        }
    }
}

/**
 * A code drawn on white; [stretch] fills the area exactly, for a code drawn at the area's own
 * shape. A code of one pixel per module needs [FilterQuality.None] to keep its edges sharp; one
 * already scaled up reads better smoothed as it shrinks.
 */
@Composable
internal fun PlainCode(code: Bitmap, modifier: Modifier, stretch: Boolean, filterQuality: FilterQuality = FilterQuality.Medium) {
    val image = remember(code) { code.asImageBitmap() }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = if (stretch) ContentScale.FillBounds else ContentScale.Fit,
            filterQuality = filterQuality,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
