@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.Ferry
import io.github.lcebot.ferryqr.dispatch.PassiveFerry
import io.github.lcebot.ferryqr.dispatch.Reach

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

/**
 * An app content can be opened in, called [label], with the icon of the package [app]. [looksEnabled]
 * false draws it disabled while it still explains itself.
 */
internal class SendApp(
    val label: String,
    val app: String,
    val looksEnabled: Boolean,
    val onClick: () -> Unit,
)

/**
 * An app with what it is called and how it takes the content. Naming an app the system finds and
 * telling whether it is installed ask the package manager, so both are looked up off the main thread
 * together with the app and travel with it from there.
 */
internal data class NamedFerry<out F : Ferry>(val ferry: F, val name: String, val reach: Reach)

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
internal fun SendMenu(apps: List<SendApp>, enabled: Boolean) {
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
        AppMenu(expanded = open, onDismiss = { open = false }, apps = apps, focusable = false)
    }
}

/**
 * A menu of [apps], each by its icon and name, at the element it is placed beside, which closes as
 * one is picked. A [focusable] menu takes focus, so back closes it; one that is not leaves focus where
 * it is, such as with a text field whose keyboard is to stay.
 */
@Composable
internal fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, apps: List<SendApp>, focusable: Boolean) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable),
    ) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
            apps.forEachIndexed { index, app ->
                val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                DropdownMenuItem(
                    onClick = {
                        onDismiss()
                        app.onClick()
                    },
                    text = { Text(app.label) },
                    shape = MenuDefaults.itemShape(index, apps.size).shape,
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

/** Hands the text to another app, shown by that app's own icon. */
@Composable
internal fun AppButton(app: SendApp, enabled: Boolean) {
    val alpha by animateFloatAsState(
        if (enabled && app.looksEnabled) 1f else 0.38f,
        MaterialTheme.motionScheme.fastEffectsSpec(),
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
