@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.text.format.DateUtils
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.FavoriteLayout
import io.github.lcebot.ferryqr.HistoryDialog
import io.github.lcebot.ferryqr.HistoryViewModel
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.CardSource
import io.github.lcebot.ferryqr.dispatch.Origin
import io.github.lcebot.ferryqr.history.FavoriteShortcuts
import io.github.lcebot.ferryqr.history.HistoryEntry
import java.time.LocalDate

/** The group the favorites form for a swipe; the other rows group by day. */
private const val FAVORITES_GROUP = "favorites"

/** The list item holding the favorites' title. */
private const val FAVORITES_HEADER_KEY = "header:favorites"

/** The list item holding the favorites as tiles. */
internal const val TILES_KEY = "favorites:tiles"

/**
 * From this many favorites, where tiles would take at most half the rows the list does, history
 * suggests tiles once.
 */
private const val SUGGEST_TILES_AT = 6

/** The key of the list item showing the entry with [text]. */
private fun rowKey(text: String): Any = "entry:$text"

/** What the history page shows below its top bar. */
private enum class HistoryContent { LOADING, EMPTY, LIST }

/**
 * [openText] is content to show at once, from a favorite's app shortcut; [onOpened] is called once
 * it has been shown or found missing.
 */
@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit, openText: String? = null, onOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val showing by vm.showing.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val settings = vm.settings
    val folded by settings.favoritesFolded.flow.collectAsStateWithLifecycle()
    val tiles by settings.favoriteTiles.flow.collectAsStateWithLifecycle()
    val grid by settings.tileGrid.flow.collectAsStateWithLifecycle()
    val foldedRows by settings.foldedRows.flow.collectAsStateWithLifecycle()
    val tilesSuggested by settings.tilesSuggested.flow.collectAsStateWithLifecycle()
    val namesFavorites by settings.namesFavorites.flow.collectAsStateWithLifecycle()
    val layout = FavoriteLayout.of(tiles, grid)
    val tiled = layout != FavoriteLayout.LIST
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
    val reorder = remember(listState, haptics, scope, keeper) {
        ListReorderState(listState, haptics, scope, ::rowKey, FAVORITES_HEADER_KEY, keeper)
    }
    LaunchedEffect(favorites) { reorder.sync(favorites) }
    val settleSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    // The count and the clear button are small parts of the bar, while what shows below it is the
    // whole page.
    val barFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val pageFade = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val magnetic = remember(scope) { MagneticList(scope) }
    val favoriting = rememberFavoriteRequester()

    LaunchedEffect(openText) {
        if (openText == null) return@LaunchedEffect
        vm.open(openText)
        onOpened()
    }

    // Only one snackbar shows at a time. The offer to undo follows the view model's session, so each
    // deletion replaces it with one counting every entry since the chance to undo last passed, and a
    // page composed again offers it again. A message takes its place while it shows, and the offer
    // comes back once the message has gone. The offer stays until it is acted on or closed, as a
    // snackbar with an action does; a newer offer or message cancels the one showing.
    var note by remember { mutableStateOf<Note?>(null) }
    LaunchedEffect(note, undo) {
        val shown = note
        if (shown != null) {
            snackbar.showLatest(context.getString(shown.text))
            note = null
            return@LaunchedEffect
        }
        val session = undo ?: return@LaunchedEffect
        val count = session.entries.size
        val result = snackbar.showLatest(
            message = if (count == 1) {
                context.getString(R.string.history_deleted)
            } else {
                context.resources.getQuantityString(R.plurals.history_deleted_count, count, count)
            },
            actionLabel = context.getString(R.string.undo),
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) {
            haptics.tap()
            vm.undoDeletes()
        } else {
            vm.deletesDone()
        }
    }
    // Swipes kept for rows to fly back with last as long as the session: once it ends, rows brought
    // back have taken up theirs as they were laid out, and any left belong to entries gone for good.
    LaunchedEffect(undo == null) {
        if (undo != null) return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        magnetic.departed.clear()
    }

    fun delete(entry: HistoryEntry) {
        haptics.confirm()
        note = null
        vm.delete(entry)
    }

    fun showMessage(@StringRes message: Int) {
        note = Note(message)
    }

    // Made once for what they show, so rows that have not changed are not composed again.
    val shortcutsFull = favorites.count { it.shortcut } >= FavoriteShortcuts.MAX
    val topFavorite = favorites.firstOrNull()?.text
    val actions = remember(shortcutsFull, topFavorite, namesFavorites, favoriting) {
        EntryActions(
            onOpen = { haptics.tap(); vm.open(it) },
            onFavorite = { entry, on -> favoriting.request(entry.content, on) },
            namesFavorites = namesFavorites,
            onNameAndFavorite = { favoriting.requestNamed(it.content) },
            onShortcut = { entry, on ->
                haptics.toggle(on)
                vm.setShortcut(entry, on)
                if (on) showMessage(R.string.shortcut_added)
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
                    AnimatedVisibility(visible = recent.isNotEmpty(), enter = fadeIn(barFade), exit = fadeOut(barFade)) {
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
                    // Folded, the favorites show their first few rows, which in the list are favorites.
                    val foldable = favorites.size > foldedRows && !reorder.active
                    val ordered = if (reorder.active) reorder.order.mapNotNull { favoritesByKey[it] } else favorites
                    val shownFavorites = if (foldable && folded) ordered.take(foldedRows) else ordered

                    // Tiles are as wide as their names, measured here so the rows they fill are known
                    // before they are laid out, for folding them and for suggesting them.
                    val gap = with(density) { ListItemDefaults.SegmentedGap.roundToPx() }
                    val available = constraints.maxWidth - with(density) {
                        (padding.calculateStartPadding(layoutDirection) + padding.calculateEndPadding(layoutDirection) + 32.dp).roundToPx()
                    }
                    val tileWidths = rememberTileWidths(
                        lists?.favoritePresentations.orEmpty(),
                        with(density) { tileChrome(layoutDirection).roundToPx() },
                    )
                    val tileKeys = if (board.key != null) board.order.filter { it in favoritesByKey } else favorites.map { it.text }
                    val columns = if (layout == FavoriteLayout.GRID) gridColumns(available, gap, with(density) { GRID_COLUMN.roundToPx() }) else null
                    val tileInputs = TileInputs(tileKeys, tileWidths, available, gap, columns)
                    val tileArrangement = remember(tileInputs) { tileInputs.arrange() }
                    val tilesFoldable = tiled && tileArrangement.rows > foldedRows
                    val rowsShown = if (tilesFoldable && folded) foldedRows else tileArrangement.rows
                    SideEffect { board.update(tileInputs, rowsShown) }

                    ReorderAutoScroll(listState, if (tiled) board else reorder, padding.calculateBottomPadding())

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
                                    foldable = if (tiled) tilesFoldable else foldable,
                                    folded = folded,
                                    hidden = if (tiled) {
                                        tileArrangement.spots.count { it.row >= foldedRows }
                                    } else {
                                        favorites.size - foldedRows
                                    },
                                    onToggle = {
                                        haptics.tap()
                                        vm.setFolded(!folded)
                                    },
                                    // Tiles are dragged straight away, so only the list has a mode for it.
                                    reorderable = !tiled && favorites.size > 1,
                                    reordering = reorder.active && !tiled,
                                    onReorder = {
                                        haptics.tap()
                                        if (reorder.active) reorder.finish() else reorder.start(favorites)
                                    },
                                    modifier = animateRow(),
                                )
                            }
                            if (tiled) {
                                item(key = TILES_KEY) {
                                    FavoriteTiles(
                                        board = board,
                                        favorites = favoritesByKey,
                                        presentationOf = { lists?.presentationOf(it) },
                                        inputs = tileInputs,
                                        arrangement = tileArrangement,
                                        rowsShown = rowsShown,
                                        pendingUndo = undo?.texts.orEmpty(),
                                        list = magnetic,
                                        actions = actions,
                                        // Folded tiles unfold for one picked up, and stay unfolded after.
                                        onLift = { if (tilesFoldable && folded) vm.setFolded(false) },
                                        // Above the rows around it, so a tile dragged or swiped past the
                                        // favorites stays in sight over them.
                                        modifier = animateRow().zIndex(1f),
                                    )
                                }
                            } else itemsIndexed(shownFavorites, key = { _, e -> rowKey(e.text) }) { index, entry ->
                                if (reorder.active) {
                                    val dragged = reorder.dragging == entry.text
                                    val moveUp = stringResource(R.string.favorite_move_up)
                                    val moveDown = stringResource(R.string.favorite_move_down)
                                    ReorderRow(
                                        entry = entry,
                                        listed = lists?.presentationOf(entry),
                                        shape = segmentShape(index, shownFavorites.size),
                                        lifted = dragged && reorder.settling == null,
                                        onDragStart = { reorder.startDrag(entry.text) },
                                        onDrag = reorder::drag,
                                        onDragEnd = { reorder.endDrag(settleSpec) },
                                        modifier = (if (dragged) Modifier else animateRow())
                                            .zIndex(if (dragged) 1f else 0f)
                                            .graphicsLayer { if (dragged) translationY = reorder.dragOffset }
                                            // Without a finger to drag with, the order changes a row at a time.
                                            .semantics {
                                                customActions = listOfNotNull(
                                                    CustomAccessibilityAction(moveUp) { reorder.move(entry.text, index - 1); true }
                                                        .takeIf { index > 0 },
                                                    CustomAccessibilityAction(moveDown) { reorder.move(entry.text, index + 1); true }
                                                        .takeIf { index < shownFavorites.size - 1 },
                                                )
                                            },
                                    )
                                } else {
                                    val back = remember { magnetic.departed.containsKey(entry.text) }
                                    EntryRow(
                                        entry,
                                        lists?.presentationOf(entry),
                                        FAVORITES_GROUP,
                                        index,
                                        shownFavorites.size,
                                        magnetic,
                                        actions,
                                        animateRow(flyingBack = back),
                                    )
                                }
                            }
                        }
                        groups.forEach { (day, items) ->
                            item(key = "header:$day") { DayHeader(day, items.first().time, animateRow()) }
                            itemsIndexed(items, key = { _, e -> rowKey(e.text) }) { index, entry ->
                                val back = remember { magnetic.departed.containsKey(entry.text) }
                                val listed = lists?.presentationOf(entry)
                                EntryRow(entry, listed, day, index, items.size, magnetic, actions, animateRow(flyingBack = back))
                            }
                        }
                    }

                    // Tiles fit as many favorites in a row as their names allow; once there are enough
                    // favorites that tiles would at least halve their rows, history suggests them, once.
                    // The tiles are arranged for that only while the suggestion can still be made.
                    val suggestible = !tiled && !tilesSuggested && !reorder.active && favorites.size >= SUGGEST_TILES_AT
                    val worthTiles = if (suggestible) {
                        val tileRows = remember(favorites, tileWidths, available, gap) {
                            arrangeTiles(favorites.map { it.text }, { tileWidths[it] ?: 0 }, available, gap, null).rows
                        }
                        tileRows * 2 <= favorites.size
                    } else {
                        false
                    }
                    LaunchedEffect(worthTiles) {
                        if (worthTiles) vm.suggestTiles()
                    }
                }
            }
        }
    }

    DamagedHistoryDialog(vm, show = lists != null, message = ::showMessage)

    HistoryDialogs(vm, dialog, message = ::showMessage)

    // History stays open after a handoff, so returning to the app lands back on the list; the card
    // slides away as it would when dismissed rather than vanishing as the other app opens.
    var closing by remember(showing?.text) { mutableStateOf(false) }

    showing?.let { entry ->
        key(entry.text) {
            val card = rememberResultCard(remember(entry) { CardSource.Read(entry.content, Origin.HISTORY, entry.time) })
            card?.let {
                ResultSheet(
                    card = it,
                    onDismiss = {
                        closing = false
                        vm.open(null)
                    },
                    onHandoff = { closing = true },
                    closing = closing,
                )
            }
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

/** A message for the snackbar; each is its own, so the same message shown twice shows again. */
private class Note(@StringRes val text: Int)

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
 * springs, as small parts of the page.
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
    val title = MaterialTheme.typography.titleSmall
    val motion = MaterialTheme.motionScheme
    val effects = motion.fastEffectsSpec<Float>()
    val spatial = motion.fastSpatialSpec<Float>()
    val resize = motion.fastSpatialSpec<IntSize>()
    val enter = fadeIn(effects) + scaleIn(spatial) + expandHorizontally(resize, expandFrom = Alignment.End, clip = false)
    val exit = fadeOut(effects) + scaleOut(spatial) + shrinkHorizontally(resize, shrinkTowards = Alignment.End, clip = false)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = LIST_TEXT_INSET, top = SECTION_TOP, bottom = SECTION_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.favorites_title),
            style = title,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        val line = with(LocalDensity.current) { title.lineHeight.roundToPx() }
        Row(
            modifier = Modifier.layout { measurable, constraints ->
                val buttons = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                layout(buttons.width, line) { buttons.place(0, (line - buttons.height) / 2) }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(visible = foldable, enter = enter, exit = exit) {
                val turn by animateFloatAsState(
                    targetValue = if (folded) 0f else 180f,
                    animationSpec = spatial,
                    label = "favorites-chevron",
                )
                TextButton(onClick = onToggle, shapes = ButtonDefaults.shapes()) {
                    AnimatedContent(
                        targetState = folded,
                        transitionSpec = { (fadeIn(effects) togetherWith fadeOut(effects)) using SizeTransform { _, _ -> resize } },
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
                    Text(stringResource(if (reordering) R.string.favorites_reorder_done else R.string.favorites_reorder))
                }
            }
        }
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
    SectionHeader(label, modifier)
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
