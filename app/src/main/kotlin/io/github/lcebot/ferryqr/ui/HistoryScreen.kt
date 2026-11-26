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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.lerp
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.FavoriteLayout
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
import androidx.collection.intListOf
import kotlin.math.roundToInt
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.MenuAnchorPosition
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.IconButtonDefaults
import io.github.lcebot.ferryqr.HistoryDialog
import io.github.lcebot.ferryqr.HistoryViewModel
import io.github.lcebot.ferryqr.dispatch.Kind
import io.github.lcebot.ferryqr.dispatch.ResultCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The corners of a segmented list, as the expressive list tokens give them: the group's outer
 * corners large, 16dp, and those between its rows extra small, 4dp. A pressed row rounds to the
 * large corner all round, so the outer corners stay put as it morphs.
 */
internal val OUTER_CORNER = 16.dp
internal val INNER_CORNER = 4.dp

/** The group the favorites form for a swipe; the other rows group by day. */
private const val FAVORITES_GROUP = "favorites"

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
    val activity = LocalActivity.current ?: return
    val haptics = rememberHaptics()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val showing by vm.showing.collectAsStateWithLifecycle()
    val pendingUndo by vm.pendingUndo.collectAsStateWithLifecycle()
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
    val ready = ferriesReady()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val favorites = lists?.favorites.orEmpty()
    val favoritesByKey = lists?.favoritesByKey.orEmpty()
    val recent = lists?.recent.orEmpty()
    val count = lists?.all?.size ?: 0
    val board = remember(haptics) { TileBoard(haptics) }
    val listState = rememberLazyListState()
    val reorder = remember(listState, haptics, scope) { ListReorderState(listState, haptics, scope, ::rowKey) }
    LaunchedEffect(favorites) { reorder.sync(favorites) }
    val settleSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val magnetic = remember(scope) { MagneticList(scope) }
    val subtitle: (@Composable () -> Unit)? = if (count == 0) {
        null
    } else {
        { Text(pluralStringResource(R.plurals.history_count, count, count)) }
    }

    LaunchedEffect(openText) {
        if (openText == null) return@LaunchedEffect
        vm.open(openText)
        onOpened()
    }

    val latestTiled by rememberUpdatedState(tiled)
    fun delete(entry: HistoryEntry) {
        haptics.confirm()
        vm.delete(entry)
        val tile = latestTiled && entry.favorite
        scope.launch {
            val result = snackbar.showLatest(
                message = context.getString(R.string.history_deleted),
                actionLabel = context.getString(R.string.undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                haptics.tap()
                vm.undoDelete(entry)
            } else {
                vm.deleteDone(entry)
                if (tile) board.forget(entry.text)
            }
        }
    }

    // Made once for what they show, so rows that have not changed are not composed again.
    val shortcutsFull = favorites.count { it.shortcut } >= FavoriteShortcuts.MAX
    val topFavorite = favorites.firstOrNull()?.text
    val actions = remember(shortcutsFull, topFavorite, namesFavorites) {
        EntryActions(
            onOpen = { haptics.tap(); vm.open(it) },
            onFavorite = { entry, on ->
                val named = hasTitle(context, entry.text, entry.format, entry.label)
                // Asked for a name first, the entry becomes a favorite only once it has one.
                if (!on || !settings.namesFavorites.value || named) haptics.toggle(on)
                vm.setFavorite(entry, on, named)
            },
            namesFavorites = namesFavorites,
            onNameAndFavorite = { haptics.tap(); vm.show(HistoryDialog.Name(it)) },
            onShortcut = { entry, on ->
                haptics.toggle(on)
                vm.setShortcut(entry, on)
                if (on) scope.launch { snackbar.showLatest(context.getString(R.string.shortcut_added)) }
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
                subtitle = subtitle,
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }, shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    AnimatedVisibility(visible = recent.isNotEmpty(), enter = fadeIn(fade), exit = fadeOut(fade)) {
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
            transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)) using null },
            modifier = Modifier.fillMaxSize(),
            label = "history",
        ) { shown ->
            when (shown) {
                HistoryContent.LOADING -> Box(Modifier.fillMaxSize())
                HistoryContent.EMPTY -> EmptyHistory(Modifier.fillMaxSize().padding(padding))
                HistoryContent.LIST -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val zone = remember { ZoneId.systemDefault() }
                    val density = LocalDensity.current
                    val groups = remember(recent) {
                        recent.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
                    }
                    // Folded, the favorites show their first few rows, which in the list are favorites.
                    val foldable = favorites.size > foldedRows && !reorder.active
                    val ordered = if (reorder.active) reorder.order.mapNotNull { favoritesByKey[it] } else favorites
                    val shownFavorites = if (foldable && folded) ordered.take(foldedRows) else ordered

                    // Tiles are as wide as their names, measured here so the rows they fill are known
                    // before they are laid out, for folding them and for suggesting them.
                    val gap = with(density) { ListItemDefaults.SegmentedGap.roundToPx() }
                    val typography = MaterialTheme.typography
                    val available = constraints.maxWidth - with(density) {
                        (padding.calculateStartPadding(layoutDirection) + padding.calculateEndPadding(layoutDirection) + 32.dp).roundToPx()
                    }
                    val measurer = rememberTextMeasurer()
                    val chrome = with(density) { tileChrome(layoutDirection).roundToPx() }
                    // A tile is as wide as the longer of its name and its kind, the two lines it shows.
                    val presentations = remember(favorites) { favorites.associate { it.text to presentationOf(context, it) } }
                    val tileWidths = remember(presentations, typography, measurer, chrome) {
                        presentations.mapValues { (_, shown) ->
                            val name = measurer.measure(shown.tileTitle, typography.bodyLarge, maxLines = 1, softWrap = false).size.width
                            val kind = measurer.measure(context.getString(shown.kind.label), typography.bodyMedium, maxLines = 1, softWrap = false)
                                .size.width
                            max(name, kind) + chrome
                        }
                    }
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
                            item(key = "header:favorites") {
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
                                        if (reorder.active) vm.setOrder(reorder.finish()) else reorder.start(favorites)
                                    },
                                    modifier = animateRow(),
                                )
                            }
                            if (tiled) {
                                item(key = TILES_KEY) {
                                    FavoriteTiles(
                                        board = board,
                                        favorites = favoritesByKey,
                                        inputs = tileInputs,
                                        arrangement = tileArrangement,
                                        rowsShown = rowsShown,
                                        pendingUndo = pendingUndo,
                                        list = magnetic,
                                        actions = actions,
                                        onShift = { entry, by ->
                                            haptics.step()
                                            vm.move(entry, by)
                                        },
                                        // Folded tiles unfold for one picked up, and stay unfolded after.
                                        onLift = { if (tilesFoldable && folded) vm.setFolded(false) },
                                        onReorder = { vm.setOrder(board.order.toList()) },
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
                                    EntryRow(entry, FAVORITES_GROUP, index, shownFavorites.size, magnetic, actions, animateRow())
                                }
                            }
                        }
                        groups.forEach { (day, items) ->
                            item(key = "header:$day") { DayHeader(day, items.first().time, animateRow()) }
                            itemsIndexed(items, key = { _, e -> rowKey(e.text) }) { index, entry ->
                                EntryRow(entry, day, index, items.size, magnetic, actions, animateRow())
                            }
                        }
                    }

                    // Tiles fit as many favorites in a row as their names allow; once there are enough
                    // favorites that tiles would at least halve their rows, history suggests them, once.
                    val tileRows = remember(favorites, tileWidths, available, gap) {
                        arrangeTiles(favorites.map { it.text }, { tileWidths[it] ?: 0 }, available, gap, null).rows
                    }
                    val worthTiles = !tiled && !tilesSuggested && !reorder.active && favorites.size >= SUGGEST_TILES_AT &&
                        tileRows * 2 <= favorites.size
                    LaunchedEffect(worthTiles) {
                        if (worthTiles) vm.suggestTiles()
                    }
                }
            }
        }
    }

    when (val shown = dialog) {
        HistoryDialog.Clear -> AlertDialog(
            onDismissRequest = { vm.show(null) },
            icon = { Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = null) },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.confirm()
                        vm.clear()
                        scope.launch { snackbar.showLatest(context.getString(R.string.history_cleared)) }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { haptics.tap(); vm.show(null) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
        HistoryDialog.SuggestTiles -> AlertDialog(
            onDismissRequest = { vm.show(null) },
            icon = { Icon(painterResource(R.drawable.ic_view_quilt), contentDescription = null) },
            title = { Text(stringResource(R.string.tiles_suggest_title)) },
            text = { Text(stringResource(R.string.tiles_suggest_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.confirm()
                        vm.useTiles()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.tiles_suggest_use)) }
            },
            dismissButton = {
                TextButton(onClick = { haptics.tap(); vm.show(null) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.tiles_suggest_keep))
                }
            },
        )
        is HistoryDialog.Name -> NameFavoriteDialog(
            current = shown.entry.label ?: defaultTitle(context, shown.entry.text, shown.entry.format),
            onDismiss = { vm.show(null) },
            onName = { name -> vm.name(shown.entry, name) },
        )
        is HistoryDialog.Rename -> RenameDialog(
            current = shown.entry.label ?: defaultTitle(context, shown.entry.text, shown.entry.format),
            onDismiss = { vm.show(null) },
            onRename = { name -> vm.rename(shown.entry, name) },
        )
        null -> Unit
    }

    // History stays open after a handoff, so returning to the app lands back on the list.
    val onResult = rememberActionResultHandler(onHandoff = { vm.open(null) }) { message ->
        scope.launch { snackbar.showLatest(context.getString(message)) }
    }

    showing?.let { entry ->
        key(entry.text) {
            // The original code is no longer in sight, so every entry is shown with its code drawn
            // again, to be scanned by another phone or kept as an image. The card is built off the
            // main thread, and again once the framework reports which apps take content.
            val card by produceState<ResultCard?>(null, entry, ready) {
                value = withContext(Dispatchers.Default) {
                    val payload = Classifier.classify(entry.text, entry.format)
                    val built = Cards.build(activity, payload, entry.format, Origin.HISTORY, scannedAt = entry.time)
                    val image = built.image ?: CodeGenerator.redraw(entry.text, entry.format)
                    built.copy(image = image, exportable = image != null)
                }
            }
            card?.let {
                ResultSheet(
                    card = it,
                    onDismiss = { vm.open(null) },
                    onAction = { action -> onResult(action.run(activity)) },
                )
            }
        }
    }
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
    // On the fast spatial motion the list item morphs its own shape on.
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val top by animateDpAsState(if (index == 0) OUTER_CORNER else INNER_CORNER, spec, label = "row-top")
    val bottom by animateDpAsState(if (index == count - 1) OUTER_CORNER else INNER_CORNER, spec, label = "row-bottom")
    val shownTop = maxOf(top, lerp(INNER_CORNER, OUTER_CORNER, roundTop.coerceIn(0f, 1f)))
    val shownBottom = maxOf(bottom, lerp(INNER_CORNER, OUTER_CORNER, roundBottom.coerceIn(0f, 1f)))
    return RoundedCornerShape(topStart = shownTop, topEnd = shownTop, bottomStart = shownBottom, bottomEnd = shownBottom)
}
