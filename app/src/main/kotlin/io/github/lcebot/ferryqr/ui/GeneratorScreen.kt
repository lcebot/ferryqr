@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.github.lcebot.ferryqr.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.Alipay
import io.github.lcebot.ferryqr.dispatch.Launch
import io.github.lcebot.ferryqr.dispatch.WeChat
import io.github.lcebot.ferryqr.generate.CodeExport
import io.github.lcebot.ferryqr.generate.CodeGenerator
import io.github.lcebot.ferryqr.generate.CodeType
import io.github.lcebot.ferryqr.generate.Generated
import io.github.lcebot.ferryqr.helper.WeChatHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Longer text is regenerated only after typing pauses, so each keystroke stays responsive. */
private const val LARGE_TEXT = 800
private const val LARGE_TEXT_DELAY_MS = 350L

/** Layout estimates used only to decide how many lines the text field can keep. */
private val GAP = 12.dp
private val CHIPS_HEIGHT = 48.dp
private val BUTTONS_HEIGHT = 48.dp
private val FIELD_ONE_LINE = 64.dp
private val SUPPORTING_HEIGHT = 24.dp
private const val MAX_FIELD_LINES = 4

/** White margin around a tilted PDF417, matching the quiet zone readers expect. */
private val TILTED_MARGIN = 8.dp
private const val MAX_TILT = 75
private const val TILT_GAIN = 1.02f

@Composable
fun GeneratorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val windowInfo = LocalWindowInfo.current
    var text by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(CodeType.QR) }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    var result by remember { mutableStateOf<Generated?>(null) }
    val alipayInstalled = remember { Launch.isInstalled(context, Alipay.PACKAGE) }
    val wechatInstalled = remember { Launch.isInstalled(context, WeChat.PACKAGE) }
    val helperReady by WeChatHelper.ready.collectAsState()
    val sendRow = alipayInstalled || wechatInstalled

    // The scanner probes on start, but the generator can be reached before that probe has answered.
    LaunchedEffect(Unit) { if (!WeChatHelper.ready.value) WeChatHelper.probe(context) }

    // Android only lets the focused app read the clipboard, so wait for focus before reading it.
    LaunchedEffect(Unit) {
        if (prefilled) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        prefilled = true
        val clip = readClipboardText(context)
        if (!clip.isNullOrEmpty() && text.isEmpty()) text = clip
    }

    LaunchedEffect(text, type) {
        if (text.isEmpty()) {
            result = null
            return@LaunchedEffect
        }
        if (text.length > LARGE_TEXT) delay(LARGE_TEXT_DELAY_MS)
        result = withContext(Dispatchers.Default) { CodeGenerator.generate(text, type) }
    }

    val code = (result as? Generated.Code)?.bitmap
    val tooLong = result as? Generated.TooLong
    val typeLabel = stringResource(type.label)
    val separator = stringResource(R.string.list_separator)
    val alternativeLabels = tooLong?.alternatives?.map { stringResource(it.label) }.orEmpty()
    val error = when {
        tooLong == null -> null
        alternativeLabels.isEmpty() -> stringResource(R.string.generate_too_long_all)
        else -> stringResource(R.string.generate_too_long, typeLabel, alternativeLabels.joinToString(separator))
    }
    val trailingIcon: (@Composable () -> Unit)? = if (text.isNotEmpty()) {
        {
            IconButton(onClick = { haptics.tap(); text = "" }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.generate_clear))
            }
        }
    } else {
        null
    }

    fun export(action: (Context, Bitmap) -> Boolean, @StringRes done: Int, @StringRes failed: Int, quietOnSuccess: Boolean) {
        val bitmap = code ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { action(context, bitmap) }
            if (ok) haptics.confirm() else haptics.reject()
            if (!ok || !quietOnSuccess) snackbar.showSnackbar(context.getString(if (ok) done else failed))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // The page never scrolls, so a collapsing app bar would only take space away from the code.
            TopAppBar(
                title = { Text(stringResource(R.string.generate_title)) },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            // Everything fits on one screen: the text field gives up lines first, then the code shrinks.
            // The line count follows where the keyboard is heading rather than each frame of its
            // animation: it drops as soon as the keyboard starts opening and rises only once it has
            // closed, so the field changes size once instead of stepping through every line count.
            val density = LocalDensity.current
            val navigationBar = WindowInsets.navigationBars.getBottom(density)
            val keyboardPx = maxOf(
                WindowInsets.imeAnimationSource.getBottom(density),
                WindowInsets.imeAnimationTarget.getBottom(density),
            ) - navigationBar
            val keyboard = with(density) { keyboardPx.coerceAtLeast(0).toDp() }
            val lineHeight = with(density) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
            val buttonRows = if (sendRow) 2 else 1
            val roomBesideCode = maxHeight - keyboard - CHIPS_HEIGHT - (BUTTONS_HEIGHT + GAP) * buttonRows - GAP * 2 - maxWidth
            val fieldLines = (MAX_FIELD_LINES downTo 1).firstOrNull { lines ->
                FIELD_ONE_LINE + lineHeight * (lines - 1) + SUPPORTING_HEIGHT <= roomBesideCode
            } ?: 1

            Column(
                modifier = Modifier.fillMaxSize().imePadding(),
                verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CodeType.entries.forEach { option ->
                        FilterChip(
                            selected = option == type,
                            onClick = { haptics.step(); type = option },
                            label = { Text(stringResource(option.label)) },
                        )
                    }
                }

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (code != null && type == CodeType.PDF417) {
                        TiltedCode(code, Modifier.fillMaxSize())
                    } else {
                        CodePreview(code, Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true))
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    label = { Text(stringResource(R.string.generate_input)) },
                    placeholder = { Text(stringResource(R.string.generate_placeholder)) },
                    isError = error != null,
                    supportingText = { Text(error.orEmpty()) },
                    trailingIcon = trailingIcon,
                    minLines = fieldLines,
                    maxLines = fieldLines,
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = {
                            // Android 13 and later preview clipboard contents themselves.
                            export(CodeExport::copy, R.string.generate_copied, R.string.generate_copy_failed,
                                quietOnSuccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        },
                        enabled = code != null,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(painterResource(R.drawable.ic_content_copy), null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.generate_copy))
                    }
                    Button(
                        onClick = {
                            export(CodeExport::save, R.string.generate_saved, R.string.generate_save_failed, quietOnSuccess = false)
                        },
                        enabled = code != null,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(painterResource(R.drawable.ic_download), null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.generate_save))
                    }
                }

                if (sendRow && activity != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (alipayInstalled) {
                            SendButton(R.string.generate_send_alipay, Alipay.PACKAGE, R.drawable.ic_open_in_new, text.isNotEmpty(), looksEnabled = true) {
                                haptics.tap()
                                if (!Alipay.handoff(activity, text)) scope.launch { snackbar.showSnackbar(context.getString(R.string.no_app)) }
                            }
                        }
                        if (wechatInstalled) {
                            // Without the helper WeChat could only open its scanner on nothing, so the
                            // button looks disabled but still explains why when tapped.
                            SendButton(R.string.generate_send_wechat, WeChat.PACKAGE, R.drawable.ic_open_in_new, text.isNotEmpty(), looksEnabled = helperReady) {
                                if (!helperReady) {
                                    haptics.reject()
                                    scope.launch { snackbar.showSnackbar(context.getString(R.string.wechat_needs_module)) }
                                } else {
                                    haptics.tap()
                                    if (!WeChat.openScanner(activity, text)) scope.launch { snackbar.showSnackbar(context.getString(R.string.no_app)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hands the text to another app. [looksEnabled] false draws the disabled colours while the button
 * still takes taps, so it can say why it does nothing.
 */
@Composable
private fun RowScope.SendButton(
    @StringRes label: Int,
    app: String,
    @DrawableRes fallback: Int,
    enabled: Boolean,
    looksEnabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (looksEnabled) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.weight(1f),
    ) {
        AppIcon(app, fallback, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(label), maxLines = 1)
    }
}

/**
 * A PDF417 is long and flat, so it is shown on its own white card tilted to whatever angle makes it
 * largest in the available area. Exports keep the code upright.
 */
@Composable
private fun TiltedCode(code: Bitmap, modifier: Modifier) {
    val image = remember(code) { code.asImageBitmap() }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val margin = with(density) { TILTED_MARGIN.toPx() }
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val ratio = code.width.toFloat() / code.height

        // Largest code height that fits when the card, margin included, is turned by `degrees`.
        fun fit(degrees: Float): Float {
            val radians = Math.toRadians(degrees.toDouble())
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            val byWidth = (width - 2 * margin * (c + s)) / (ratio * c + s)
            val byHeight = (height - 2 * margin * (c + s)) / (ratio * s + c)
            return min(byWidth, byHeight).coerceAtLeast(0f)
        }

        // Prefer a flatter angle unless tilting further makes the code clearly larger.
        var target = 0f
        var best = fit(0f)
        for (degrees in 1..MAX_TILT) {
            val size = fit(degrees.toFloat())
            if (size > best * TILT_GAIN) {
                best = size
                target = degrees.toFloat()
            }
        }
        val angle by animateFloatAsState(target, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "pdf417-tilt")
        val codeHeight = fit(angle)
        val cardWidth = with(density) { (codeHeight * ratio + 2 * margin).toDp() }
        val cardHeight = with(density) { (codeHeight + 2 * margin).toDp() }

        Box(
            Modifier
                // Unrotated, the card may be wider than the area; only its rotated footprint has to fit.
                .requiredSize(cardWidth, cardHeight)
                .graphicsLayer { rotationZ = -angle }
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(TILTED_MARGIN)
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The code always sits on white, whatever the theme, because readers expect dark modules on light. */
@Composable
private fun CodePreview(code: Bitmap?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            if (code != null) {
                val image = remember(code) { code.asImageBitmap() }
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .padding(8.dp)
                            .aspectRatio(code.width.toFloat() / code.height),
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    // The placeholder shrinks with the preview, so its icon scales down instead of overflowing.
                    Icon(
                        painterResource(R.drawable.ic_qr_code_2),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false).sizeIn(maxWidth = 56.dp, maxHeight = 56.dp).aspectRatio(1f),
                    )
                    Text(
                        stringResource(R.string.generate_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    if (clipboard.primaryClipDescription?.hasMimeType("text/*") != true) return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}
