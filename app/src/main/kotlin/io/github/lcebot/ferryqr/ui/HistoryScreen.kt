@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.Job
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import android.os.SystemClock
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.dispatch.ActionResult
import io.github.lcebot.ferryqr.dispatch.Cards
import io.github.lcebot.ferryqr.dispatch.Origin
import io.github.lcebot.ferryqr.generate.CodeGenerator
import io.github.lcebot.ferryqr.history.FavoriteShortcuts
import io.github.lcebot.ferryqr.history.HistoryEntry
import io.github.lcebot.ferryqr.history.HistoryStore
import java.text.BreakIterator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.launch

/**
 * The corners of a segmented list, as the expressive list tokens give them: the group's outer
 * corners large, 16dp, and those between its rows extra small, 4dp. A pressed row rounds to the
 * large corner all round, so the outer corners stay put as it morphs.
 */
private val OUTER_CORNER = 16.dp
private val INNER_CORNER = 4.dp

/** With more favorites than this, the section can fold down to its first few. */
private const val FOLDED_FAVORITES = 4

/** The group the favorites form for a swipe; the other rows group by day. */
private const val FAVORITES_GROUP = "favorites"

private const val PREFS = "history"
private const val PREF_FAVORITES_FOLDED = "favorites_folded"

/**
 * [openText] is content to show at once, from a favorite's app shortcut; [onOpened] is called once
 * it has been shown or found missing.
 */
@Composable
fun HistoryScreen(onBack: () -> Unit, openText: String? = null, onOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = LocalActivity.current ?: return
    val haptics = rememberHaptics()
    val store = remember { HistoryStore.get(context) }
    val entries by store.entries.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var folded by remember { mutableStateOf(prefs.getBoolean(PREF_FAVORITES_FOLDED, false)) }
    var confirmClear by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<HistoryEntry?>(null) }
    var showing by remember { mutableStateOf<HistoryEntry?>(null) }
    val ready = ferriesReady()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val favorites = remember(entries) { HistoryStore.favoritesOf(entries) }
    // While reordering, the favorites follow [order], the contents in their new order, which is saved when done.
    var reordering by remember { mutableStateOf(false) }
    val order = remember { mutableStateListOf<String>() }
    // A favorite added meanwhile, from a row below, joins at the top, where it would go anyway; one
    // removed leaves.
    LaunchedEffect(favorites) {
        if (!reordering) return@LaunchedEffect
        val current = favorites.map { it.text }
        order.retainAll(current.toSet())
        order.addAll(0, current.filter { it !in order })
    }
    // The favorite being dragged, and while [settling] the one gliding into its place after the
    // finger lifted. It sets its dragged look down as the finger lifts and stays above its
    // neighbours only until it lands, on the quick spatial motion, so letting go answers at once.
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val settleSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val listState = rememberLazyListState()
    val magnetic = remember { MagneticList() }
    val recent = remember(entries) { entries.filterNot { it.favorite } }
    val shortcutsFull = favorites.count { it.shortcut } >= FavoriteShortcuts.MAX
    val subtitle: (@Composable () -> Unit)? = if (entries.isEmpty()) {
        null
    } else {
        { Text(pluralStringResource(R.plurals.history_count, entries.size, entries.size)) }
    }

    fun open(entry: HistoryEntry) {
        showing = entry
    }

    LaunchedEffect(openText) {
        if (openText == null) return@LaunchedEffect
        store.find(openText)?.let { open(it) }
        onOpened()
    }

    fun delete(entry: HistoryEntry) {
        haptics.confirm()
        store.delete(entry)
        scope.launch {
            val result = snackbar.showLatest(
                message = context.getString(R.string.history_deleted),
                actionLabel = context.getString(R.string.undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                haptics.tap()
                store.restore(entry)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                subtitle = subtitle,
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    if (recent.isNotEmpty()) {
                        IconButton(onClick = { haptics.tap(); confirmClear = true }) {
                            Icon(painterResource(R.drawable.ic_delete_sweep), stringResource(R.string.history_clear))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyHistory(Modifier.fillMaxSize().padding(padding))
        } else {
            val zone = remember { ZoneId.systemDefault() }
            val groups = remember(recent) {
                recent.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
            }
            val foldable = favorites.size > FOLDED_FAVORITES && !reordering
            val ordered = if (reordering) order.mapNotNull { text -> favorites.find { it.text == text } } else favorites
            val shownFavorites = if (foldable && folded) ordered.take(FOLDED_FAVORITES) else ordered

            /**
             * Moves the row being dragged past a neighbour once its middle crosses the neighbour's,
             * and keeps it under the finger by taking the distance it moved off the drag.
             */
            fun followDrag() {
                val text = dragging ?: return
                val rows = listState.layoutInfo.visibleItemsInfo
                val row = rows.firstOrNull { it.key == "entry:$text" } ?: return
                val index = order.indexOf(text)
                // Until the list is laid out again after a move, its positions are the old ones, and
                // would move the row straight back; the favorites start below their header.
                if (row.index != index + 1) return
                val middle = row.offset + row.size / 2 + dragOffset
                val next = order.getOrNull(index + 1)?.let { key -> rows.firstOrNull { it.key == "entry:$key" } }
                val previous = order.getOrNull(index - 1)?.let { key -> rows.firstOrNull { it.key == "entry:$key" } }
                // The list keeps its place by its first visible row; pinned here, a moved first row
                // does not drag the list along with it.
                fun pin() = listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                when {
                    next != null && middle > next.offset + next.size / 2 -> {
                        pin()
                        order.add(index + 1, order.removeAt(index))
                        dragOffset -= (next.offset + next.size) - (row.offset + row.size)
                        haptics.step()
                    }
                    previous != null && middle < previous.offset + previous.size / 2 -> {
                        pin()
                        order.add(index - 1, order.removeAt(index))
                        dragOffset += row.offset - previous.offset
                        haptics.step()
                    }
                }
            }
            fun startDrag(text: String) {
                settling?.cancel()
                settling = null
                // Caught again on its way into place, the row goes on from where it is.
                if (dragging != text) dragOffset = 0f
                dragging = text
                haptics.step()
            }
            /** Glides the row from where the finger left it into its place, then sets it down. */
            fun endDrag() {
                settling = scope.launch {
                    animate(dragOffset, 0f, animationSpec = settleSpec) { value, _ -> dragOffset = value }
                    dragging = null
                    settling = null
                }
            }
            fun moveInOrder(text: String, to: Int) {
                val from = order.indexOf(text)
                if (from < 0 || to !in order.indices) return
                order.add(to, order.removeAt(from))
                haptics.step()
            }
            val actions = EntryActions(
                onOpen = { haptics.tap(); open(it) },
                onFavorite = { entry, on ->
                    haptics.toggle(on)
                    store.setFavorite(entry.text, entry.format, on)
                },
                onShortcut = { entry, on ->
                    haptics.toggle(on)
                    store.setShortcut(entry.text, on)
                    if (on) {
                        scope.launch {
                            snackbar.showLatest(context.getString(R.string.shortcut_added))
                        }
                    }
                },
                shortcutsFull = shortcutsFull,
                onRename = { haptics.tap(); renaming = it },
                onDelete = ::delete,
                topFavorite = favorites.firstOrNull()?.text,
                onMoveToTop = { entry ->
                    haptics.tap()
                    store.moveFavorite(entry.text, 0)
                },
            )
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                if (favorites.isNotEmpty()) {
                    item(key = "header:favorites") {
                        FavoritesHeader(
                            foldable = foldable,
                            folded = folded,
                            hidden = favorites.size - FOLDED_FAVORITES,
                            onToggle = {
                                haptics.tap()
                                folded = !folded
                                prefs.edit().putBoolean(PREF_FAVORITES_FOLDED, folded).apply()
                            },
                            reorderable = favorites.size > 1,
                            reordering = reordering,
                            onReorder = {
                                haptics.tap()
                                if (reordering) {
                                    store.setFavoriteOrder(order.toList())
                                } else {
                                    order.clear()
                                    order.addAll(favorites.map { it.text })
                                }
                                reordering = !reordering
                            },
                            modifier = animateRow(),
                        )
                    }
                    itemsIndexed(shownFavorites, key = { _, e -> "entry:${e.text}" }) { index, entry ->
                        if (reordering) {
                            val dragged = dragging == entry.text
                            val moveUp = stringResource(R.string.favorite_move_up)
                            val moveDown = stringResource(R.string.favorite_move_down)
                            ReorderRow(
                                entry = entry,
                                shape = segmentShape(index, shownFavorites.size),
                                lifted = dragged && settling == null,
                                onDragStart = { startDrag(entry.text) },
                                onDrag = { amount ->
                                    dragOffset += amount
                                    followDrag()
                                },
                                onDragEnd = ::endDrag,
                                modifier = (if (dragged) Modifier else animateRow())
                                    .zIndex(if (dragged) 1f else 0f)
                                    .graphicsLayer { if (dragged) translationY = dragOffset }
                                    // Without a finger to drag with, the order changes a row at a time.
                                    .semantics {
                                        customActions = listOfNotNull(
                                            CustomAccessibilityAction(moveUp) { moveInOrder(entry.text, index - 1); true }
                                                .takeIf { index > 0 },
                                            CustomAccessibilityAction(moveDown) { moveInOrder(entry.text, index + 1); true }
                                                .takeIf { index < shownFavorites.size - 1 },
                                        )
                                    },
                            )
                        } else {
                            EntryRow(entry, FAVORITES_GROUP, index, shownFavorites.size, magnetic, actions, animateRow())
                        }
                    }
                }
                groups.forEach { (day, items) ->
                    item(key = "header:$day") { DayHeader(day, items.first().time, animateRow()) }
                    itemsIndexed(items, key = { _, e -> "entry:${e.text}" }) { index, entry ->
                        EntryRow(entry, day, index, items.size, magnetic, actions, animateRow())
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = null) },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    store.clear()
                    confirmClear = false
                    scope.launch { snackbar.showLatest(context.getString(R.string.history_cleared)) }
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { haptics.tap(); confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    renaming?.let { entry ->
        RenameDialog(
            current = entry.label ?: defaultTitle(context, entry),
            onDismiss = { renaming = null },
            onRename = { name ->
                store.rename(entry.text, name)
                renaming = null
            },
        )
    }

    // History stays open after a handoff, so returning to the app lands back on the list.
    fun onResult(result: ActionResult) {
        when (result) {
            ActionResult.Handoff -> {
                haptics.tap()
                showing = null
            }
            ActionResult.Done -> haptics.tap()
            ActionResult.Copied -> {
                haptics.confirm()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    scope.launch { snackbar.showLatest(context.getString(R.string.copied)) }
                }
            }
            ActionResult.Unavailable -> {
                haptics.reject()
                scope.launch { snackbar.showLatest(context.getString(R.string.no_app)) }
            }
        }
    }

    showing?.let { entry ->
        // The original code is no longer in sight, so every entry is shown with its code drawn
        // again, to be scanned by another phone or kept as an image. The card is built again once
        // the framework reports which apps take content.
        val card = remember(entry, ready) {
            val payload = Classifier.classify(entry.text, entry.format)
            val built = Cards.build(activity, payload, entry.format, Origin.HISTORY, scannedAt = entry.time)
            val image = built.image ?: CodeGenerator.redraw(entry.text, entry.format)
            built.copy(image = image, exportable = image != null)
        }
        ResultSheet(
            card = card,
            onDismiss = { showing = null },
            onAction = { action -> onResult(action.run(activity)) },
        )
    }
}

/** [topFavorite] is the content of the favorite at the top, which has nowhere higher to move. */
private class EntryActions(
    val onOpen: (HistoryEntry) -> Unit,
    val onFavorite: (HistoryEntry, Boolean) -> Unit,
    val onShortcut: (HistoryEntry, Boolean) -> Unit,
    val shortcutsFull: Boolean,
    val onRename: (HistoryEntry) -> Unit,
    val onDelete: (HistoryEntry) -> Unit,
    val topFavorite: String?,
    val onMoveToTop: (HistoryEntry) -> Unit,
)

/** What a favorite is called until the user names it: the same short title its shortcut shows. */
private fun defaultTitle(context: Context, entry: HistoryEntry): String {
    val payload = Classifier.classify(entry.text, entry.format)
    return Cards.titleOf(context, payload) ?: context.getString(Cards.kindOf(payload).label)
}

/** Rows and headers fade in and out and slide to their new places with the theme's motion. */
@Composable
private fun LazyItemScope.animateRow(): Modifier = Modifier.animateItem(
    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
)

@Composable
private fun FavoritesHeader(
    foldable: Boolean,
    folded: Boolean,
    hidden: Int,
    onToggle: () -> Unit,
    reorderable: Boolean,
    reordering: Boolean,
    onReorder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = LIST_TEXT_INSET, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.favorites_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (foldable) {
            val turn by animateFloatAsState(
                targetValue = if (folded) 0f else 180f,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "favorites-chevron",
            )
            val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            TextButton(onClick = onToggle, shapes = ButtonDefaults.shapes()) {
                AnimatedContent(
                    targetState = folded,
                    transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                    label = "favorites-toggle",
                ) { isFolded ->
                    Text(
                        if (isFolded) pluralStringResource(R.plurals.favorites_show_more, hidden, hidden)
                        else stringResource(R.string.favorites_show_less),
                    )
                }
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Icon(
                    painterResource(R.drawable.ic_expand),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize).rotate(turn),
                )
            }
        }
        if (reorderable || reordering) {
            TextButton(onClick = onReorder, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(if (reordering) R.string.favorites_reorder_done else R.string.favorites_reorder))
            }
        }
    }
}

/**
 * A favorite while the favorites are reordered: it neither opens nor swipes nor opens its menu. A
 * long press anywhere on it picks it up, and its handle at the end picks it up at once, as in
 * Android's own reorderable lists. While [lifted], the list item takes its dragged look, from the
 * expressive list's reorder tokens, through a drag interaction.
 */
@Composable
private fun ReorderRow(
    entry: HistoryEntry,
    shape: Shape,
    lifted: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            shape,
            onClick = {},
            onLongClick = null,
            interactionSource = interactions,
            lifted = lifted,
            trailingOverride = {
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
                        .size(40.dp)
                        .padding(8.dp),
                )
            },
        )
    }
}

@Composable
private fun DayHeader(day: LocalDate, sample: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val label = when (day) {
        today -> stringResource(R.string.history_today)
        today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> {
            var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY
            if (day.year != today.year) flags = flags or DateUtils.FORMAT_SHOW_YEAR
            DateUtils.formatDateTime(context, sample, flags)
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = LIST_TEXT_INSET, end = LIST_TEXT_INSET, top = SECTION_TOP, bottom = SECTION_GAP),
    )
}

/**
 * One entry: a tap opens it, a long press opens its menu, a swipe toward the start deletes it, and a
 * swipe toward the end makes it a favorite or stops it being one. A favorite swipe slides the row
 * back once done. The swipe works as a notification's does: the row follows the finger at half its
 * pace, pulling the rows around it, until it comes loose at [DETACH_AT] and follows the finger
 * itself. The swipe state lives only as long as the row, so an entry brought back by undo arrives
 * settled.
 */
@Composable
private fun EntryRow(
    entry: HistoryEntry,
    group: Any,
    index: Int,
    count: Int,
    list: MagneticList,
    actions: EntryActions,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val swipeHaptics = rememberSwipeHaptics(haptics)
    var menu by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val detachAt = with(density) { DETACH_AT.toPx() }
    val attachAt = with(density) { ATTACH_AT.toPx() }
    val dismissVelocity = with(density) { DISMISS_VELOCITY.toPx() }
    val minDismissVelocity = with(density) { MIN_DISMISS_VELOCITY.toPx() }
    val scope = rememberCoroutineScope()
    val magnet = rememberRowMagnet(list, entry.text, group, index, detachAt)

    // Where the row is drawn, apart from the finger: it follows at half pace until it comes loose.
    val shift = remember { Follower() }
    var pull by remember { mutableStateOf<Pull?>(null) }
    // Let go to act, until the row is back in place.
    var acting by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<Job?>(null) }
    var width by remember { mutableIntStateOf(0) }
    val tracker = remember { VelocityTracker1D(isDataDifferential = true) }

    val direction by remember(rtl) {
        derivedStateOf {
            val x = shift.value.value
            when {
                x == 0f -> SwipeToDismissBoxValue.Settled
                (x > 0f) != rtl -> SwipeToDismissBoxValue.StartToEnd
                else -> SwipeToDismissBoxValue.EndToStart
            }
        }
    }
    val armed = pull?.phase == PullPhase.Detached || acting

    LaunchedEffect(pull) {
        val swiped = pull ?: return@LaunchedEffect
        var phase = swiped.phase
        snapshotFlow { swiped.phase to swiped.finger }.collectLatest { (now, finger) ->
            if (now == PullPhase.Released) return@collectLatest
            if (now != phase) {
                // Coming loose throws the row on at the finger's speed; rejoining throws it back.
                val speed = abs(tracker.calculateVelocity()) * sign(finger)
                if (now == PullPhase.Detached) shift.animateWith(DetachSpring, speed) else shift.animateWith(AttachSpring, -speed)
                phase = now
            }
            shift.follow(if (now == PullPhase.Detached) finger else PULLED_SHARE * finger)
        }
    }
    DisposableEffect(list) {
        onDispose { if (list.pull?.key == entry.text) list.pull = null }
    }

    // What a favorite swipe will do is fixed when it starts, so the row sliding back after the
    // change still shows the change it made rather than the opposite one.
    var becomesFavorite by remember { mutableStateOf(!entry.favorite) }
    LaunchedEffect(direction, entry.favorite) {
        if (direction == SwipeToDismissBoxValue.Settled) becomesFavorite = !entry.favorite
    }

    val current by rememberUpdatedState(entry)
    val delete by rememberUpdatedState(actions.onDelete)
    val favorite by rememberUpdatedState(actions.onFavorite)
    val place by rememberUpdatedState(index)
    val settle = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    val drag = rememberDraggableState { delta ->
        val swiped = pull ?: return@rememberDraggableState
        tracker.addDataPoint(SystemClock.uptimeMillis(), delta)
        swiped.finger += delta
        val travel = abs(swiped.finger)
        when (swiped.phase) {
            PullPhase.Pulling ->
                if (travel >= detachAt) {
                    swiped.phase = PullPhase.Detached
                    swipeHaptics.threshold(true)
                } else {
                    swipeHaptics.pull(PULLED_SHARE * travel / detachAt)
                }
            PullPhase.Detached ->
                if (travel <= attachAt) {
                    swiped.phase = PullPhase.Pulling
                    swipeHaptics.threshold(false)
                }
            PullPhase.Released -> Unit
        }
    }

    fun start() {
        // A row caught while it moves is swiped on from where it is.
        release?.cancel()
        acting = false
        tracker.resetTracking()
        val from = shift.value.value
        if (from != 0f) shift.animateWith(AttachSpring) else shift.stopAnimating()
        val swiped = Pull(entry.text, group, place).apply { finger = from }
        pull = swiped
        list.pull = swiped
    }

    fun letGo(velocity: Float) {
        val swiped = pull ?: return
        val detached = swiped.phase == PullPhase.Detached
        val side = sign(swiped.finger)
        val fast = abs(velocity) >= dismissVelocity
        // A loose row acts unless flung back; an attached one only when flung on.
        val acts = side != 0f && if (detached) !(fast && sign(velocity) != side) else fast && sign(velocity) == side
        swiped.releaseVelocity = velocity
        swiped.dismissing = acts
        swiped.phase = PullPhase.Released
        release = scope.launch {
            try {
                if (acts) {
                    if (!detached) swipeHaptics.threshold(true)
                    acting = true
                    val speed = if (detached) max(minDismissVelocity, abs(velocity)) else abs(velocity)
                    val end = side * width
                    val distance = abs(end - shift.value.value)
                    val millis = if (speed > 0f) min(MAX_ESCAPE_MS, (distance * 1000f / speed).toInt()) else DEFAULT_ESCAPE_MS
                    // A flick carries on at its own speed; a row let go slowly gathers speed as it leaves.
                    shift.value.animateTo(end, tween(millis, easing = if (fast) LinearEasing else FastOutLinearInEasing))
                    if ((side > 0f) != rtl) {
                        favorite(current, !current.favorite)
                        if (list.pull === swiped) list.pull = null
                        shift.value.animateTo(0f, settle)
                        acting = false
                    } else {
                        delete(current)
                    }
                } else {
                    shift.value.animateTo(0f, SnapBackSpring, velocity)
                }
            } finally {
                if (list.pull === swiped) list.pull = null
                if (pull === swiped) pull = null
            }
        }
    }

    Box(
        modifier
            .onSizeChanged { width = it.width }
            .draggable(
                drag,
                Orientation.Horizontal,
                onDragStarted = { start() },
                onDragStopped = { letGo(it) },
            ),
    ) {
        SwipeBackground(direction, armed, becomesFavorite, segmentShape(index, count), Modifier.matchParentSize())
        Box(Modifier.graphicsLayer { translationX = shift.value.value + magnet.lean.value.value }) {
            EntryItem(
                entry,
                segmentShape(index, count, magnet.top.value.value, magnet.bottom.value.value),
                onClick = { actions.onOpen(entry) },
                onLongClick = { menu = true },
            )
        }
        EntryMenu(
            entry = entry,
            expanded = menu,
            onDismiss = { menu = false },
            actions = actions,
        )
    }
}

/** [trailingOverride] replaces what the row shows at its end, as the handle does while reordering. */
@Composable
private fun EntryItem(
    entry: HistoryEntry,
    shape: Shape,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    trailingOverride: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    lifted: Boolean = false,
) {
    val context = LocalContext.current
    val payload = remember(entry.text, entry.format) { Classifier.classify(entry.text, entry.format) }
    val kind = remember(payload) { Cards.kindOf(payload) }
    val shown = remember(payload) { Cards.shownText(context, payload) }
    val time = remember(entry.time) { DateFormat.getTimeFormat(context).format(Date(entry.time)) }
    // Favorites take the tertiary colour, so they stay apart from the history at a glance. A lifted
    // row turns tertiary container itself, so its icon takes the full tertiary to stand out on it.
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when {
            lifted -> colors.tertiary
            entry.favorite -> colors.tertiaryContainer
            else -> colors.primaryContainer
        },
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "entry-icon-container",
    )
    val onContainer by animateColorAsState(
        when {
            lifted -> colors.onTertiary
            entry.favorite -> colors.onTertiaryContainer
            else -> colors.onPrimaryContainer
        },
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "entry-icon",
    )
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
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialShapes.Cookie4Sided.toShape())
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(kind.icon),
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        trailingContent = trailing,
        supportingContent = { Text(stringResource(kind.label)) },
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        val headline = entry.label ?: shown
        val anywhere = remember(headline) { breakAnywhere(headline) }
        Text(
            anywhere ?: headline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Greedy, so the first line fills before the second starts.
            style = if (anywhere != null) LocalTextStyle.current.copy(lineBreak = LineBreak.Simple) else LocalTextStyle.current,
        )
    }
}

/**
 * Shown behind a row being swiped. Once letting go would act, the icon grows and the colours turn
 * to those of the result: the error colours for deleting, the tertiary colours favorites use for a
 * new favorite, and the primary colours of other entries for one that stops being a favorite.
 */
@Composable
private fun SwipeBackground(
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

/** The long-press menu, in the grouped expressive style: what to do with the entry, then deleting it. */
@Composable
private fun EntryMenu(entry: HistoryEntry, expanded: Boolean, onDismiss: () -> Unit, actions: EntryActions) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
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

@Composable
fun menuItemShape(index: Int, count: Int): Shape = when {
    count == 1 -> MenuDefaults.standaloneItemShape
    index == 0 -> MenuDefaults.leadingItemShape
    index == count - 1 -> MenuDefaults.trailingItemShape
    else -> MenuDefaults.middleItemShape
}

@Composable
private fun EmptyHistory(modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_history),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.history_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * [text] with a break allowed between any two characters, or null when it breaks well as it is. Text
 * without spaces, such as a link, a key or an account name, can otherwise break only at the few
 * places a line breaker finds in it, as after a colon or a slash, which leaves a short piece alone on
 * the first line and pushes the rest to the second, where it is cut off. Text with spaces breaks
 * between its words, and Chinese text between any two characters already, keeping its rules for
 * punctuation, so both are left alone.
 */
private fun breakAnywhere(text: String): String? {
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

/**
 * Expressive grouped list: large corners outside a group, small ones between its rows. The corners
 * follow a row that becomes the first or last of its group, as when a neighbour is deleted, where
 * the list item's own segmented shapes would switch at once.
 */
@Composable
fun segmentShape(index: Int, count: Int): Shape = segmentShape(index, count, roundTop = 0f, roundBottom = 0f)

/**
 * [roundTop] and [roundBottom], from 0 to 1, round a row's corners further toward the large corner,
 * as a swipe does to the row pulled and those around it.
 */
@Composable
internal fun segmentShape(index: Int, count: Int, roundTop: Float, roundBottom: Float): Shape {
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Dp>()
    val top by animateDpAsState(if (index == 0) OUTER_CORNER else INNER_CORNER, spec, label = "row-top")
    val bottom by animateDpAsState(if (index == count - 1) OUTER_CORNER else INNER_CORNER, spec, label = "row-bottom")
    val shownTop = maxOf(top, lerp(INNER_CORNER, OUTER_CORNER, roundTop.coerceIn(0f, 1f)))
    val shownBottom = maxOf(bottom, lerp(INNER_CORNER, OUTER_CORNER, roundBottom.coerceIn(0f, 1f)))
    return RoundedCornerShape(topStart = shownTop, topEnd = shownTop, bottomStart = shownBottom, bottomEnd = shownBottom)
}
