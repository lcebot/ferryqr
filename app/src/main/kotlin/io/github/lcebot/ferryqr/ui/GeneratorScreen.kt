@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.github.lcebot.ferryqr.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.LongCodeEnlarge
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.dispatch.ActiveFerries
import io.github.lcebot.ferryqr.dispatch.ActiveFerry
import io.github.lcebot.ferryqr.dispatch.Ferries
import io.github.lcebot.ferryqr.dispatch.Ferry
import io.github.lcebot.ferryqr.dispatch.PassiveFerry
import io.github.lcebot.ferryqr.generate.AutoFormat
import io.github.lcebot.ferryqr.generate.CodeGenerator
import io.github.lcebot.ferryqr.generate.CodeProblem
import io.github.lcebot.ferryqr.generate.CodeType
import io.github.lcebot.ferryqr.generate.Generated
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The saved choice that lets the content decide the format. */
private const val AUTO = "auto"

/** Longer text is regenerated only after typing pauses, so each keystroke stays responsive. */
private const val LARGE_TEXT = 800
private const val LARGE_TEXT_DELAY_MS = 350L

/** How long the format picker waits for the keyboard to start going down before it opens anyway. */
private const val KEYBOARD_CLOSE_TIMEOUT_MS = 300L

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
    val field = rememberTextFieldState()
    val text = field.text.toString()
    // The format is chosen from the content unless one is picked; the last pick is kept only when
    // the settings ask for it, as the page keeps nothing else between visits.
    val keepFormat = remember { AppSettings.rememberFormat(context) }
    var choice by rememberSaveable { mutableStateOf(if (keepFormat) AppSettings.lastFormat(context) ?: AUTO else AUTO) }
    val chosen = remember(choice) { CodeType.entries.firstOrNull { it.name == choice } }
    val automatic = remember(text) { AutoFormat.pick(text) }
    val type = chosen ?: automatic
    fun choose(next: String) {
        choice = next
        if (keepFormat) AppSettings.setLastFormat(context, next)
    }
    var picking by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeInsets = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val pageDensity = LocalDensity.current

    /**
     * Opens the format picker, with the keyboard sent down first. The picker is a sheet in a window
     * of its own: opened over the keyboard, it would take focus from the page, and the keyboard would
     * leave without the page following it, so the code would jump to its new size. Closed from the
     * page while it still has focus, the keyboard goes down with the page settling as usual, and the
     * sheet rises as soon as the keyboard is on its way, so both move at once.
     */
    fun openPicker() {
        if (imeInsets.getBottom(pageDensity) == 0) {
            picking = true
            return
        }
        focusManager.clearFocus()
        keyboardController?.hide()
        scope.launch {
            withTimeoutOrNull(KEYBOARD_CLOSE_TIMEOUT_MS) {
                snapshotFlow { imeTarget.getBottom(pageDensity) }.first { it == 0 }
            }
            picking = true
        }
    }
    val enlarge = remember { AppSettings.longCodeEnlarge(context) }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    var result by remember { mutableStateOf<Generated?>(null) }
    // Every installed app FerryQR reaches its own way, each in a place of its own in the toolbar so
    // it can fade in and out there: the apps for their own codes first, the general ones last.
    val candidates = remember { ActiveFerries.all.filter { it.installed(context) }.sortedBy { it.general } }
    val appFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val appResize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val ready = ferriesReady()

    // Android only lets the focused app read the clipboard, so wait for focus before reading it.
    LaunchedEffect(Unit) {
        if (prefilled) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        prefilled = true
        val clip = readClipboardText(context)
        // Read from the field itself: by now the text this composition saw may be stale.
        if (!clip.isNullOrEmpty() && field.text.isEmpty()) field.setTextAndPlaceCursorAtEnd(clip)
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
    val unfit = result as? Generated.Unfit
    val typeLabel = stringResource(type.label)
    val separator = stringResource(R.string.list_separator)
    val alternatives = unfit?.alternatives?.map { stringResource(it.label) }.orEmpty().joinToString(separator)
    val error = unfit?.let {
        when (val problem = it.problem) {
            CodeProblem.TooLong ->
                if (alternatives.isEmpty()) stringResource(R.string.generate_too_long_all)
                else stringResource(R.string.generate_too_long, typeLabel, alternatives)
            else -> {
                val reason = problemText(problem)
                if (alternatives.isEmpty()) stringResource(R.string.generate_unfit_all, typeLabel, reason)
                else stringResource(R.string.generate_unfit, typeLabel, reason, alternatives)
            }
        }
    }
    val trailingIcon: (@Composable () -> Unit)? = if (text.isNotEmpty()) {
        {
            IconButton(onClick = { haptics.tap(); field.clearText() }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.generate_clear))
            }
        }
    } else {
        null
    }


    if (picking) {
        FormatPickerSheet(
            text = text,
            selected = chosen,
            original = null,
            onPick = { picked ->
                choose(picked.name)
                picking = false
            },
            onDismiss = { picking = false },
        )
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
            // Turned sideways, the screen has too little height for the code above the controls, so the
            // code takes one half of the width, as tall as the screen lets it be, and the controls the other.
            val sideBySide = maxWidth > maxHeight
            val roomForField = if (sideBySide) {
                maxHeight - keyboard - CHIPS_HEIGHT - TOOLBAR_HEIGHT - GAP * 2
            } else {
                maxHeight - keyboard - CHIPS_HEIGHT - TOOLBAR_HEIGHT - GAP * 3 - maxWidth
            }
            val toolbarRoom = if (sideBySide) (maxWidth - GAP) / 2 else maxWidth
            val fieldLines = (MAX_FIELD_LINES downTo 1).firstOrNull { lines ->
                FIELD_ONE_LINE + lineHeight * (lines - 1) + SUPPORTING_HEIGHT <= roomForField
            } ?: 1

            // What is done with the code sits in one toolbar under it: actions on the code itself,
            // then the apps that can open the text, with sharing as the main way out. The apps are
            // the ones result cards offer for the same content, in the same order.
            val sendApp: (Ferry) -> SendApp = { ferry ->
                val name = ferry.label(context)
                // Without the module the app could only open its scanner on nothing, so the
                // button looks disabled but still explains why when tapped.
                val looksEnabled = (ferry as? ActiveFerry)?.let { ready[it] } ?: ferry.ready
                SendApp(context.getString(R.string.open_in_app, name), ferry.pkg, looksEnabled) {
                    when {
                        activity == null -> Unit
                        !looksEnabled -> {
                            haptics.reject()
                            scope.launch { snackbar.showLatest(context.getString(R.string.needs_module_in_app, name)) }
                        }
                        else -> {
                            haptics.tap()
                            if (!ferry.open(activity, text)) scope.launch { snackbar.showLatest(context.getString(R.string.no_app)) }
                        }
                    }
                }
            }
            val payload = remember(text) { Classifier.classify(text) }
            val offered = remember(payload, ready) { Ferries.offered(context, payload).filter { it.installed(context) } }
            // The app the system opens the text in has a place of its own, between the apps for
            // their own codes and the general ones, and keeps it while it fades out.
            val passive = offered.firstOrNull { it is PassiveFerry }
            var lastPassive by remember { mutableStateOf<Ferry?>(null) }
            LaunchedEffect(passive) { if (passive != null) lastPassive = passive }
            // While the whole group of apps fades out, each app inside keeps its place, so the group
            // shrinks as one instead of its apps shrinking inside it at the same time, two animations
            // that drift apart and jerk when the group comes back before the second has ended.
            val lastLayout = remember { arrayOf(SendLayout(emptySet(), emptyList())) }
            if (offered.isNotEmpty()) lastLayout[0] = sendLayout(offered, toolbarRoom)
            val layout = lastLayout[0]

            // The code opens over the whole window when tapped, as large as the window lets it be.
            var enlarged by rememberSaveable { mutableStateOf(false) }
            var codeBounds by remember { mutableStateOf<Rect?>(null) }
            val brighten = remember { AppSettings.fullBrightness(context) }
            if (enlarged && code != null) {
                CodeOverlay(
                    code,
                    brighten,
                    codeBounds,
                    onDismiss = { enlarged = false },
                    drawTall = if (type.long) {
                        { ratio -> (CodeGenerator.generate(text, type, ratio) as? Generated.Code)?.bitmap }
                    } else {
                        null
                    },
                    long = type.long,
                )
            }

            val chips: @Composable () -> Unit = {
                // The automatic choice leads and names the format it uses; the common formats follow, with
                // one picked from the rest beside them while it is in use, and the rest one tap away.
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Labeled(R.string.format_auto_desc) {
                        FilterChip(
                            selected = chosen == null,
                            onClick = { haptics.step(); choose(AUTO) },
                            label = { Text(stringResource(R.string.format_auto, stringResource(automatic.label))) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_lightbulb), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                            },
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    val formats = CodeType.COMMON + listOfNotNull(chosen?.takeIf { it !in CodeType.COMMON })
                    formats.forEach { option ->
                        FilterChip(
                            selected = option == chosen,
                            onClick = { haptics.step(); choose(option.name) },
                            label = { Text(stringResource(option.label)) },
                        )
                    }
                    // It opens the picker rather than being chosen, so it is an assist chip, which
                    // screen readers announce as a button rather than as an unchecked option.
                    // Its label and icon take the colour of the unselected formats beside it rather than
                    // an assist chip's own, so the row reads as one set of options.
                    AssistChip(
                        onClick = { haptics.tap(); openPicker() },
                        label = { Text(stringResource(R.string.format_more)) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        trailingIcon = {
                            Icon(painterResource(R.drawable.ic_expand), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                        },
                    )
                }
            }
            val codeArea: @Composable (Modifier) -> Unit = { modifier ->
                Box(
                    modifier = modifier.then(
                        if (code == null) {
                            Modifier
                        } else {
                            Modifier.clickable(onClickLabel = stringResource(R.string.code_enlarge)) {
                                haptics.tap()
                                enlarged = true
                            }
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    // Where the code itself is drawn, which the enlarged code grows out of.
                    val bounds = Modifier.onGloballyPositioned { codeBounds = it.boundsInWindow() }
                    if (code != null && type.long && enlarge == LongCodeEnlarge.TILT) {
                        TiltedCode(code, bounds.fillMaxSize())
                    } else if (code != null && type.long) {
                        // Drawn again at the preview's own shape, so its bars fill it without a scaled picture.
                        BoxWithConstraints(bounds.fillMaxSize()) {
                            val ratio = ((maxHeight / maxWidth) * 20).roundToInt() / 20f
                            val tall = remember(text, type, ratio) {
                                (CodeGenerator.generate(text, type, ratio) as? Generated.Code)?.bitmap
                            } ?: code
                            PlainCode(tall, Modifier.fillMaxSize(), stretch = true, filterQuality = FilterQuality.None)
                        }
                    } else {
                        CodePreview(code, bounds.aspectRatio(1f, matchHeightConstraintsFirst = true))
                    }
                }
            }
            val toolbar: @Composable () -> Unit = {
                CodeToolbar(
                    code = code,
                    onMessage = { message -> scope.launch { snackbar.showLatest(context.getString(message)) } },
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
                                val enabled = text.isNotEmpty()
                                for (ferry in candidates.filterNot { it.general }) {
                                    AppSlot(ferry in layout.inline, sendApp(ferry), enabled, appFade, appResize)
                                }
                                (passive ?: lastPassive)?.let { ferry ->
                                    AppSlot(ferry in layout.inline, sendApp(ferry), enabled, appFade, appResize)
                                }
                                for (ferry in candidates.filter { it.general }) {
                                    AppSlot(ferry in layout.inline, sendApp(ferry), enabled, appFade, appResize)
                                }
                                AnimatedVisibility(
                                    visible = layout.folded.isNotEmpty(),
                                    enter = fadeIn(appFade) + expandHorizontally(appResize),
                                    exit = fadeOut(appFade) + shrinkHorizontally(appResize),
                                ) {
                                    SendMenu(layout.folded.map(sendApp), enabled = enabled)
                                }
                            }
                        }
                    }
                }
            }
            val textField: @Composable () -> Unit = {
                OutlinedTextField(
                    state = field,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Its lines change with the room the keyboard leaves. Shrinking, it never springs
                        // below its new height, which would cut off its outline and supporting text.
                        .clipToBounds()
                        .animateSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    label = { Text(stringResource(R.string.generate_input)) },
                    placeholder = { Text(stringResource(R.string.generate_placeholder)) },
                    isError = error != null,
                    supportingText = { Text(error.orEmpty()) },
                    trailingIcon = trailingIcon,
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = fieldLines, maxHeightInLines = fieldLines),
                )
            }

            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    codeArea(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(GAP),
                    ) {
                        chips()
                        toolbar()
                        textField()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    verticalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    chips()
                    codeArea(Modifier.weight(1f).fillMaxWidth())
                    toolbar()
                    textField()
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

/** The apps shown in the toolbar, and those folded behind its overflow button. */
private class SendLayout(val inline: Set<Ferry>, val folded: List<Ferry>)

/**
 * Which of the [offered] apps fit a toolbar [room] wide. All of them when they fit; otherwise the
 * first, the one the text goes to best, stays in view beside the overflow button, which the
 * narrowest phone screen Android allows still holds, and the others fold away.
 */
private fun sendLayout(offered: List<Ferry>, room: Dp): SendLayout {
    fun fits(groupButtons: Int) =
        TOOLBAR_PADDING + TOOLBAR_BUTTON * (CODE_ACTIONS + groupButtons) + GROUP_GAP + TOOLBAR_FAB <= room
    val first = offered.firstOrNull()
    return when {
        fits(offered.size) -> SendLayout(offered.toSet(), emptyList())
        first != null && fits(2) -> SendLayout(setOf(first), offered - first)
        else -> SendLayout(emptySet(), offered)
    }
}

/** An app's place in the toolbar, where its button fades and resizes in and out as it is offered. */
@Composable
private fun RowScope.AppSlot(
    visible: Boolean,
    app: SendApp,
    enabled: Boolean,
    fade: FiniteAnimationSpec<Float>,
    resize: FiniteAnimationSpec<IntSize>,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(fade) + expandHorizontally(resize),
        exit = fadeOut(fade) + shrinkHorizontally(resize),
    ) {
        AppButton(app, enabled = enabled)
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
