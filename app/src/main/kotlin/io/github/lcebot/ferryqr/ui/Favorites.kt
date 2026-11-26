@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconToggleButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.EntryNames
import io.github.lcebot.ferryqr.history.HistoryStore
import kotlinx.coroutines.flow.first

/**
 * Stars content as a favorite, adding it to the history first when it is not there yet, and asks for
 * its name first where the settings say so and it has no title. On its own a favorite takes the
 * tertiary colours that
 * favorites have in the history; among other icon buttons, [colors] can match theirs so that only
 * the filled star tells it apart.
 */
@Composable
fun FavoriteToggle(
    text: String,
    format: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconToggleButtonColors = IconButtonDefaults.iconToggleButtonVibrantColors(
        checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ),
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val store = remember { HistoryStore.get(context) }
    val entries by store.entries.collectAsStateWithLifecycle()
    val favorite = entries?.any { it.text == text && it.favorite } == true
    var naming by remember { mutableStateOf(false) }
    // Until the history is read it is not known whether the content is a favorite, so the toggle
    // keeps its place unseen and fades in once it is, rather than showing an empty star that fills.
    val shown by animateFloatAsState(
        if (entries != null) 1f else 0f,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "favorite-toggle",
    )
    IconToggleButton(
        checked = favorite,
        onCheckedChange = { on ->
            val label = entries?.find { it.text == text }?.label
            if (on && store.asksName(text, format, label)) {
                haptics.tap()
                naming = true
            } else {
                haptics.toggle(on)
                store.setFavorite(text, format, on)
            }
        },
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = modifier.graphicsLayer { alpha = shown },
        enabled = enabled && entries != null,
        colors = colors,
    ) {
        FavoriteIcon(favorite, stringResource(R.string.favorite))
    }
    if (naming) {
        NameFavoriteDialog(
            current = EntryNames(context, text, format, entries?.find { it.text == text }?.label).shortTitle,
            onDismiss = { naming = false },
            onName = { name ->
                store.setFavorite(text, format, true, name)
                naming = false
            },
        )
    }
}

/**
 * Names content as it becomes a favorite. Saving makes it one, under the name given or showing its
 * content when left blank, and cancelling leaves it as it was.
 */
@Composable
fun NameFavoriteDialog(current: String, onDismiss: () -> Unit, onName: (String) -> Unit) {
    RenameDialog(
        current = current,
        onDismiss = onDismiss,
        onRename = onName,
        title = R.string.favorite_name_title,
        confirm = R.string.favorite,
        icon = R.drawable.ic_star,
    )
}

/** The star fills with a springy pop, so the change reads even at a glance. */
@Composable
fun FavoriteIcon(favorite: Boolean, contentDescription: String?, modifier: Modifier = Modifier) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = favorite,
        transitionSpec = { (scaleIn(spatial, initialScale = 0.6f) + fadeIn(effects)) togetherWith fadeOut(effects) },
        modifier = modifier,
        label = "favorite-icon",
    ) { on ->
        Icon(
            painterResource(if (on) R.drawable.ic_star_filled else R.drawable.ic_star),
            contentDescription = contentDescription,
        )
    }
}

/**
 * Names an entry. The field starts focused with its text selected, so typing replaces the
 * current name at once; a blank name returns the entry to showing its content. [title], [confirm]
 * and [icon] fit the dialog to naming content as it becomes a favorite.
 */
@Composable
fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    @StringRes title: Int = R.string.rename,
    @StringRes confirm: Int = R.string.save,
    @DrawableRes icon: Int = R.drawable.ic_edit,
) {
    val haptics = rememberHaptics()
    val name = rememberTextFieldState(current, initialSelection = TextRange(0, current.length))
    val focus = remember { FocusRequester() }
    fun confirm() {
        haptics.confirm()
        onRename(name.text.toString())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(icon), contentDescription = null) },
        title = { Text(stringResource(title)) },
        text = {
            // The dialog has a window of its own, and the keyboard only opens for a focused window.
            val window = LocalWindowInfo.current
            LaunchedEffect(Unit) {
                snapshotFlow { window.isWindowFocused }.first { it }
                focus.requestFocus()
            }
            OutlinedTextField(
                state = name,
                label = { Text(stringResource(R.string.rename_label)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                onKeyboardAction = { confirm() },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { haptics.tap(); onDismiss() }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
