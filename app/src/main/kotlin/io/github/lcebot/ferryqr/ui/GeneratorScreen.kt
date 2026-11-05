@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.github.lcebot.ferryqr.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.exclude
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.MenuDefaults
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.Targets
import io.github.lcebot.ferryqr.generate.CodeExport
import io.github.lcebot.ferryqr.generate.CodeGenerator
import io.github.lcebot.ferryqr.generate.CodeType
import io.github.lcebot.ferryqr.generate.Generated
import io.github.lcebot.ferryqr.openin.OpenInHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Longer text is regenerated only after typing pauses, so each keystroke stays responsive. */
private const val LARGE_TEXT = 800
private const val LARGE_TEXT_DELAY_MS = 350L

/**
 * The toolbar's fixed parts, from the floating toolbar's own tokens: its padding, one icon button,
 * the gap before the group of apps, and the gap before the FAB together with the FAB itself.
 */
private val TOOLBAR_PADDING = 16.dp
private val TOOLBAR_BUTTON = 48.dp
private val GROUP_GAP = 4.dp
private val TOOLBAR_FAB = 64.dp

/** Copy, save and favorite, which always stay in the toolbar. */
private const val CODE_ACTIONS = 3

/** Layout estimates used only to decide how many lines the text field can keep. */
private val GAP = 12.dp
private val CHIPS_HEIGHT = 48.dp
private val TOOLBAR_HEIGHT = FloatingToolbarDefaults.ContainerSize
private val FIELD_ONE_LINE = 64.dp
private val SUPPORTING_HEIGHT = 24.dp
private const val MAX_FIELD_LINES = 4

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
    // Every installed app the text could go to, in the order the toolbar shows them: an app that
    // appears for its own code comes first, as it does on result cards.
    val candidates = remember { Targets.all.filter { it.installed(context) }.sortedBy { it.general } }
    val appFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val appResize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val ready = candidates.associate { target ->
        target.pkg to (!target.viaModule || OpenInHelper.ready(target.pkg).collectAsState().value)
    }

    // The scope may have changed in LSPosed since the scanner last asked.
    LaunchedEffect(Unit) { OpenInHelper.refresh() }

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

    fun share() {
        val bitmap = code ?: return
        scope.launch {
            val chooser = withContext(Dispatchers.IO) { CodeExport.share(context, bitmap) }
            if (chooser != null) {
                haptics.tap()
                context.startActivity(chooser)
            } else {
                haptics.reject()
                snackbar.showSnackbar(context.getString(R.string.code_share_failed))
            }
        }
    }

    Scaffold(
        // The scaffold lifts the snackbar only above the system bars, and the keyboard would cover it.
        snackbarHost = {
            SnackbarHost(snackbar, Modifier.windowInsetsPadding(WindowInsets.ime.exclude(ScaffoldDefaults.contentWindowInsets)))
        },
        topBar = {
            // The page never scrolls, so a collapsing app bar would only take space away from the code.
            TopAppBar(
                title = { Text(stringResource(R.string.generate_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            val roomBesideCode = maxHeight - keyboard - CHIPS_HEIGHT - TOOLBAR_HEIGHT - GAP * 3 - maxWidth
            val toolbarRoom = maxWidth
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

                // What is done with the code sits in one toolbar: actions on the code itself, then the
                // apps that can open the text, with sharing as the main way out. The apps are the ones
                // result cards offer for the same content.
                val apps = candidates.associateWith { target ->
                    val name = context.getString(target.name)
                    // Without the helper the app could only open its scanner on nothing, so the
                    // button looks disabled but still explains why when tapped.
                    val looksEnabled = ready.getValue(target.pkg)
                    SendApp(context.getString(R.string.open_in_app, name), target.pkg, looksEnabled) {
                        when {
                            activity == null -> Unit
                            !looksEnabled -> {
                                haptics.reject()
                                scope.launch { snackbar.showSnackbar(context.getString(R.string.needs_module_in_app, name)) }
                            }
                            else -> {
                                haptics.tap()
                                if (!target.open(activity, text)) scope.launch { snackbar.showSnackbar(context.getString(R.string.no_app)) }
                            }
                        }
                    }
                }
                val offered = remember(text, candidates) { Targets.forText(text).filter { it in candidates }.toSet() }
                val placement = sendPlacement(offered.size, toolbarRoom)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    HorizontalFloatingToolbar(
                        expanded = true,
                        floatingActionButton = { ShareButton(enabled = code != null, onClick = ::share) },
                    ) {
                        Labeled(R.string.code_copy) {
                            IconButton(
                                onClick = {
                                    // Android 13 and later preview clipboard contents themselves.
                                    export(CodeExport::copy, R.string.code_copied, R.string.code_copy_failed,
                                        quietOnSuccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                },
                                enabled = code != null,
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.code_copy))
                            }
                        }
                        Labeled(R.string.code_save) {
                            IconButton(
                                onClick = {
                                    export(CodeExport::save, R.string.code_saved, R.string.code_save_failed, quietOnSuccess = false)
                                },
                                enabled = code != null,
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(painterResource(R.drawable.ic_download), stringResource(R.string.code_save))
                            }
                        }
                        // Kept codes are shown again from history, drawn the same way as here. The toggle
                        // takes the toolbar's own content colour, like copy and save beside it.
                        Labeled(R.string.favorite) {
                            FavoriteToggle(
                                text,
                                type.format.name,
                                enabled = code != null,
                                colors = IconButtonDefaults.iconToggleButtonColors(
                                    checkedContainerColor = Color.Transparent,
                                    checkedContentColor = LocalContentColor.current,
                                ),
                            )
                        }
                        AnimatedVisibility(
                            visible = placement != SendPlacement.NONE,
                            enter = fadeIn(appFade) + expandHorizontally(appResize),
                            exit = fadeOut(appFade) + shrinkHorizontally(appResize),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(GROUP_GAP))
                                AppGroup {
                                    // Crossing the width at which the apps no longer fit folds them into
                                    // the overflow button, and the pill resizes between the two.
                                    AnimatedContent(
                                        targetState = placement == SendPlacement.MENU,
                                        transitionSpec = {
                                            fadeIn(appFade) togetherWith fadeOut(appFade) using
                                                SizeTransform(clip = false) { _, _ -> appResize }
                                        },
                                        label = "app-placement",
                                    ) { inMenu ->
                                        if (inMenu) {
                                            SendMenu(candidates.filter { it in offered }.map(apps::getValue), enabled = text.isNotEmpty())
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                for (target in candidates) {
                                                    AnimatedVisibility(
                                                        visible = target in offered,
                                                        enter = fadeIn(appFade) + expandHorizontally(appResize),
                                                        exit = fadeOut(appFade) + shrinkHorizontally(appResize),
                                                    ) {
                                                        AppButton(apps.getValue(target), enabled = text.isNotEmpty())
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** An app the text can be opened in. [looksEnabled] false draws it disabled while it still explains itself. */
private class SendApp(
    val label: String,
    val app: String,
    val looksEnabled: Boolean,
    val onClick: () -> Unit,
)

private enum class SendPlacement { NONE, INLINE, MENU }

/**
 * Where the apps go in a toolbar [room] wide: each in the group when they fit, and otherwise behind
 * one overflow button in the same group. The narrowest phone screen Android allows still holds the
 * toolbar with the overflow button.
 */
private fun sendPlacement(apps: Int, room: Dp): SendPlacement {
    fun width(groupButtons: Int) =
        TOOLBAR_PADDING + TOOLBAR_BUTTON * (CODE_ACTIONS + groupButtons) + GROUP_GAP + TOOLBAR_FAB
    return when {
        apps == 0 -> SendPlacement.NONE
        width(apps) <= room -> SendPlacement.INLINE
        else -> SendPlacement.MENU
    }
}

/**
 * The colour of everything that hands the code to another app: the group of apps and the share FAB.
 * It marks what the actions do rather than how much they matter, so it is one neutral tone for
 * both, two steps from the toolbar's container, and the actions on the code itself stay on the
 * toolbar's own colour.
 */
@Composable
private fun handoffContainer(): Color = MaterialTheme.colorScheme.surfaceContainerHighest

/** The apps share one pill, which sets them apart from the actions on the code without a divider. */
@Composable
private fun AppGroup(content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = CircleShape,
        color = handoffContainer(),
        contentColor = LocalContentColor.current,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** The apps behind one overflow button, for toolbars too narrow to show each of them. */
@Composable
private fun SendMenu(apps: List<SendApp>, enabled: Boolean) {
    val haptics = rememberHaptics()
    var open by remember { mutableStateOf(false) }
    Box {
        Labeled(R.string.entry_more) {
            IconButton(
                onClick = { haptics.tap(); open = true },
                enabled = enabled,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.entry_more))
            }
        }
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                apps.forEachIndexed { index, app ->
                    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    DropdownMenuItem(
                        onClick = {
                            open = false
                            app.onClick()
                        },
                        text = { Text(app.label) },
                        shape = menuItemShape(index, apps.size),
                        leadingIcon = {
                            AppIcon(
                                app.app,
                                R.drawable.ic_open_in_new,
                                Modifier.size(MenuDefaults.LeadingIconSize).alpha(if (app.looksEnabled) 1f else 0.38f),
                            )
                        },
                        colors = if (app.looksEnabled) MenuDefaults.itemColors() else MenuDefaults.itemColors(textColor = dim),
                    )
                }
            }
        }
    }
}

/**
 * Sharing is the toolbar's floating action button, in the same tone as the group of apps, since it
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

/** Hands the text to another app, shown by that app's own icon. */
@Composable
private fun AppButton(app: SendApp, enabled: Boolean) {
    val alpha by animateFloatAsState(
        if (enabled && app.looksEnabled) 1f else 0.38f,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "app-button",
    )
    val description = app.label
    Labeled(app.label) {
        IconButton(
            onClick = app.onClick,
            enabled = enabled,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            AppIcon(app.app, R.drawable.ic_open_in_new, Modifier.size(24.dp).alpha(alpha))
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
