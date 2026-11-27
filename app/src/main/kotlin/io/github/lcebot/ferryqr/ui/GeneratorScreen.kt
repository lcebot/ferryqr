@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.github.lcebot.ferryqr.ui

import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.LongCodeEnlarge
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.dispatch.ActiveFerries
import io.github.lcebot.ferryqr.dispatch.ActiveFerry
import io.github.lcebot.ferryqr.dispatch.Ferries
import io.github.lcebot.ferryqr.dispatch.Ferry
import io.github.lcebot.ferryqr.generate.AutoFormat
import io.github.lcebot.ferryqr.generate.CodeGenerator
import io.github.lcebot.ferryqr.generate.CodeProblem
import io.github.lcebot.ferryqr.generate.CodeType
import io.github.lcebot.ferryqr.generate.Generated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The saved choice that lets the content decide the format. */
private const val AUTO = "auto"

/** Longer text is regenerated only after typing pauses, so each keystroke stays responsive. */
private const val LARGE_TEXT = 800
private const val LARGE_TEXT_DELAY_MS = 350L

/**
 * The most of a clip the page takes in. It is beyond what any format holds, so a longer clip still
 * reads as too long rather than becoming a code of its beginning.
 */
private const val CLIP_MAX_CHARS = 4096

/** How long the format picker waits for the keyboard to start going down before it opens anyway. */
private const val KEYBOARD_CLOSE_TIMEOUT_MS = 300L

private val GAP = 12.dp

/** The apps offered for the text are looked up once typing has paused this long. */
private const val OFFER_DELAY_MS = 150L

@Composable
fun GeneratorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val windowInfo = LocalWindowInfo.current
    // The text is read in effects and in the parts that depend on it, and values drawn from it are
    // derived, so a keystroke composes the field again rather than the whole page.
    val field = rememberTextFieldState()
    val hasText by remember { derivedStateOf { field.text.isNotEmpty() } }
    // The format is chosen from the content unless one is picked; the last pick is kept only when
    // the settings ask for it, as the page keeps nothing else between visits.
    val settings = remember { AppSettings.of(context) }
    val keepFormat by settings.rememberFormat.flow.collectAsStateWithLifecycle()
    var choice by rememberSaveable { mutableStateOf(if (settings.rememberFormat.value) settings.lastFormat.value ?: AUTO else AUTO) }
    val chosen by remember { derivedStateOf { CodeType.entries.firstOrNull { it.name == choice } } }
    val automatic by remember { derivedStateOf { AutoFormat.pick(field.text.toString()) } }
    val type by remember { derivedStateOf { chosen ?: automatic } }
    fun choose(next: String) {
        choice = next
        if (keepFormat) settings.lastFormat.value = next
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
    val enlarge by settings.longCodeEnlarge.flow.collectAsStateWithLifecycle()
    var prefilled by rememberSaveable { mutableStateOf(false) }
    var made by remember { mutableStateOf<Made?>(null) }
    // The field follows only whether the text fits, not every code made from it.
    val unfit by remember { derivedStateOf { made?.generated as? Generated.Unfit } }
    // Every installed app FerryQR reaches its own way, each in a place of its own in the toolbar so
    // it can fade in and out there: the apps for their own codes first, the general ones last.
    // Asking the package manager is a call into another process, so it stays off the main thread,
    // and the apps are named there too, as every app offered is.
    val candidates by produceState(emptyList<NamedFerry<ActiveFerry>>()) {
        value = withContext(Dispatchers.IO) {
            ActiveFerries.all.filter { it.installed(context) }.sortedBy { it.general }.map { NamedFerry(it, it.label(context)) }
        }
    }
    val appFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val appResize = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    val ready = readyApps()
    // The apps for the text are the ones result cards offer for the same content, in the same order,
    // looked up once typing pauses rather than on every keystroke. An app the system opens the text
    // in is named by the package manager, so each is named here, off the main thread.
    val offered by produceState(emptyList<NamedFerry<Ferry>>(), ready) {
        snapshotFlow { field.text.toString() }.collectLatest { text ->
            delay(OFFER_DELAY_MS)
            value = withContext(Dispatchers.IO) {
                Ferries.offered(context, Classifier.classify(text), ready)
                    .filter { it.installed(context) }
                    .map { NamedFerry(it, it.label(context)) }
            }
        }
    }

    // Android only lets the focused app read the clipboard, so wait for focus before reading it.
    LaunchedEffect(Unit) {
        if (prefilled) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        prefilled = true
        val clip = withContext(Dispatchers.IO) { readClipboardText(context) }
        // Typing may have begun while the page waited for focus, and the clipboard never replaces it.
        if (!clip.isNullOrEmpty() && field.text.isEmpty()) field.setTextAndPlaceCursorAtEnd(clip)
    }

    // The text and the format are read together, so a code is never made from one with the other
    // out of date; a code still being made is dropped once either moves on.
    LaunchedEffect(Unit) {
        snapshotFlow { field.text.toString() to type }.collectLatest { (text, wanted) ->
            if (text.isEmpty()) {
                made = null
                return@collectLatest
            }
            if (text.length > LARGE_TEXT) delay(LARGE_TEXT_DELAY_MS)
            made = withContext(Dispatchers.Default) { Made(text, wanted, CodeGenerator.generate(text, wanted)) }
        }
    }

    val clearButton: @Composable () -> Unit = {
        IconButton(onClick = { haptics.tap(); field.clearText() }, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.generate_clear))
        }
    }

    if (picking) {
        FormatPickerSheet(
            text = field.text.toString(),
            selected = chosen,
            original = null,
            onPick = { picked -> choose(picked.name) },
            onDismiss = { picking = false },
        )
    }

    // How far the snackbar rises above the bottom of the page so it stays clear of the toolbar and
    // the text field, which the layout below works out as it places them.
    val snackbarLift = remember { mutableIntStateOf(0) }
    Scaffold(
        // The scaffold lifts the snackbar only above the system bars, and the keyboard would cover it.
        snackbarHost = {
            SnackbarHost(
                snackbar,
                Modifier
                    .windowInsetsPadding(WindowInsets.ime.exclude(ScaffoldDefaults.contentWindowInsets))
                    .offset { IntOffset(0, -snackbarLift.intValue) },
            )
        },
        topBar = {
            // The page never scrolls, so a collapsing app bar would only take space away from the code.
            TopAppBar(
                title = { Text(stringResource(R.string.generate_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }, shapes = IconButtonDefaults.shapes()) {
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
            // Turned sideways, the screen has too little height for the code above the controls, so the
            // code takes one half of the width, as tall as the screen lets it be, and the controls the other.
            val sideBySide = maxWidth > maxHeight
            val toolbarRoom = if (sideBySide) (maxWidth - GAP) / 2 else maxWidth

            // What is done with the code sits in one toolbar under it: actions on the code itself,
            // then the apps that can open the text, with sharing as the main way out.
            val sendApp: (NamedFerry<Ferry>) -> SendApp = { (ferry, name) ->
                // Without the module the app could only open its scanner on nothing, so the
                // button looks disabled but still explains why when tapped.
                val looksEnabled = ferry.isReady(ready)
                SendApp(context.getString(R.string.open_in_app, name), ferry.pkg, looksEnabled) {
                    when {
                        activity == null -> Unit
                        !looksEnabled -> {
                            haptics.reject()
                            scope.launch { snackbar.showLatest(context.getString(R.string.needs_module_in_app, name)) }
                        }
                        else -> {
                            haptics.tap()
                            val opened = ferry.open(activity, field.text.toString())
                            if (!opened) scope.launch { snackbar.showLatest(context.getString(R.string.no_app)) }
                        }
                    }
                }
            }

            // The code opens over the whole window when tapped, as large as the window lets it be.
            var enlarged by rememberSaveable { mutableStateOf(false) }
            var codeBounds by remember { mutableStateOf<Rect?>(null) }
            val brighten by settings.fullBrightness.flow.collectAsStateWithLifecycle()

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
            val codeFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val codeArea: @Composable () -> Unit = {
                val current = made
                val code = (current?.generated as? Generated.Code)?.bitmap
                // The format of the code shown, which the format chosen may have moved on from.
                val long = current?.type?.long == true
                // A long code is drawn again at the shape it is shown in, so its bars fill it without a
                // scaled picture, from the same text and format as the code it replaces.
                val drawTall: ((Float) -> Bitmap?)? = if (current != null && long) {
                    { ratio -> (CodeGenerator.generate(current.text, current.type, ratio) as? Generated.Code)?.bitmap }
                } else {
                    null
                }
                if (enlarged && code != null) {
                    CodeOverlay(code, brighten, codeBounds, onDismiss = { enlarged = false }, drawTall = drawTall, long = long)
                }
                Box(
                    modifier = Modifier.fillMaxSize().then(
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
                    val look = when {
                        code == null || !long -> CodeLook.SQUARE
                        enlarge == LongCodeEnlarge.TILT -> CodeLook.TILTED
                        else -> CodeLook.STRETCHED
                    }
                    // A code drawn another way crossfades into the new one; drawn the same way, it is
                    // simply drawn again.
                    AnimatedContent(
                        targetState = ShownCode(look, code),
                        contentKey = { it.look },
                        transitionSpec = { fadeIn(codeFade) togetherWith fadeOut(codeFade) },
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                        label = "code",
                    ) { shown ->
                        // Where the code itself is drawn, which the enlarged code grows out of.
                        val bounds = Modifier.onGloballyPositioned { codeBounds = it.boundsInWindow() }
                        val bitmap = shown.code
                        // The transition hands its content the area's exact size; inside a box the
                        // square preview is free to be smaller, as large as the shorter side.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            when {
                                shown.look == CodeLook.TILTED && bitmap != null -> TiltedCode(bitmap, bounds.fillMaxSize())
                                shown.look == CodeLook.STRETCHED && bitmap != null ->
                                    FilledTallCode(bitmap, drawTall, bounds.fillMaxSize())
                                else -> CodePreview(bitmap, bounds.aspectRatio(1f, matchHeightConstraintsFirst = true))
                            }
                        }
                    }
                }
            }
            val appsMotion: AnimatedContentTransitionScope<*>.() -> ContentTransform = {
                (fadeIn(appFade) + expandHorizontally(appResize)) togetherWith
                    (fadeOut(appFade) + shrinkHorizontally(appResize)) using null
            }
            val toolbar: @Composable () -> Unit = {
                val code = (made?.generated as? Generated.Code)?.bitmap
                // Worked out again only when the offered apps or their room change, not for each new code.
                val layout = remember(offered, toolbarRoom) { if (offered.isEmpty()) null else sendLayout(offered, toolbarRoom) }
                CodeToolbar(
                    code = code,
                    onMessage = { message -> scope.launch { snackbar.showLatest(context.getString(message)) } },
                    favorite = {
                        // Kept codes are shown again from history, drawn the same way as here. Unstarred,
                        // the toggle takes the toolbar's own content colour, like save and copy beside it;
                        // starred, it fills like the favorite on result cards. Only the toggle reads the
                        // text, so a keystroke composes it again and leaves the rest of the toolbar be.
                        Labeled(R.string.favorite) {
                            FavoriteToggle(
                                field.text.toString(),
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
                    // While the whole group of apps fades out, it keeps showing the apps it held, each in
                    // its place, so the group shrinks as one instead of its apps shrinking inside it at
                    // the same time, two animations that drift apart and jerk when the group comes back.
                    AnimatedContent(
                        targetState = layout,
                        contentKey = { it != null },
                        transitionSpec = appsMotion,
                        contentAlignment = Alignment.CenterStart,
                        label = "apps",
                    ) { shown ->
                        if (shown != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(GROUP_GAP))
                            // Folding apps away or bringing them back resizes the pill a button at a
                            // time, the same way as when an app appears for the text.
                            AppGroup {
                                val enabled = hasText
                                for (app in candidates.filterNot { it.ferry.general }) {
                                    AppSlot(shown.inline.any { it.ferry == app.ferry }, sendApp(app), enabled, appFade, appResize)
                                }
                                // The app the system opens the text in has a place of its own, between the
                                // apps for their own codes and the general ones, and keeps it while it fades out.
                                AnimatedContent(
                                    targetState = shown.passive?.takeIf { passive -> shown.inline.any { it.ferry == passive.ferry } },
                                    contentKey = { it?.ferry?.pkg },
                                    transitionSpec = appsMotion,
                                    contentAlignment = Alignment.Center,
                                    label = "passive-app",
                                ) { app ->
                                    if (app != null) AppButton(sendApp(app), enabled)
                                }
                                for (app in candidates.filter { it.ferry.general }) {
                                    AppSlot(shown.inline.any { it.ferry == app.ferry }, sendApp(app), enabled, appFade, appResize)
                                }
                                AnimatedVisibility(
                                    visible = shown.folded.isNotEmpty(),
                                    enter = fadeIn(appFade) + expandHorizontally(appResize),
                                    exit = fadeOut(appFade) + shrinkHorizontally(appResize),
                                ) {
                                    SendMenu(shown.folded.map(sendApp), enabled = enabled)
                                }
                            }
                        }
                    }
                }
            }
            val textField: @Composable (lines: Int) -> Unit = { lines ->
                val error = unfitMessage(unfit, made?.type ?: type)
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
                    trailingIcon = if (hasText) clearButton else null,
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = lines, maxHeightInLines = lines),
                )
            }
            // The text field one line tall, with the same supporting text, measured but never shown,
            // which tells how much room the field takes at the current font size.
            val probeState = remember { TextFieldState() }
            val fieldProbe: @Composable () -> Unit = {
                val error = unfitMessage(unfit, made?.type ?: type)
                OutlinedTextField(
                    state = probeState,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.generate_input)) },
                    isError = error != null,
                    supportingText = { Text(error.orEmpty()) },
                    trailingIcon = if (hasText) clearButton else null,
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 1),
                )
            }
            GeneratorLayout(
                sideBySide = sideBySide,
                gap = GAP,
                chips = chips,
                code = codeArea,
                toolbar = toolbar,
                field = textField,
                fieldProbe = fieldProbe,
                onSnackbarLift = { snackbarLift.intValue = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** What was made from [text] in [type], kept with both so a long code is drawn again from the same. */
private class Made(val text: String, val type: CodeType, val generated: Generated)

/**
 * Why the text does not fit [type], naming the common formats that would hold it, or null when
 * nothing is [unfit].
 */
@Composable
private fun unfitMessage(unfit: Generated.Unfit?, type: CodeType): String? {
    if (unfit == null) return null
    val typeLabel = stringResource(type.label)
    val separator = stringResource(R.string.list_separator)
    val alternatives = unfit.alternatives.map { stringResource(it.label) }.joinToString(separator)
    return when (val problem = unfit.problem) {
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

/** How the create page's preview draws a code. */
private enum class CodeLook { SQUARE, TILTED, STRETCHED }

/** The code the preview shows, null before one is made, and how it is drawn. */
private data class ShownCode(val look: CodeLook, val code: Bitmap?)

/** The code always sits on white, whatever the theme, because readers expect dark modules on light. */
@Composable
private fun CodePreview(code: Bitmap?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = code,
            contentKey = { it != null },
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().padding(20.dp),
            label = "code-preview",
        ) { code ->
            if (code != null) {
                val image = remember(code) { code.asImageBitmap() }
                // Inside a box, so the white card takes the code's own shape rather than the area's size.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(shape = MaterialTheme.shapes.large, color = Color.White) {
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

/**
 * The text on the clipboard, up to [CLIP_MAX_CHARS]. Some apps put text on the clipboard as a
 * content URI, which `coerceToText` would read to its end however large it is, so the URI is read
 * here only as far as the field can take. It asks the clipboard and possibly another app's
 * provider, so it runs off the main thread.
 */
private fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    if (clipboard.primaryClipDescription?.hasMimeType("text/*") != true) return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val item = clip.getItemAt(0)
    item.text?.let { return it.take(CLIP_MAX_CHARS).toString() }
    item.uri?.let { uri ->
        // A URI that cannot be read as text stands for itself, unless it points into this device,
        // as coerceToText treats it.
        return readText(context, uri) ?: uri.toString().take(CLIP_MAX_CHARS).takeIf { uri.scheme !in LOCAL_SCHEMES }
    }
    return item.intent?.toUri(Intent.URI_INTENT_SCHEME)?.take(CLIP_MAX_CHARS)
}

/** URI schemes that name something on this device rather than a link worth showing as text. */
private val LOCAL_SCHEMES = setOf(ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_ANDROID_RESOURCE, ContentResolver.SCHEME_FILE)

/** The first [CLIP_MAX_CHARS] of the text behind [uri], in UTF-8 as the clipboard reads it, or null when it cannot be read. */
private fun readText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openTypedAssetFileDescriptor(uri, "text/*", null)?.use { descriptor ->
        descriptor.createInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(CLIP_MAX_CHARS)
            var read = 0
            while (read < buffer.size) {
                val count = reader.read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
            String(buffer, 0, read)
        }
    }
} catch (e: Exception) {
    // A provider may refuse, go away or fail midway, and the clip then counts as unreadable.
    null
}
