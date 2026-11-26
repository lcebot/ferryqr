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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.lcebot.ferryqr.dispatch.ReadContent
import io.github.lcebot.ferryqr.history.FavoriteRequest
import io.github.lcebot.ferryqr.history.HistoryStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Stars content as a favorite, adding it to the history first when it is not there yet, and asks for
 * its name first where the settings say so and it has no title. On its own a favorite takes the
 * tertiary colours that favorites have in the history; among other icon buttons, [colors] can match
 * theirs so that only the filled star tells it apart.
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
    val store = remember(context) { HistoryStore.get(context) }
    val favoriting = rememberFavoriteRequester()
    // Null until the history is read, then whether the content is a favorite. Only this content's
    // entry is followed, so the rest of the history changing leaves the toggle alone; read already,
    // the history gives the answer from the first frame.
    val following = remember(store, text) { store.entryOf(text).map { it?.favorite == true } }
    val known = remember(store, text) { store.entries.value?.let { all -> all.any { it.text == text && it.favorite } } }
    // Keyed by the content, so new content starts from what history says of it rather than from
    // what the toggle showed for the content before.
    val favorite = key(text) { following.collectAsStateWithLifecycle<Boolean?>(known).value }
    // Until the history is read it is not known whether the content is a favorite, so the toggle
    // keeps its place unseen and fades in once it is, rather than showing an empty star that fills.
    val shown by animateFloatAsState(
        if (favorite != null) 1f else 0f,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "favorite-toggle",
    )
    IconToggleButton(
        checked = favorite == true,
        onCheckedChange = { on -> favoriting.request(ReadContent.of(text, format), on) },
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = modifier.graphicsLayer { alpha = shown },
        enabled = enabled && favorite != null,
        colors = colors,
    ) {
        FavoriteIcon(favorite == true, stringResource(R.string.favorite))
    }
}

/** Content waiting to be named as it becomes a favorite, and the name the dialog starts from. */
internal class FavoriteNaming(val text: String, val format: String, val initialName: String)

/** Kept across a configuration change, so a dialog open on a page that is created again stays open. */
private val FavoriteNamingSaver: Saver<FavoriteNaming?, Any> = listSaver(
    save = { naming -> naming?.let { listOf(it.text, it.format, it.initialName) }.orEmpty() },
    restore = { saved -> if (saved.size == 3) FavoriteNaming(saved[0], saved[1], saved[2]) else null },
)

/**
 * Makes content a favorite or stops it being one through [HistoryStore.requestFavorite], with the
 * haptics of the outcome, and asks for the name in the dialog of [rememberFavoriteRequester] where
 * the store says so: the content becomes a favorite only once it has one.
 */
@Stable
internal class FavoriteRequester(
    private val store: HistoryStore,
    private val haptics: Haptics,
    private val naming: MutableState<FavoriteNaming?>,
) {
    fun request(content: ReadContent, favorite: Boolean) =
        handle(content, favorite, store.requestFavorite(content, favorite))

    /** Names the content and makes it a favorite at once, whatever the settings say. */
    fun requestNamed(content: ReadContent) =
        handle(content, true, store.requestFavorite(content, true, askName = true))

    fun name(shown: FavoriteNaming, name: String) {
        store.nameFavorite(ReadContent.of(shown.text, shown.format), name)
        naming.value = null
    }

    private fun handle(content: ReadContent, favorite: Boolean, outcome: FavoriteRequest) {
        when (outcome) {
            FavoriteRequest.Done -> haptics.toggle(favorite)
            is FavoriteRequest.NeedsName -> {
                haptics.tap()
                naming.value = FavoriteNaming(content.text, content.format, outcome.initialName)
            }
        }
    }
}

/**
 * The one way the app's screens make content a favorite. It shows the dialog that names content as
 * it becomes a favorite while one is asked for: saving makes it one, under the name given or
 * showing its content when left blank, and cancelling leaves it as it was.
 */
@Composable
internal fun rememberFavoriteRequester(): FavoriteRequester {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val naming = rememberSaveable(stateSaver = FavoriteNamingSaver) { mutableStateOf<FavoriteNaming?>(null) }
    val requester = remember(context, haptics, naming) { FavoriteRequester(HistoryStore.get(context), haptics, naming) }
    naming.value?.let { shown ->
        RenameDialog(
            current = shown.initialName,
            onDismiss = { naming.value = null },
            onRename = { name -> requester.name(shown, name) },
            title = R.string.favorite_name_title,
            confirm = R.string.favorite,
            icon = R.drawable.ic_star,
        )
    }
    return requester
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
