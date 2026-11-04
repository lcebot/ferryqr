@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.history.HistoryStore
import kotlinx.coroutines.flow.first

/** Stars content as a favorite, adding it to the history first when it is not there yet. */
@Composable
fun FavoriteToggle(text: String, format: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val store = remember { HistoryStore.get(context) }
    val entries by store.entries.collectAsStateWithLifecycle()
    val favorite = entries.any { it.text == text && it.favorite }
    IconToggleButton(
        checked = favorite,
        onCheckedChange = { on ->
            haptics.toggle(on)
            store.setFavorite(text, format, on)
        },
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = modifier,
    ) {
        FavoriteIcon(favorite, stringResource(R.string.favorite))
    }
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
 * Names a favorite. The field starts focused with its text selected, so typing replaces the
 * current name at once; a blank name returns the entry to showing its content.
 */
@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val haptics = rememberHaptics()
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(current, TextRange(0, current.length)))
    }
    val focus = remember { FocusRequester() }
    fun confirm() {
        haptics.confirm()
        onRename(value.text)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            // The dialog has a window of its own, and the keyboard only opens for a focused window.
            val window = LocalWindowInfo.current
            LaunchedEffect(Unit) {
                snapshotFlow { window.isWindowFocused }.first { it }
                focus.requestFocus()
            }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.rename_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { haptics.tap(); onDismiss() }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
