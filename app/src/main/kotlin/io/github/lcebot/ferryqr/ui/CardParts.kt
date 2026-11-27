@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.CardAction

/**
 * The card's notes, the column's gap apart. A note that comes with a card built again
 * fades in while the notes make room for it, and one that leaves fades out before they close up.
 * The notes the card opens with are there from the start.
 */
@Composable
internal fun CardNotes(notes: List<String>) {
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // Every note in sight, those still fading out included, each where it was among the rest.
    val shown = remember { notes.toMutableStateList() }
    val opening = remember { notes.toMutableSet() }
    LaunchedEffect(notes) {
        notes.forEachIndexed { index, note ->
            if (note !in shown) shown.add(index.coerceAtMost(shown.size), note)
        }
    }
    // Clipped, so a note fading in is uncovered as the notes grow rather than drawn over the actions.
    Column(
        Modifier.clipToBounds().animateSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        shown.forEach { note ->
            key(note) {
                val visible = remember { MutableTransitionState(note in opening) }
                visible.targetState = note in notes
                AnimatedVisibility(visibleState = visible, enter = fadeIn(fade), exit = fadeOut(fade)) {
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
                if (visible.isIdle && !visible.currentState) {
                    LaunchedEffect(Unit) {
                        opening -= note
                        shown -= note
                    }
                }
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
internal fun CardActions(actions: List<CardAction>, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val primary = actions.firstOrNull() ?: return
    val (labeled, tools) = actions.drop(1).partition { it.labeled }
    ActionLines(
        modifier = modifier,
        tools = { tools.forEach { action -> ToolButton(action, onAction) } },
        main = {
            Button(onClick = { onAction(primary) }, shapes = ButtonDefaults.shapes()) {
                ActionIcon(primary, ButtonDefaults.IconSize)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                ActionLabel(primary)
            }
            labeled.forEach { action ->
                FilledTonalButton(onClick = { onAction(action) }, shapes = ButtonDefaults.shapes()) {
                    ActionIcon(action, ButtonDefaults.IconSize)
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    ActionLabel(action)
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
 * on a narrow screen or with large text. Items on a line are centred on each other. The lines
 * resize on a spring when the actions change, as when a card is built again.
 */
@Composable
private fun ActionLines(main: @Composable () -> Unit, tools: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(
        contents = listOf(main, tools),
        modifier = modifier.fillMaxWidth().animateSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) { (mainItems, toolItems), constraints ->
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

/**
 * An action's label, which crossfades when a card built again names the action another way. It is
 * left unclipped, so a shorter label keeps its edges while the spring undershoots its new width.
 */
@Composable
private fun ActionLabel(action: CardAction) {
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val resize = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    AnimatedContent(
        targetState = labelOf(action),
        transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)) using SizeTransform(clip = false) { _, _ -> resize } },
        label = "action-label",
    ) { label ->
        Text(label)
    }
}

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
