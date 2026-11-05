@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.CardAction
import io.github.lcebot.ferryqr.dispatch.ResultCard

@Composable
fun ResultSheet(card: ResultCard, onDismiss: () -> Unit, onAction: (CardAction) -> Unit) {
    val state = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
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

/** The body of a result card, shared by the scanner's sheet and the image sheet. */
@Composable
fun ResultCardContent(card: ResultCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
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
        card.image?.let { bitmap ->
            CodeImage(bitmap, tilt = card.format == PDF417_FORMAT, modifier = Modifier.align(Alignment.CenterHorizontally))
            // In history the original is out of sight, so the drawing can be kept or passed on.
            if (card.exportable) {
                val context = LocalContext.current
                CodeToolbar(bitmap, onMessage = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() })
            }
        }
        SelectionContainer {
            Text(
                card.headline,
                style = if (card.headline.length > 60) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.headlineSmall,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
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
}

/**
 * The card's actions under its content. In reading order, so the main way out is met first, the
 * primary action as a filled button, then whatever else opens the content elsewhere, such as the
 * browser or other apps, as tonal buttons that keep their labels, since one app can be offered in
 * more than one way. Round icon buttons for what can be done with the content itself, each named
 * in a tooltip and to screen readers, end the same line when it holds them all; otherwise they
 * take a line of their own above it.
 */
@Composable
private fun CardActions(actions: List<CardAction>, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val primary = actions.firstOrNull() ?: return
    val (opens, tools) = actions.drop(1).partition { it.opens }
    ActionLines(
        modifier = modifier,
        tools = { tools.forEach { action -> ToolButton(action, onAction) } },
        main = {
            Button(onClick = { onAction(primary) }, shapes = ButtonDefaults.shapes()) {
                ActionIcon(primary, ButtonDefaults.IconSize)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(labelOf(primary))
            }
            opens.forEach { action ->
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
    else -> stringResource(action.label, stringResource(app))
}

@Composable
private fun ActionIcon(action: CardAction, size: Dp) {
    val app = action.app
    if (app != null) AppIcon(app, action.icon, Modifier.size(size))
    else Icon(painterResource(action.icon), contentDescription = null, modifier = Modifier.size(size))
}

/** The code's height while small; a wide linear code grows sideways from it. */
private val CODE_HEIGHT = 160.dp

/** zxing-cpp's name for PDF417, the one format that is enlarged by turning it. */
private const val PDF417_FORMAT = "PDF_417"

/** The white margin [TiltedCode] keeps around the code, so the small card shows it at full width. */
private val TILTED_FRAME = 16.dp

/**
 * The code on white, since readers expect dark modules on light. A tap grows it to the sheet's full
 * width so another phone can scan it from a distance, and a second tap returns it. A PDF417 with
 * [tilt] grows into a square instead and turns as it grows, the same way the create page shows it.
 */
@Composable
private fun CodeImage(bitmap: Bitmap, tilt: Boolean, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val aspect = bitmap.width.toFloat() / bitmap.height
    var enlarged by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val small = minOf(CODE_HEIGHT * maxOf(aspect, 1f), maxWidth)
        val tilted = tilt && aspect > 1f
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
        val width by animateDpAsState(if (enlarged) maxWidth else small, spatial, label = "code-width")
        val height by animateDpAsState(
            targetValue = when {
                !tilted -> (if (enlarged) maxWidth else small) / aspect
                enlarged -> maxWidth
                else -> small / aspect + TILTED_FRAME
            },
            animationSpec = spatial,
            label = "code-height",
        )
        val clickLabel = stringResource(if (enlarged) R.string.code_shrink else R.string.code_enlarge)
        fun toggle() {
            haptics.tap()
            enlarged = !enlarged
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tilted) {
                // The card turns inside its area, so a ripple on that area would not match its outline.
                TiltedCode(
                    bitmap,
                    Modifier
                        .size(width, height)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = clickLabel,
                            onClick = ::toggle,
                        ),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width, height)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .clickable(onClickLabel = clickLabel, onClick = ::toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = enlarged,
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                label = "code-hint",
            ) { big ->
                Text(
                    stringResource(if (big) R.string.code_tap_shrink else R.string.code_tap_enlarge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
