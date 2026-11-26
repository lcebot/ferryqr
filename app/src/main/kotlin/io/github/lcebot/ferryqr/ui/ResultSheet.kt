// rememberBottomSheetState is an experimental Material 3 API; combinedClickable is stable and needs
// no opt-in.
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import android.widget.Toast
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.LongCodeEnlarge
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.CardAction
import io.github.lcebot.ferryqr.dispatch.CardSection
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.generate.CodeGenerator
import io.github.lcebot.ferryqr.generate.CodeRules
import io.github.lcebot.ferryqr.generate.CodeType
import io.github.lcebot.ferryqr.scan.MINI_PROGRAM_FORMAT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** With [closing] set, the sheet slides away as when dismissed, and then [onDismiss] follows. */
@Composable
fun ResultSheet(card: ResultCard, onDismiss: () -> Unit, onAction: (CardAction) -> Unit, closing: Boolean = false) {
    val state = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        state.hide()
        dismiss()
    }
    ModalSheet(onDismissRequest = onDismiss, sheetState = state) {
        ResultCardContent(
            card = card,
            onAction = onAction,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        )
    }
}

/**
 * The body of a result card, shared by the scanner's sheet and the image sheet. A code drawn again
 * in history can be drawn in another format for the reader at hand, for as long as the card is open;
 * the entry keeps the format it was read in.
 */
@Composable
fun ResultCardContent(card: ResultCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val original = remember(card.format) { CodeType.ofReaderName(card.format) }
    // Keyed on the content rather than the card, which is built again whenever an app's readiness
    // changes, so the format picked stays until another code is shown.
    var shown by rememberSaveable(card.raw, card.format) { mutableStateOf<CodeType?>(null) }
    var picking by remember { mutableStateOf(false) }
    // A mini program code means something only as one; its content drawn in another format would not.
    val switchable = card.exportable && card.raw.isNotEmpty() && card.format.isNotEmpty() && card.format != MINI_PROGRAM_FORMAT
    // Drawn off the main thread; the code shown stays until the new drawing is ready.
    val redrawn by produceState<Bitmap?>(null, card.raw, shown) {
        value = shown?.let { type -> withContext(Dispatchers.Default) { CodeGenerator.draw(card.raw, type) } }
    }
    val image = if (shown == null) card.image else redrawn ?: card.image
    // What the code is drawn as: the picked format, else the original when the content fits it, as
    // history redraws it, else the QR code it falls back to.
    val displayed = shown ?: remember(card.raw, original) {
        original?.takeIf { CodeRules.problem(card.raw, it) == null } ?: CodeType.QR
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(card.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(card.kind),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            // Only read content can be kept; a picture of an unread code has nothing to store.
            if (card.raw.isNotEmpty() && card.format.isNotEmpty()) {
                FavoriteToggle(card.raw, card.format, Modifier.offset(x = 12.dp))
            }
        }
        image?.let { bitmap ->
            CodeImage(
                bitmap = bitmap,
                long = displayed.long && bitmap.width > bitmap.height,
                drawTall = if (switchable) {
                    { ratio -> CodeGenerator.draw(card.raw, displayed, ratio) }
                } else {
                    null
                },
                onChangeFormat = if (switchable) {
                    { picking = true }
                } else {
                    null
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            // In history the original is out of sight, so the drawing can be kept or passed on.
            if (card.exportable) {
                val context = LocalContext.current
                CodeToolbar(bitmap, onMessage = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() })
            }
        }
        if (card.headline.isNotEmpty()) {
            SelectionContainer {
                Text(
                    card.headline,
                    style = if (card.headline.length > 60) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.headlineSmall,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        card.body?.let { body ->
            SelectionContainer {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        card.sections.forEach { CardSectionView(it) }
        card.notes.forEach { note ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        CardActions(card.actions, onAction, Modifier.padding(top = 4.dp))
    }
    if (picking) {
        FormatPickerSheet(
            text = card.raw,
            selected = displayed,
            original = original,
            onPick = { type ->
                shown = type.takeUnless { it == original }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun CardSectionView(section: CardSection) {
    val colors = MaterialTheme.colorScheme
    when (section) {
        is CardSection.Route -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            section.label?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = colors.primary) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(section.from, style = MaterialTheme.typography.displaySmall)
                Icon(
                    painterResource(R.drawable.ic_flight),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    // The glyph points up; along the route it points from the first airport to the second.
                    modifier = Modifier.size(28.dp).rotate(90f),
                )
                Text(section.to, style = MaterialTheme.typography.displaySmall)
            }
            if (section.caption.isNotEmpty()) {
                Text(section.caption, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
            }
        }
        is CardSection.Fields -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            section.rows.chunked(2).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    line.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                    if (line.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        is CardSection.Raw -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.card_raw), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            SelectionContainer {
                Text(
                    section.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceContainerHighest)
                        .padding(12.dp),
                )
            }
        }
    }
}

/**
 * The card's actions under its content. In reading order, so the main way out is met first, the
 * primary action as a filled button, then whatever else opens the content elsewhere, such as the
 * browser or other apps, as tonal buttons that keep their labels, since one app can be offered in
 * more than one way. Actions that would look alike as icons keep their labels the same way. Round
 * icon buttons for what can be done with the content itself, each named
 * in a tooltip and to screen readers, end the same line when it holds them all; otherwise they
 * take a line of their own above it.
 */
@Composable
private fun CardActions(actions: List<CardAction>, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val primary = actions.firstOrNull() ?: return
    val (labeled, tools) = actions.drop(1).partition { it.labeled }
    ActionLines(
        modifier = modifier,
        tools = { tools.forEach { action -> ToolButton(action, onAction) } },
        main = {
            Button(onClick = { onAction(primary) }, shapes = ButtonDefaults.shapes()) {
                ActionIcon(primary, ButtonDefaults.IconSize)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(labelOf(primary))
            }
            labeled.forEach { action ->
                FilledTonalButton(onClick = { onAction(action) }, shapes = ButtonDefaults.shapes()) {
                    ActionIcon(action, ButtonDefaults.IconSize)
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(labelOf(action))
                }
            }
        },
    )
}

private val ACTION_GAP = 8.dp
private val ACTION_LINE_GAP = 12.dp

/**
 * [main] and [tools] on one line, [tools] at its end, when the width holds both. Otherwise [tools]
 * go on a line above [main], and each wraps onto further lines when it is too long by itself, as
 * on a narrow screen or with large text. Items on a line are centred on each other.
 */
@Composable
private fun ActionLines(main: @Composable () -> Unit, tools: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(contents = listOf(main, tools), modifier = modifier.fillMaxWidth()) { (mainItems, toolItems), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val mains = mainItems.map { it.measure(loose) }
        val tooling = toolItems.map { it.measure(loose) }
        val gap = ACTION_GAP.roundToPx()
        fun span(items: List<Placeable>) = items.sumOf { it.width } + gap * (items.size - 1).coerceAtLeast(0)
        val together = span(mains) + if (tooling.isEmpty()) 0 else gap + span(tooling)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else together

        // Each placement is an item with its position, worked out before the layout's size is known.
        val placements = mutableListOf<Triple<Placeable, Int, Int>>()

        /** Places [items] in lines starting at [top], and returns the height used. */
        fun flow(items: List<Placeable>, top: Int): Int {
            var y = top
            var line = mutableListOf<Placeable>()
            fun close() {
                val height = line.maxOfOrNull { it.height } ?: 0
                var x = 0
                for (item in line) {
                    placements += Triple(item, x, y + (height - item.height) / 2)
                    x += item.width + gap
                }
                y += height + gap
                line = mutableListOf()
            }
            for (item in items) {
                if (line.isNotEmpty() && span(line) + gap + item.width > width) close()
                line += item
            }
            if (line.isNotEmpty()) close()
            return (y - top - gap).coerceAtLeast(0)
        }

        val height = if (together <= width) {
            val lineHeight = (mains + tooling).maxOfOrNull { it.height } ?: 0
            var x = 0
            for (item in mains) {
                placements += Triple(item, x, (lineHeight - item.height) / 2)
                x += item.width + gap
            }
            x = width - span(tooling)
            for (item in tooling) {
                placements += Triple(item, x, (lineHeight - item.height) / 2)
                x += item.width + gap
            }
            lineHeight
        } else {
            val toolsHeight = flow(tooling, 0)
            val top = if (tooling.isEmpty()) 0 else toolsHeight + ACTION_LINE_GAP.roundToPx()
            top + flow(mains, top)
        }
        layout(width, height) {
            for ((item, x, y) in placements) item.placeRelative(x, y)
        }
    }
}

@Composable
private fun ToolButton(action: CardAction, onAction: (CardAction) -> Unit) {
    val label = labelOf(action)
    Labeled(label) {
        FilledTonalIconButton(
            onClick = { onAction(action) },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            ActionIcon(action, ACTION_ICON_SIZE)
        }
    }
}

private val ACTION_ICON_SIZE = 24.dp

@Composable
private fun labelOf(action: CardAction): String = when (val app = action.appName) {
    null -> stringResource(action.label)
    else -> stringResource(action.label, app)
}

@Composable
private fun ActionIcon(action: CardAction, size: Dp) {
    val app = action.app
    if (app != null) AppIcon(app, action.icon, Modifier.size(size))
    else Icon(painterResource(action.icon), contentDescription = null, modifier = Modifier.size(size))
}

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
 * The code on white, since readers expect dark modules on light. A tap grows it as large as the
 * sheet allows, so another phone can scan it from a distance, and a second tap returns it. The room
 * is measured from the window as it is, so a turned phone or a resized window never lets the code
 * outgrow the screen. A [long] code, a barcode or a PDF417, is tilted along the diagonal,
 * stretched taller, or shown level over the whole window, as the settings choose, and a tilted one
 * stays level when the area is wide anyway. [onChangeFormat], when given, is a long press.
 * [drawTall] draws the code again as tall as a stretched code has to be for every unit of width,
 * so it grows taller bars rather than a scaled picture; without it the picture is scaled.
 */
@Composable
private fun CodeImage(
    bitmap: Bitmap,
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
    val image = remember(bitmap) { bitmap.asImageBitmap() }
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
            val area = Modifier
                .size(width, height)
                .onGloballyPositioned { place.coordinates = it }
            if (tilted) {
                // The card turns inside its area, so a ripple on that area would not match its outline.
                TiltedCode(
                    bitmap,
                    area.combinedClickable(
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
                    modifier = area
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .combinedClickable(
                            onClickLabel = clickLabel,
                            onLongClickLabel = if (longPress != null) longClickLabel else null,
                            onLongClick = longPress,
                            onClick = ::tap,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val shown = remember(tall) { tall?.asImageBitmap() } ?: image
                    Image(
                        bitmap = shown,
                        contentDescription = null,
                        // Drawn at its new height, the stretched code fills the area exactly once it is there,
                        // and its bars only grow or shrink in height on the way.
                        contentScale = if (stretched) ContentScale.FillBounds else ContentScale.Fit,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val resize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
            AnimatedContent(
                targetState = enlarged,
                transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)) using SizeTransform { _, _ -> resize } },
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
