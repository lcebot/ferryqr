@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.content.WebPayload
import io.github.lcebot.ferryqr.dispatch.Cards
import io.github.lcebot.ferryqr.dispatch.Titles
import io.github.lcebot.ferryqr.history.HistoryEntry
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
    val favorite = entries.any { it.text == text && it.favorite }
    var naming by remember { mutableStateOf(false) }
    IconToggleButton(
        checked = favorite,
        onCheckedChange = { on ->
            val label = entries.find { it.text == text }?.label
            if (on && AppSettings.namesFavorites(context) && !hasTitle(context, text, format, label)) {
                haptics.tap()
                naming = true
            } else {
                haptics.toggle(on)
                store.setFavorite(text, format, on)
            }
        },
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
    ) {
        FavoriteIcon(favorite, stringResource(R.string.favorite))
    }
    if (naming) {
        NameFavoriteDialog(
            current = entries.find { it.text == text }?.label ?: defaultTitle(context, text, format),
            onDismiss = { naming = false },
            onName = { name ->
                store.setFavorite(text, format, true, name)
                naming = false
            },
        )
    }
}

/**
 * Whether content shows under a title rather than as itself: a name the user gave it, or one its
 * kind gives it, as a boarding pass is shown by its flight. Such content is never asked for a name
 * as it becomes a favorite.
 */
internal fun hasTitle(context: Context, text: String, format: String, label: String?): Boolean =
    label != null || Titles.of(context, Classifier.classify(text, format)) != null

/**
 * What a favorite tile shows: what history shows any entry as, its name or else the title its kind
 * gives it or else its content, except that a link without either shows only its host, which keeps
 * the tile short.
 */
internal fun tileTitle(context: Context, entry: HistoryEntry): String {
    entry.label?.let { return it }
    val payload = Classifier.classify(entry.text, entry.format)
    return Titles.of(context, payload) ?: (payload as? WebPayload)?.host ?: payload.raw
}

/** What a name starts from until the user gives one: the same short title a shortcut shows. */
internal fun defaultTitle(context: Context, text: String, format: String): String {
    val payload = Classifier.classify(text, format)
    return Cards.titleOf(context, payload) ?: context.getString(Cards.kindOf(payload).label)
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
