@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    // Clipped, so a note fading in is uncovered as the notes grow rather than drawn over the actions.
    Column(
        Modifier.clipToBounds().animateSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppearingItems(notes, keyOf = { it }, enter = fadeIn(fade), exit = fadeOut(fade)) { note, _ ->
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
    }
}

/**
 * [items] in their order, each known by [keyOf], together with those that have left and are still
 * on their way out, each where it was among the rest. An item that comes after the first
 * composition enters on [enter], and one that leaves exits on [exit] before it gives up its place;
 * the items there from the start are shown from the start. Each item is one element of the layout
 * it is emitted in, with [itemModifier]. One that is leaving shows as it last was, and [content] is
 * told it is leaving, so whatever it draws from the items still there can stay as it was too.
 */
@Composable
private fun <T> AppearingItems(
    items: List<T>,
    keyOf: (T) -> Any,
    enter: EnterTransition,
    exit: ExitTransition,
    itemModifier: Modifier = Modifier,
    content: @Composable (item: T, leaving: Boolean) -> Unit,
) {
    val current = items.associateBy(keyOf)
    // Every item in sight, those still leaving included, each where it was among the rest.
    val shown = remember { items.toMutableStateList() }
    val opening = remember { current.keys.toMutableSet() }
    LaunchedEffect(items) {
        val merged = items.toMutableList()
        shown.forEachIndexed { index, item ->
            if (keyOf(item) !in current) merged.add(index.coerceAtMost(merged.size), item)
        }
        shown.clear()
        shown.addAll(merged)
    }
    shown.forEach { item ->
        val itemKey = keyOf(item)
        key(itemKey) {
            val visible = remember { MutableTransitionState(itemKey in opening) }
            visible.targetState = itemKey in current
            AnimatedVisibility(visibleState = visible, modifier = itemModifier, enter = enter, exit = exit) {
                val present = current[itemKey]
                content(present ?: item, present == null)
            }
            if (visible.isIdle && !visible.currentState) {
                LaunchedEffect(Unit) {
                    opening -= itemKey
                    shown.removeAll { keyOf(it) == itemKey }
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
    val motion = MaterialTheme.motionScheme
    // Small parts of the card, so they move, appear and leave on the fast springs, and the lines
    // resize on the same spring as the buttons move within them, since they belong together.
    val spatial = motion.fastSpatialSpec<Rect>()
    val bounds = remember(spatial) { BoundsTransform { _, _ -> spatial } }
    val fade = motion.fastEffectsSpec<Float>()
    val scale = motion.fastSpatialSpec<Float>()
    // Scaled from near full size rather than from nothing, so the spring's bounce never turns a
    // button inside out.
    val enter = fadeIn(fade) + scaleIn(scale, initialScale = APPEARING_SCALE)
    val exit = fadeOut(fade) + scaleOut(scale, targetScale = APPEARING_SCALE)
    // A layout of its own around the scope, so the buttons move relative to the actions, and the
    // gap above them, which moves on its own spring, does not set them chasing it.
    Box(modifier.fillMaxWidth()) {
        LookaheadScope {
            val moving = Modifier.animateBounds(this, boundsTransform = bounds)
            ActionLines(
                tools = {
                    AppearingItems(tools, ::actionKey, enter, exit, moving) { action, _ -> ToolButton(action, onAction) }
                },
                main = {
                    AppearingItems(listOf(primary) + labeled, ::actionKey, enter, exit, moving) { action, leaving ->
                        ActionButton(action, primary = actionKey(action) == actionKey(primary), leaving, onAction)
                    }
                },
            )
        }
    }
}

/**
 * What a button stands for, so it keeps its own label and icon and moves to its new place when a
 * card built again offers the actions in another order. An app's action is its app's, as a card
 * offers each app once, so one the card names another way as the app becomes ready stays the same
 * button, whose label crossfades; any other action is its label's.
 */
private fun actionKey(action: CardAction): Any = action.app ?: action.label

/** How far from full size an action scales as it appears and leaves. */
private const val APPEARING_SCALE = 0.8f

/**
 * A button opening the content somewhere, filled for the [primary] action and tonal for the rest.
 * One button for both, its colours changing on the fast effects spring, so an action that becomes
 * the primary one moves to the front as itself rather than leaving one place and appearing in another.
 * A button [leaving] keeps the look it had while it was offered, so a former primary fades out filled
 * beside the one taking its place rather than turning tonal on its way out.
 */
@Composable
private fun ActionButton(action: CardAction, primary: Boolean, leaving: Boolean, onAction: (CardAction) -> Unit) {
    val offered = remember { OfferedLook(primary) }
    if (!leaving) SideEffect { offered.primary = primary }
    val filledLook = if (leaving) offered.primary else primary
    val filled = ButtonDefaults.buttonColors()
    val tonal = ButtonDefaults.filledTonalButtonColors()
    val spec = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
    val container by animateColorAsState(
        if (filledLook) filled.containerColor else tonal.containerColor,
        spec,
        label = "action-container",
    )
    val content by animateColorAsState(
        if (filledLook) filled.contentColor else tonal.contentColor,
        spec,
        label = "action-content",
    )
    ActionTarget(action, onAction) { onClick ->
        Button(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        ) {
            ActionIcon(action, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            ActionLabel(action)
        }
    }
}

/** Whether an action's button was the primary one the last time it was offered. */
private class OfferedLook(var primary: Boolean)

/**
 * The [button] of [action], given what its tap does: run the action, or, for one that several apps
 * take, open a menu of them at the button, each called by its app's name, which runs the choice
 * picked. Opening the menu runs nothing. The menu takes focus, so back closes it rather than the card.
 */
@Composable
private fun ActionTarget(action: CardAction, onAction: (CardAction) -> Unit, button: @Composable (onClick: () -> Unit) -> Unit) {
    if (action.choices.isEmpty()) {
        button { onAction(action) }
    } else {
        val haptics = rememberHaptics()
        var open by remember { mutableStateOf(false) }
        Box {
            button {
                haptics.tap()
                open = true
            }
            AppMenu(
                expanded = open,
                onDismiss = { open = false },
                apps = action.choices.map { choice -> SendApp(choice, choice.appName ?: labelOf(choice)) },
                onAction = onAction,
                focusable = true,
            )
        }
    }
}

private val ACTION_GAP = 8.dp
private val ACTION_LINE_GAP = 12.dp

/**
 * [main] and [tools] on one line, [tools] at its end, when the width holds both. Otherwise [tools]
 * go on a line above [main], and each wraps onto further lines when it is too long by itself, as
 * on a narrow screen or with large text. Items on a line are centred on each other. The lines
 * resize on the fast spatial spring the buttons move on, as when a card is built again.
 */
@Composable
private fun ActionLines(main: @Composable () -> Unit, tools: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(
        contents = listOf(main, tools),
        modifier = modifier.fillMaxWidth().animateSize(MaterialTheme.motionScheme.fastSpatialSpec()),
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
    ActionTarget(action, onAction) { onClick ->
        Labeled(label) {
            FilledTonalIconButton(
                onClick = onClick,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.semantics { contentDescription = label },
            ) {
                ActionIcon(action, Modifier.size(ACTION_ICON_SIZE))
            }
        }
    }
}

private val ACTION_ICON_SIZE = 24.dp

/** An action's label, which crossfades when a card built again names the action another way. */
@Composable
private fun ActionLabel(action: CardAction) {
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val resize = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    AnimatedContent(
        targetState = labelOf(action),
        transitionSpec = { crossfadeResizing(fade, resize) },
        label = "action-label",
    ) { label ->
        Text(label)
    }
}

/** What [action] is called, with its app's name in it for the labels that name the app. */
@Composable
internal fun labelOf(action: CardAction): String = when (val app = action.appName) {
    null -> stringResource(action.label)
    else -> stringResource(action.label, app)
}

/**
 * The icon of [action]: its app's own for an action that hands off to an app, else its own.
 * [fullColor] is added only to an app's full-colour icon, which the content colour does not reach.
 */
@Composable
internal fun ActionIcon(action: CardAction, modifier: Modifier, fullColor: Modifier = Modifier) {
    val app = action.app
    if (app != null) AppIcon(app, action.icon, modifier, fullColor)
    else Icon(painterResource(action.icon), contentDescription = null, modifier = modifier)
}
