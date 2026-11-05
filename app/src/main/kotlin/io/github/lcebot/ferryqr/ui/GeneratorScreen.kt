@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.github.lcebot.ferryqr.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.exclude
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.PopupProperties
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.Target
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.dispatch.Targets
import io.github.lcebot.ferryqr.generate.CodeGenerator
import io.github.lcebot.ferryqr.generate.CodeType
import io.github.lcebot.ferryqr.generate.Generated
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
private val TOOLBAR_FAB = TOOLBAR_TO_FAB_GAP + SHARE_FAB_SIZE

/** Copy, save and favorite, which always stay in the toolbar. */
private const val CODE_ACTIONS = 3

/** Layout estimates used only to decide how many lines the text field can keep. */
private val GAP = 12.dp
private val CHIPS_HEIGHT = 48.dp
/** The floating toolbar's own height, which the share FAB beside it does not exceed. */
private val TOOLBAR_HEIGHT = 64.dp
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
    val ready = targetsReady()

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
            // animation, so the field changes size once, as the keyboard starts to move, and the
            // page settles in one motion with it. The keyboard also closes when a menu or the share
            // sheet takes focus, and the field then grows while the keyboard leaves, rather than
            // the code first filling the space and giving it back afterwards.
            val density = LocalDensity.current
            val navigationBar = WindowInsets.navigationBars.getBottom(density)
            val keyboardPx = WindowInsets.imeAnimationTarget.getBottom(density) - navigationBar
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

                // What is done with the code sits in one toolbar under it: actions on the code itself,
                // then the apps that can open the text, with sharing as the main way out. The apps are
                // the ones result cards offer for the same content.
                val apps = candidates.associateWith { target ->
                    val name = context.getString(target.name)
                    // Without the module the app could only open its scanner on nothing, so the
                    // button looks disabled but still explains why when tapped.
                    val looksEnabled = ready.getValue(target)
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
                val payload = remember(text) { Classifier.classify(text) }
                val offered = remember(payload, candidates) { Targets.offeredFor(payload).filter { it in candidates } }
                val owner = remember(payload, candidates) { Targets.claimant(payload)?.takeIf { it in candidates } }
                val layout = sendLayout(offered, owner, toolbarRoom)
                CodeToolbar(
                    code = code,
                    onMessage = { message -> scope.launch { snackbar.showSnackbar(context.getString(message)) } },
                    favorite = {
                        // Kept codes are shown again from history, drawn the same way as here. Unstarred,
                        // the toggle takes the toolbar's own content colour, like save and copy beside it;
                        // starred, it fills like the favorite on result cards.
                        Labeled(R.string.favorite) {
                            FavoriteToggle(
                                text,
                                type.format.name,
                                enabled = code != null,
                                colors = IconButtonDefaults.iconToggleButtonColors(
                                    checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ),
                            )
                        }
                    },
                ) {
                    AnimatedVisibility(
                        visible = offered.isNotEmpty(),
                        enter = fadeIn(appFade) + expandHorizontally(appResize),
                        exit = fadeOut(appFade) + shrinkHorizontally(appResize),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(GROUP_GAP))
                            // Folding apps away or bringing them back resizes the pill a button at a
                            // time, the same way as when an app appears for the text.
                            AppGroup {
                                for (target in candidates) {
                                    AnimatedVisibility(
                                        visible = target in layout.inline,
                                        enter = fadeIn(appFade) + expandHorizontally(appResize),
                                        exit = fadeOut(appFade) + shrinkHorizontally(appResize),
                                    ) {
                                        AppButton(apps.getValue(target), enabled = text.isNotEmpty())
                                    }
                                }
                                AnimatedVisibility(
                                    visible = layout.folded.isNotEmpty(),
                                    enter = fadeIn(appFade) + expandHorizontally(appResize),
                                    exit = fadeOut(appFade) + shrinkHorizontally(appResize),
                                ) {
                                    SendMenu(layout.folded.map(apps::getValue), enabled = text.isNotEmpty())
                                }
                            }
                        }
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

/** The apps shown in the toolbar, and those folded behind its overflow button. */
private class SendLayout(val inline: Set<Target>, val folded: List<Target>)

/**
 * Which apps fit a toolbar [room] wide. All of them when they fit; otherwise the app the text
 * belongs to stays in view beside the overflow button, which the narrowest phone screen Android
 * allows still holds, and the others fold away.
 */
private fun sendLayout(offered: List<Target>, owner: Target?, room: Dp): SendLayout {
    fun fits(groupButtons: Int) =
        TOOLBAR_PADDING + TOOLBAR_BUTTON * (CODE_ACTIONS + groupButtons) + GROUP_GAP + TOOLBAR_FAB <= room
    return when {
        fits(offered.size) -> SendLayout(offered.toSet(), emptyList())
        owner != null && fits(2) -> SendLayout(setOf(owner), offered - owner)
        else -> SendLayout(emptySet(), offered)
    }
}


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
        // The menu leaves focus with the text field, so the keyboard stays and the page keeps still.
        DropdownMenuPopup(
            expanded = open,
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = false),
        ) {
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
