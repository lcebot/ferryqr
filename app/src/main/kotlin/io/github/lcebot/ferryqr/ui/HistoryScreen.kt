@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.FavoriteLayout
import io.github.lcebot.ferryqr.HistoryDialog
import io.github.lcebot.ferryqr.HistoryViewModel
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.history.FavoriteShortcuts
import io.github.lcebot.ferryqr.history.HistoryEntry
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The group the favorites form for a swipe; the other rows group by day. */
private const val FAVORITES_GROUP = "favorites"

/** How long rows brought back by undo have to take up their swipes before the swipes are let go. */
private const val DEPARTED_WAIT_MS = 1000L

/** The list item holding the favorites' title. */
private const val FAVORITES_HEADER_KEY = "header:favorites"

/** The list item holding the favorites as tiles. */
internal const val TILES_KEY = "favorites:tiles"

/** The key of the list item showing the entry with [text]. */
private fun rowKey(text: String): Any = "entry:$text"

/** What the history page shows below its top bar. */
private enum class HistoryContent { LOADING, EMPTY, LIST }

/**
 * [openSaved] is an entry to show at once, kept as its
 * [savedForm][io.github.lcebot.ferryqr.history.savedForm] in a favorite's app shortcut; [onOpened]
 * is called once it has been shown or found missing.
 */
@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit, openSaved: String? = null, onOpened: () -> Unit = {}) {
    val haptics = rememberHaptics()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val showing by vm.showing.collectAsStateWithLifecycle()
    val card by vm.card.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val settings = vm.settings
    val folded by settings.favoritesFolded.flow.collectAsStateWithLifecycle()
    val tiles by settings.favoriteTiles.flow.collectAsStateWithLifecycle()
    val grid by settings.tileGrid.flow.collectAsStateWithLifecycle()
    val foldedRows by settings.foldedRows.flow.collectAsStateWithLifecycle()
    val tilesSuggested by settings.tilesSuggested.flow.collectAsStateWithLifecycle()
    val namesFavorites by settings.namesFavorites.flow.collectAsStateWithLifecycle()
    val layout = FavoriteLayout.of(tiles, grid)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val favorites = lists?.favorites.orEmpty()
    val favoritesByKey = lists?.favoritesByKey.orEmpty()
    val recent = lists?.recent.orEmpty()
    val count = lists?.all?.size ?: 0
    val keeper = remember(vm) { FavoriteOrderKeeper { vm.setOrder(it) } }
    val board = remember(haptics, keeper) { TileBoard(haptics, keeper) }
    val listState = rememberLazyListState()
    val listReorder = remember(listState, haptics, scope, keeper) {
        ListReorderState(listState, haptics, scope, ::rowKey, FAVORITES_HEADER_KEY, keeper)
    }
    val settleSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    // The count and the clear button are small parts of the bar, while what shows below it is the
    // whole page.
    val barFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val barResize = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    val pageFade = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val magnetic = remember(scope) { MagneticList(scope) }
    val favoriting = rememberFavoriteRequester()
    val messages = rememberHistoryMessages(vm, snackbar) { restored -> magnetic.undone(restored.map { it.text }) }

    LaunchedEffect(openSaved) {
        if (openSaved == null) return@LaunchedEffect
        vm.openSaved(openSaved)
        onOpened()
    }

    // Swipes kept for rows to fly back with last as long as the session. Once it ends, rows brought
    // back take up theirs as they are laid out after the lists hold them again, which lists made off
    // the main thread may take a few frames to do; entries gone for good never come back, so the wait
    // is bounded, and what is left then belongs to them.
    LaunchedEffect(undo == null) {
        if (undo != null) return@LaunchedEffect
        val kept = magnetic.departed.keys.toSet()
        if (kept.isNotEmpty()) {
            withTimeoutOrNull(DEPARTED_WAIT_MS) {
                snapshotFlow { lists }.first { current -> current != null && kept.all { text -> current.all.any { it.text == text } } }
            }
        }
        withFrameNanos {}
        withFrameNanos {}
        magnetic.departed.clear()
    }

    fun delete(entry: HistoryEntry) {
        haptics.confirm()
        messages.delete(listOf(entry))
    }

    // A day's entries go together, as one undoable deletion, so nothing asks first.
    fun clearDay(entries: List<HistoryEntry>) {
        haptics.confirm()
        messages.delete(entries)
    }

    // Made once for what they show, so rows that have not changed are not composed again.
    val shortcutsFull = favorites.count { it.shortcut } >= FavoriteShortcuts.MAX
    val topFavorite = favorites.firstOrNull()?.text
    val actions = remember(shortcutsFull, topFavorite, namesFavorites, favoriting, messages) {
        EntryActions(
            onOpen = { haptics.tap(); vm.open(it) },
            onFavorite = { entry, on -> favoriting.request(entry.content, on) },
            namesFavorites = namesFavorites,
            onNameAndFavorite = { favoriting.requestNamed(it.content) },
            onShortcut = { entry, on ->
                haptics.toggle(on)
                vm.setShortcut(entry, on)
                if (on) messages.show(R.string.shortcut_added)
            },
            shortcutsFull = shortcutsFull,
            onRename = { haptics.tap(); vm.show(HistoryDialog.Rename(it)) },
            onDelete = ::delete,
            topFavorite = topFavorite,
            onMoveToTop = { haptics.tap(); vm.moveToTop(it) },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                subtitle = { HistoryCount(count, barFade) },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }, shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    // The button's slot opens and closes as it fades, so the title beside it gains and
                    // loses room smoothly; unclipped, as the favorites' buttons are.
                    AnimatedVisibility(
                        visible = recent.isNotEmpty(),
                        enter = fadeIn(barFade) + expandHorizontally(barResize, expandFrom = Alignment.End, clip = false),
                        exit = fadeOut(barFade) + shrinkHorizontally(barResize, shrinkTowards = Alignment.End, clip = false),
                    ) {
                        IconButton(onClick = { haptics.tap(); vm.show(HistoryDialog.Clear) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(painterResource(R.drawable.ic_delete_sweep), stringResource(R.string.history_clear))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val content = when {
            lists == null -> HistoryContent.LOADING
            count == 0 -> HistoryContent.EMPTY
            else -> HistoryContent.LIST
        }
        // Nothing shows until the history is read, which then fades in, rather than its empty page
        // showing first and giving way to the entries.
        AnimatedContent(
            targetState = content,
            transitionSpec = { (fadeIn(pageFade) togetherWith fadeOut(pageFade)) using null },
            modifier = Modifier.fillMaxSize(),
            label = "history",
        ) { shown ->
            when (shown) {
                HistoryContent.LOADING -> Box(Modifier.fillMaxSize())
                HistoryContent.EMPTY -> EmptyHistory(Modifier.fillMaxSize().padding(padding))
                HistoryContent.LIST -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val groups = lists?.days.orEmpty()
                    val available = constraints.maxWidth - with(density) {
                        (padding.calculateStartPadding(layoutDirection) + padding.calculateEndPadding(layoutDirection) + 32.dp).roundToPx()
                    }
                    val section = rememberFavoritesSection(
                        favorites = favorites,
                        names = lists?.favoriteNames.orEmpty(),
                        layout = layout,
                        folded = folded,
                        foldedRows = foldedRows,
                        tilesSuggested = tilesSuggested,
                        available = available,
                        board = board,
                        listReorder = listReorder,
                    )

                    ReorderAutoScroll(listState, section.reorder, padding.calculateBottomPadding())

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
                            item(key = FAVORITES_HEADER_KEY) {
                                FavoritesHeader(
                                    foldable = section.foldable,
                                    folded = section.folded,
                                    hidden = section.hiddenCount,
                                    onToggle = {
                                        haptics.tap()
                                        vm.setFolded(!folded)
                                    },
                                    reorderable = section.reorderable,
                                    reordering = section.reordering,
                                    onReorder = {
                                        haptics.tap()
                                        if (listReorder.active) listReorder.finish() else listReorder.start(favorites)
                                    },
                                    modifier = animateRow(),
                                )
                            }
                            if (section.tiled) {
                                item(key = TILES_KEY) {
                                    FavoriteTiles(
                                        board = board,
                                        favorites = favoritesByKey,
                                        namesOf = { lists?.namesOf(it) },
                                        inputs = section.tiles,
                                        arrangement = section.arrangement,
                                        rowsShown = section.rowsShown,
                                        pendingUndo = undo?.texts.orEmpty(),
                                        list = magnetic,
                                        actions = actions,
                                        // Folded tiles unfold for one picked up, and stay unfolded after.
                                        onLift = { if (section.foldable && section.folded) vm.setFolded(false) },
                                        // Above the rows around it, so a tile dragged or swiped past the
                                        // favorites stays in sight over them.
                                        modifier = animateRow().zIndex(1f),
                                    )
                                }
                            } else {
                                val rows = section.rows
                                itemsIndexed(rows, key = { _, e -> rowKey(e.text) }) { index, entry ->
                                    val back = remember { magnetic.departed.containsKey(entry.text) }
                                    val reordering = listReorder.active
                                    val dragged = reordering && listReorder.dragging == entry.text
                                    val moveUp = stringResource(R.string.favorite_move_up)
                                    val moveDown = stringResource(R.string.favorite_move_down)
                                    EntryRow(
                                        entry = entry,
                                        listed = lists?.namesOf(entry),
                                        group = FAVORITES_GROUP,
                                        index = index,
                                        count = rows.size,
                                        list = magnetic,
                                        actions = actions,
                                        modifier = (if (dragged) Modifier else animateRow(flyingBack = back))
                                            .zIndex(if (dragged) 1f else 0f)
                                            .graphicsLayer { if (dragged) translationY = listReorder.dragOffset }
                                            // Without a finger to drag with, the order changes a row at a time.
                                            .then(
                                                if (!reordering) {
                                                    Modifier
                                                } else {
                                                    Modifier.semantics {
                                                        customActions = listOfNotNull(
                                                            CustomAccessibilityAction(moveUp) { listReorder.move(entry.text, index - 1); true }
                                                                .takeIf { index > 0 },
                                                            CustomAccessibilityAction(moveDown) { listReorder.move(entry.text, index + 1); true }
                                                                .takeIf { index < rows.size - 1 },
                                                        )
                                                    }
                                                },
                                            ),
                                        reorder = RowReorder(
                                            active = reordering,
                                            lifted = dragged && listReorder.settling == null,
                                            onDragStart = { listReorder.startDrag(entry.text) },
                                            onDrag = listReorder::drag,
                                            onDragEnd = { listReorder.endDrag(settleSpec) },
                                        ),
                                    )
                                }
                            }
                        }
                        groups.forEach { (day, items) ->
                            item(key = "header:$day") { DayHeader(day, items.first().time, onClear = { clearDay(items) }, animateRow()) }
                            itemsIndexed(items, key = { _, e -> rowKey(e.text) }) { index, entry ->
                                val back = remember { magnetic.departed.containsKey(entry.text) }
                                val listed = lists?.namesOf(entry)
                                EntryRow(entry, listed, day, index, items.size, magnetic, actions, animateRow(flyingBack = back))
                            }
                        }
                    }

                    val suggest = section.suggestTiles
                    LaunchedEffect(suggest) {
                        if (suggest) vm.suggestTiles()
                    }
                }
            }
        }
    }

    DamagedHistoryDialog(vm, show = lists != null, message = messages::show)

    HistoryDialogs(vm, dialog, message = messages::show)

    // History stays open after a handoff, so returning to the app lands back on the list.
    showing?.let { entry ->
        key(entry.text) {
            card?.let { ResultSheet(card = it, onClosed = { vm.open(null) }) }
        }
    }
}

/**
 * How many entries history holds, under its title. It fades in once the history is read and out
 * when the last entry goes, on [fade], showing the last count as it does; meanwhile it stays empty
 * but a line tall, so the title above keeps its place. A count that changes between two others
 * changes at once, in figures of one width, with the title kept still.
 */
@Composable
private fun HistoryCount(count: Int, fade: FiniteAnimationSpec<Float>) {
    AnimatedContent(
        targetState = count,
        contentKey = { it > 0 },
        transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)) using null },
        label = "history-count",
    ) { shown ->
        Text(
            if (shown > 0) pluralStringResource(R.plurals.history_count, shown, shown) else "",
            style = LocalTextStyle.current.withTabularFigures(),
        )
    }
}

/**
 * Rows and headers fade in and out and slide to their new places with the theme's motion. A row
 * [flyingBack] into its place, brought back by undo after it was swiped away, is in view as it comes
 * rather than fading in.
 */
@Composable
private fun LazyItemScope.animateRow(flyingBack: Boolean = false): Modifier = Modifier.animateItem(
    fadeInSpec = if (flyingBack) null else MaterialTheme.motionScheme.defaultEffectsSpec(),
    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
)

/**
 * The favorites' title, spaced from the list as a day's is, with its buttons at the end. The buttons
 * are taller than the title's line and centred on it, reaching into the space above and below
 * rather than pushing the favorites further away than the entries of a day are from theirs. Each
 * button comes and goes growing and fading in place while the other slides aside, on the fast
 * springs, as small parts of the page. A label that changes fades into the next while its button
 * resizes, without clipping, since the spring settles a little below a shorter label before it
 * comes to rest, which would cut off its end; the button's padding has room for it.
 */
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
    val motion = MaterialTheme.motionScheme
    val effects = motion.fastEffectsSpec<Float>()
    val spatial = motion.fastSpatialSpec<Float>()
    val resize = motion.fastSpatialSpec<IntSize>()
    val enter = fadeIn(effects) + scaleIn(spatial) + expandHorizontally(resize, expandFrom = Alignment.End, clip = false)
    val exit = fadeOut(effects) + scaleOut(spatial) + shrinkHorizontally(resize, shrinkTowards = Alignment.End, clip = false)
    SectionHeader(stringResource(R.string.favorites_title), modifier) {
        AnimatedVisibility(visible = foldable, enter = enter, exit = exit) {
            val turn by animateFloatAsState(
                targetValue = if (folded) 0f else 180f,
                animationSpec = spatial,
                label = "favorites-chevron",
            )
            TextButton(onClick = onToggle, shapes = ButtonDefaults.shapes()) {
                AnimatedContent(
                    targetState = folded,
                    transitionSpec = { (fadeIn(effects) togetherWith fadeOut(effects)) using SizeTransform(clip = false) { _, _ -> resize } },
                    label = "favorites-toggle",
                ) { isFolded ->
                    Text(
                        if (isFolded) pluralStringResource(R.plurals.favorites_show_more, hidden, hidden)
                        else stringResource(R.string.favorites_show_less),
                        style = LocalTextStyle.current.withTabularFigures(),
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
        AnimatedVisibility(visible = reorderable || reordering, enter = enter, exit = exit) {
            TextButton(onClick = onReorder, shapes = ButtonDefaults.shapes()) {
                AnimatedContent(
                    targetState = reordering,
                    transitionSpec = { (fadeIn(effects) togetherWith fadeOut(effects)) using SizeTransform(clip = false) { _, _ -> resize } },
                    label = "favorites-reorder",
                ) { isReordering ->
                    Text(stringResource(if (isReordering) R.string.favorites_reorder_done else R.string.favorites_reorder))
                }
            }
        }
    }
}

/** A day's header, with a button that deletes the day's entries at once, as one undoable deletion. */
@Composable
private fun DayHeader(day: LocalDate, sample: Long, onClear: () -> Unit, modifier: Modifier = Modifier) {
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
    SectionHeader(label, modifier) {
        TextButton(onClick = onClear, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.history_clear_day))
        }
    }
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
