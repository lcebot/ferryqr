@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.CardAction
import io.github.lcebot.ferryqr.dispatch.Ferry
import io.github.lcebot.ferryqr.dispatch.PassiveFerry

/**
 * The toolbar's fixed parts, from the floating toolbar's own tokens: its padding, one icon button,
 * the gap before the group of apps, and the gap before the FAB together with the FAB itself.
 */
private val TOOLBAR_PADDING = 16.dp
private val TOOLBAR_BUTTON = 48.dp
internal val GROUP_GAP = 4.dp
private val TOOLBAR_FAB = TOOLBAR_TO_FAB_GAP + SHARE_FAB_SIZE

/** Copy, save and favorite, which always stay in the toolbar. */
private const val CODE_ACTIONS = 3

/** How much of a disabled control shows, as the tokens dim disabled content. */
private const val DISABLED_ALPHA = 0.38f

/**
 * The [action] that hands content to an app, as a toolbar or a menu shows it: by the app's icon and
 * [label]. A blocked action is drawn disabled, though a tap on it still says why.
 */
internal class SendApp(val action: CardAction, val label: String) {
    val looksEnabled: Boolean get() = action.blocked == null
}

/**
 * An app with the [action] that hands content to it, named and labelled for how the app takes the
 * content. Naming an app the system finds and telling how it takes the content ask the package
 * manager, so the action is made off the main thread together with the app and travels with it from there.
 */
internal data class NamedFerry<out F : Ferry>(val ferry: F, val action: CardAction)

/**
 * The apps shown in the toolbar, and those folded behind its overflow button. [passive] is the app
 * the system opens the text in, which has a place of its own among them.
 */
internal class SendLayout(
    val inline: Set<NamedFerry<Ferry>>,
    val folded: List<NamedFerry<Ferry>>,
    val passive: NamedFerry<Ferry>?,
)

/**
 * Which of the [offered] apps fit a toolbar [room] wide. All of them when they fit; otherwise the
 * first, the one the text goes to best, stays in view beside the overflow button, which the
 * narrowest phone screen Android allows still holds, and the others fold away.
 */
internal fun sendLayout(offered: List<NamedFerry<Ferry>>, room: Dp): SendLayout {
    fun fits(groupButtons: Int) =
        TOOLBAR_PADDING + TOOLBAR_BUTTON * (CODE_ACTIONS + groupButtons) + GROUP_GAP + TOOLBAR_FAB <= room
    val first = offered.firstOrNull()
    val passive = offered.firstOrNull { it.ferry is PassiveFerry }
    return when {
        fits(offered.size) -> SendLayout(offered.toSet(), emptyList(), passive)
        first != null && fits(2) -> SendLayout(setOf(first), offered - first, passive)
        else -> SendLayout(emptySet(), offered, passive)
    }
}

/** An app's place in the toolbar, where its button fades and resizes in and out as it is offered. */
@Composable
internal fun RowScope.AppSlot(
    visible: Boolean,
    app: SendApp,
    enabled: Boolean,
    onAction: (CardAction) -> Unit,
    fade: FiniteAnimationSpec<Float>,
    resize: FiniteAnimationSpec<IntSize>,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(fade) + expandHorizontally(resize),
        exit = fadeOut(fade) + shrinkHorizontally(resize),
    ) {
        AppButton(app, enabled = enabled, onAction = onAction)
    }
}

/** The apps share one pill, which sets them apart from the actions on the code without a divider. */
@Composable
internal fun AppGroup(content: @Composable RowScope.() -> Unit) {
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
internal fun SendMenu(apps: List<SendApp>, enabled: Boolean, onAction: (CardAction) -> Unit) {
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
        AppMenu(expanded = open, onDismiss = { open = false }, apps = apps, onAction = onAction, focusable = false)
    }
}

/**
 * A menu of [apps], each by its icon and label, at the element it is placed beside, which closes as
 * one is picked and hands its action to [onAction]. A [focusable] menu takes focus, so back closes it;
 * one that is not leaves focus where it is, such as with a text field whose keyboard is to stay.
 */
@Composable
internal fun AppMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    apps: List<SendApp>,
    onAction: (CardAction) -> Unit,
    focusable: Boolean,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable),
    ) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
            apps.forEachIndexed { index, app ->
                val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                DropdownMenuItem(
                    onClick = {
                        onDismiss()
                        onAction(app.action)
                    },
                    text = { Text(app.label) },
                    shape = MenuDefaults.itemShape(index, apps.size).shape,
                    leadingIcon = {
                        ActionIcon(app.action, Modifier.size(MenuDefaults.LeadingIconSize).alpha(if (app.looksEnabled) 1f else DISABLED_ALPHA))
                    },
                    colors = if (app.looksEnabled) MenuDefaults.itemColors() else MenuDefaults.itemColors(textColor = dim),
                )
            }
        }
    }
}

/**
 * Hands the text to another app through [onAction], shown by that app's own icon, which a disabled
 * or blocked app dims once. A glyph drawn in the content colour is dimmed by the button's colours:
 * the disabled one, or for a blocked app, whose button stays enabled so a tap can say why, a content
 * colour as dim as that. A full-colour glyph takes no colour, so it is dimmed by its alpha instead.
 */
@Composable
internal fun AppButton(app: SendApp, enabled: Boolean, onAction: (CardAction) -> Unit) {
    val content = LocalContentColor.current
    val color by animateColorAsState(
        if (app.looksEnabled) content else content.copy(alpha = DISABLED_ALPHA),
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "app-button-color",
    )
    val imageAlpha by animateFloatAsState(
        if (enabled && app.looksEnabled) 1f else DISABLED_ALPHA,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "app-button",
    )
    val description = app.label
    Labeled(app.label) {
        IconButton(
            onClick = { onAction(app.action) },
            enabled = enabled,
            shapes = IconButtonDefaults.shapes(),
            // The disabled colour takes the alpha of the tokens whatever the content colour's own,
            // so a blocked app's glyph is not dimmed again once the button is disabled too.
            colors = IconButtonDefaults.iconButtonColors(contentColor = color),
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            ActionIcon(app.action, Modifier.size(24.dp), fullColor = Modifier.graphicsLayer { alpha = imageAlpha })
        }
    }
}
