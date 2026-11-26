@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.collection.intListOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.EntryPresentation
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.history.FavoriteShortcuts
import io.github.lcebot.ferryqr.history.HistoryEntry
import java.text.BreakIterator
import java.util.Date
import kotlin.math.roundToInt

/**
 * How [entry] is shown: [listed], as the history lists worked it out, or else
 * worked out here, for an entry the lists do not hold as it is, such as a tile fading out once
 * deleted. Its content and names are then still kept for the process from when the lists held
 * it, as a rule, so it is seldom classified or named again on the main thread.
 */
@Composable
internal fun rememberPresentation(entry: HistoryEntry, listed: EntryPresentation?): EntryPresentation {
    val context = LocalContext.current
    val languages = LocalConfiguration.current.locales
    return listed ?: remember(entry.text, entry.format, entry.label, languages) { EntryPresentation.of(context, entry) }
}

/**
 * An entry's [icon] on its avatar. Favorites take the tertiary colour, so they stay apart from the
 * history at a glance. A lifted row or tile turns tertiary container itself, which a favorite's
 * avatar then blends into, leaving its icon in on tertiary container, the colour a reorderable list
 * gives a dragged item's icon.
 */
@Composable
internal fun EntryAvatar(@DrawableRes icon: Int, favorite: Boolean) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (favorite) colors.tertiaryContainer else colors.primaryContainer,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "entry-icon-container",
    )
    val onContainer by animateColorAsState(
        if (favorite) colors.onTertiaryContainer else colors.onPrimaryContainer,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "entry-icon",
    )
    Box(
        modifier = Modifier
            .size(ENTRY_AVATAR)
            .clip(MaterialShapes.Cookie4Sided.toShape())
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = onContainer, modifier = Modifier.size(20.dp))
    }
}

/** The avatar's side, a list item's leading avatar. */
internal val ENTRY_AVATAR = 40.dp

/** An entry's [title] on two lines at most, broken anywhere when it has no spaces to break at. */
@Composable
internal fun EntryTitle(title: String) {
    val anywhere = remember(title) { breakAnywhere(title) }
    Text(
        anywhere ?: title,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        // Greedy, so the first line fills before the second starts.
        style = if (anywhere != null) LocalTextStyle.current.copy(lineBreak = LineBreak.Simple) else LocalTextStyle.current,
    )
}

/**
 * Interactions that give a list item its dragged look, from the expressive list's reorder tokens,
 * while [lifted], as a drag interaction does.
 */
@Composable
internal fun rememberLiftedInteraction(lifted: Boolean): MutableInteractionSource {
    val interactions = remember { MutableInteractionSource() }
    var start by remember { mutableStateOf<DragInteraction.Start?>(null) }
    LaunchedEffect(lifted) {
        if (lifted && start == null) {
            start = DragInteraction.Start().also { interactions.emit(it) }
        } else if (!lifted) {
            start?.let { interactions.emit(DragInteraction.Stop(it)) }
            start = null
        }
    }
    return interactions
}

/**
 * [topFavorite] is the content of the favorite at the top, which has nowhere higher to move.
 * [onNameAndFavorite] names an entry as it becomes a favorite, offered only while [namesFavorites]
 * is off, since favoriting asks for the name by itself while it is on.
 */
internal class EntryActions(
    val onOpen: (HistoryEntry) -> Unit,
    val onFavorite: (HistoryEntry, Boolean) -> Unit,
    val namesFavorites: Boolean,
    val onNameAndFavorite: (HistoryEntry) -> Unit,
    val onShortcut: (HistoryEntry, Boolean) -> Unit,
    val shortcutsFull: Boolean,
    val onRename: (HistoryEntry) -> Unit,
    val onDelete: (HistoryEntry) -> Unit,
    val topFavorite: String?,
    val onMoveToTop: (HistoryEntry) -> Unit,
)

/**
 * A favorite while the favorites are reordered: it neither opens nor swipes nor opens its menu. A
 * long press anywhere on it picks it up, and its handle at the end picks it up at once, as in
 * Android's own reorderable lists. While [lifted], the list item takes its dragged look, from the
 * expressive list's reorder tokens, through a drag interaction.
 */
@Composable
internal fun ReorderRow(
    entry: HistoryEntry,
    listed: EntryPresentation?,
    shape: Shape,
    lifted: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = rememberLiftedInteraction(lifted)
    Box(
        modifier.pointerInput(entry.text) {
            detectDragGesturesAfterLongPress(
                onDragStart = { onDragStart() },
                onDragEnd = onDragEnd,
                onDragCancel = onDragEnd,
                onDrag = { change, amount ->
                    change.consume()
                    onDrag(amount.y)
                },
            )
        },
    ) {
        EntryItem(
            entry,
            listed,
            shape,
            onClick = {},
            onLongClick = null,
            interactionSource = interactions,
            trailingOverride = {
                // The drag is detected outside the minimum interactive size, so a touch anywhere in
                // it picks the row up, not only one on the glyph.
                Icon(
                    painterResource(R.drawable.ic_drag_indicator),
                    contentDescription = stringResource(R.string.favorite_drag),
                    modifier = Modifier
                        .pointerInput(entry.text) {
                            detectDragGestures(
                                onDragStart = { onDragStart() },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragEnd,
                                onDrag = { change, amount ->
                                    change.consume()
                                    onDrag(amount.y)
                                },
                            )
                        }
                        .minimumInteractiveComponentSize()
                        .size(24.dp),
                )
            },
        )
    }
}

/**
 * One entry: a tap opens it, a long press opens its menu, a swipe toward the start deletes it, and a
 * swipe toward the end makes it a favorite or stops it being one. A favorite swipe slides the row
 * back once done. The swipe works as a notification's does: the row follows the finger at half its
 * pace, pulling the rows around it, until it comes loose at [DETACH_AT] and follows the finger
 * itself. A row swiped away and deleted leaves its swipe with [list] while undoing can bring it
 * back, and brought back, it flies back into its place from where it went, as a tile does.
 */
@Composable
internal fun EntryRow(
    entry: HistoryEntry,
    listed: EntryPresentation?,
    group: Any,
    index: Int,
    count: Int,
    list: MagneticList,
    actions: EntryActions,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val magnet = rememberRowMagnet(list, entry.text, group, index, with(density) { DETACH_AT.toPx() })
    var width by remember { mutableIntStateOf(0) }
    val kept = remember { list.departed[entry.text] }
    val swipe = rememberEntrySwipe(
        entry = entry,
        group = group,
        index = index,
        list = list,
        flyTo = { width.toFloat() },
        onDelete = actions.onDelete,
        onFavorite = actions.onFavorite,
        kept = kept,
    )
    DisposableEffect(swipe) {
        onDispose { if (swipe.acting) list.departed[entry.text] = swipe }
    }
    LaunchedEffect(swipe) {
        if (kept == null) return@LaunchedEffect
        list.departed.remove(entry.text)
        if (swipe.acting) swipe.comeBack()
    }
    // What a swipe shows behind the row takes the row's own shape, rounding with it as it is pulled
    // and comes loose, so it matches the corners of the neighbours that round toward it.
    val roundTop by rememberRounding(magnet.topStart)
    val roundBottom by rememberRounding(magnet.bottomStart)
    val shape = segmentShape(index, count, roundTop, roundBottom)
    Box(
        modifier
            .onSizeChanged { width = it.width }
            .trackTouch { touch = it }
            .entrySwipe(swipe),
    ) {
        SwipeBackground(
            swipe.direction,
            swipe.armed,
            swipe.becomesFavorite,
            shape,
            Modifier.matchParentSize().graphicsLayer { alpha = swipe.backdrop.value },
        )
        Box(Modifier.graphicsLayer { translationX = onWholePixels(swipe.shift.value.value + magnet.lean.value.value) }) {
            EntryItem(
                entry,
                listed,
                shape,
                onClick = { actions.onOpen(entry) },
                onLongClick = { menu = true },
            )
        }
        EntryMenu(
            entry = entry,
            at = touch,
            expanded = menu,
            onDismiss = { menu = false },
            actions = actions,
        )
    }
}

/**
 * [listed] is how the history lists show [entry]. [trailingOverride] replaces what the row shows at
 * its end, as the handle does while reordering.
 */
@Composable
private fun EntryItem(
    entry: HistoryEntry,
    listed: EntryPresentation?,
    shape: Shape,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    trailingOverride: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val context = LocalContext.current
    val shown = rememberPresentation(entry, listed)
    val time = remember(entry.time) { DateFormat.getTimeFormat(context).format(Date(entry.time)) }
    // A favorite is kept for what it is rather than when it was scanned.
    val timeTrailing: (@Composable () -> Unit)? = if (entry.favorite) null else {
        { Text(time, style = MaterialTheme.typography.labelMedium) }
    }
    val trailing = trailingOverride ?: timeTrailing
    ListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = if (onLongClick != null) stringResource(R.string.entry_more) else null,
        interactionSource = interactionSource,
        // Deliberately centred, as the settings rows are, against the list's rule that top-aligns
        // the icon, time and handle of a row three lines tall, which a title on two lines makes it.
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { EntryAvatar(shown.kind.icon, entry.favorite) },
        trailingContent = trailing,
        supportingContent = { Text(stringResource(shown.kind.label)) },
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        EntryTitle(shown.title)
    }
}

/**
 * Shown behind a row being swiped. Once letting go would act, the icon grows and the colours turn
 * to those of the result: the error colours for deleting, the tertiary colours favorites use for a
 * new favorite, and the primary colours of other entries for one that stops being a favorite.
 */
@Composable
internal fun SwipeBackground(
    direction: SwipeToDismissBoxValue,
    armed: Boolean,
    becomesFavorite: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (armedContainer, armedContent) = when {
        direction == SwipeToDismissBoxValue.EndToStart -> colors.errorContainer to colors.onErrorContainer
        becomesFavorite -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.primaryContainer to colors.onPrimaryContainer
    }
    val container by animateColorAsState(
        if (armed) armedContainer else colors.surfaceContainerHighest,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "swipe-container",
    )
    val content by animateColorAsState(
        if (armed) armedContent else colors.onSurfaceVariant,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "swipe-icon",
    )
    val scale by animateFloatAsState(
        if (armed) 1.25f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "swipe-scale",
    )
    val (alignment, icon) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd ->
            Alignment.CenterStart to if (becomesFavorite) R.drawable.ic_star_filled else R.drawable.ic_star
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd to R.drawable.ic_delete
        SwipeToDismissBoxValue.Settled -> null to null
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (alignment == null) Color.Transparent else container)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment ?: Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = content,
                modifier = Modifier.scale(scale),
            )
        }
    }
}

/** Where each touch on the modified element goes down, so a menu it opens can open there. */
internal fun Modifier.trackTouch(onDown: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture { onDown(awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position) }
}

/**
 * The long-press menu, in the grouped expressive style: what to do with the entry, then deleting it.
 * It opens at [at], where the long press touched the entry, as a context menu opens at its pointer:
 * from there toward the end and downward, or toward the start or upward where there is no room.
 */
@Composable
internal fun EntryMenu(entry: HistoryEntry, at: Offset, expanded: Boolean, onDismiss: () -> Unit, actions: EntryActions) {
    val position = remember(at) {
        MenuAnchorPosition.Custom(
            xCandidates = {
                val x = anchorBounds.left + at.x.roundToInt()
                if (layoutDirection == LayoutDirection.Ltr) intListOf(x, x - menuSize.width) else intListOf(x - menuSize.width, x)
            },
            yCandidates = {
                val y = anchorBounds.top + at.y.roundToInt()
                intListOf(y, y - menuSize.height)
            },
        )
    }
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
        popupPositionProvider = MenuDefaults.rememberDropdownMenuPopupPositionProvider(position),
    ) {
        val keep = buildList {
            if (entry.favorite) {
                add(MenuEntry(R.string.favorite_remove, R.drawable.ic_star_filled) { actions.onFavorite(entry, false) })
                add(MenuEntry(R.string.rename, R.drawable.ic_edit) { actions.onRename(entry) })
                if (entry.text != actions.topFavorite) {
                    add(MenuEntry(R.string.favorite_move_top, R.drawable.ic_drag_indicator) { actions.onMoveToTop(entry) })
                }
                if (entry.shortcut) {
                    add(MenuEntry(R.string.shortcut_remove, R.drawable.ic_app_shortcut) { actions.onShortcut(entry, false) })
                } else {
                    // A full set stays visible but disabled, so the limit explains itself.
                    add(
                        MenuEntry(
                            R.string.shortcut_add,
                            R.drawable.ic_app_shortcut,
                            enabled = !actions.shortcutsFull,
                            note = if (actions.shortcutsFull) R.string.shortcut_full else null,
                        ) { actions.onShortcut(entry, true) },
                    )
                }
            } else {
                add(MenuEntry(R.string.favorite, R.drawable.ic_star) { actions.onFavorite(entry, true) })
                if (!actions.namesFavorites) {
                    add(MenuEntry(R.string.rename_and_favorite, R.drawable.ic_edit) { actions.onNameAndFavorite(entry) })
                }
                add(MenuEntry(R.string.rename, R.drawable.ic_edit) { actions.onRename(entry) })
            }
        }
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
            keep.forEachIndexed { index, item ->
                MenuRow(item, menuItemShape(index, keep.size), onDismiss)
            }
        }
        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
            MenuRow(
                MenuEntry(R.string.delete, R.drawable.ic_delete) { actions.onDelete(entry) },
                menuItemShape(0, 1),
                onDismiss,
                destructive = true,
            )
        }
    }
}

/** [note] explains why an item is disabled. */
private class MenuEntry(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val enabled: Boolean = true,
    @StringRes val note: Int? = null,
    val run: () -> Unit,
)

@Composable
private fun MenuRow(item: MenuEntry, shape: Shape, onDismiss: () -> Unit, destructive: Boolean = false) {
    val colors = if (destructive) {
        MenuDefaults.itemColors(
            textColor = MaterialTheme.colorScheme.error,
            leadingIconColor = MaterialTheme.colorScheme.error,
        )
    } else {
        MenuDefaults.itemColors()
    }
    val noteRes = item.note
    val note: (@Composable () -> Unit)? = if (noteRes == null) null else {
        { Text(stringResource(noteRes, FavoriteShortcuts.MAX)) }
    }
    DropdownMenuItem(
        onClick = {
            onDismiss()
            item.run()
        },
        text = { Text(stringResource(item.label)) },
        shape = shape,
        enabled = item.enabled,
        supportingText = note,
        leadingIcon = {
            Icon(painterResource(item.icon), contentDescription = null, modifier = Modifier.size(MenuDefaults.LeadingIconSize))
        },
        colors = colors,
    )
}

/**
 * [text] with a break allowed between any two characters, or null when it breaks well as it is. Text
 * without spaces, such as a link, a key or an account name, can otherwise break only at the few
 * places a line breaker finds in it, as after a colon or a slash, which leaves a short piece alone on
 * the first line and pushes the rest to the second, where it is cut off. Text with spaces breaks
 * between its words, and Chinese text between any two characters already, keeping its rules for
 * punctuation, so both are left alone.
 */
internal fun breakAnywhere(text: String): String? {
    if (text.any { it.isWhitespace() }) return null
    if (text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }) return null
    val characters = BreakIterator.getCharacterInstance().apply { setText(text) }
    return buildString {
        var start = characters.first()
        var end = characters.next()
        while (end != BreakIterator.DONE) {
            if (start > 0) append(ZERO_WIDTH_SPACE)
            append(text, start, end)
            start = end
            end = characters.next()
        }
    }
}

private const val ZERO_WIDTH_SPACE = '\u200B'
