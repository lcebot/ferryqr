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

/**
 * The corners of a segmented list, as the expressive list tokens give them: the group's outer
 * corners large, 16dp, and those between its rows extra small, 4dp. A pressed row rounds to the
 * large corner all round, so the outer corners stay put as it morphs.
 */
internal val OUTER_CORNER = 16.dp
internal val INNER_CORNER = 4.dp

/**
 * How close to the top or bottom of the visible list a favorite being dragged makes the list scroll
 * toward the favorites out of view: 48dp, the edge zone of the Reorderable library for Compose and
 * the size of a touch target. Scrolling stops once the first or last favorite is as far inside the
 * edge, so the row dragged to it rests outside the zone. Material leaves this to the app.
 */
private val REORDER_EDGE = 48.dp

/**
 * How fast the list scrolls per second with the row at the edge or past it, slower in proportion
 * the less far into [REORDER_EDGE] it is: RecyclerView's ItemTouchHelper scrolls at most 20dp a
 * frame, which is this at 60 frames a second.
 */
private val REORDER_SCROLL_SPEED = 1200.dp

/**
 * Nearing where it stops, the list scrolls at most this many times the distance left each second,
 * so it eases into place instead of halting at full speed.
 */
private const val REORDER_SCROLL_EASE = 10f

/** The group the favorites form for a swipe; the other rows group by day. */
private const val FAVORITES_GROUP = "favorites"

/** The list item holding the favorites as tiles. */
private const val TILES_KEY = "favorites:tiles"

/**
 * From this many favorites, where tiles would take at most half the rows the list does, history
 * suggests tiles once.
 */
private const val SUGGEST_TILES_AT = 6

private const val PREFS = "history"
private const val PREF_FAVORITES_FOLDED = "favorites_folded"
private const val PREF_TILES_SUGGESTED = "tiles_suggested"

/**
 * Where a favorite being dragged is drawn and how far the favorites reach past the top and bottom
 * edges of the visible list, in the list's pixels.
 */
private class HeldReach(val top: Float, val bottom: Float, val above: Float, val below: Float)

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
    val favoritesByKey = remember(favorites) { favorites.associateBy { it.text } }
    var layout by remember { mutableStateOf(AppSettings.favoriteLayout(context)) }
    var namesFavorites by remember { mutableStateOf(AppSettings.namesFavorites(context)) }
    var foldedRows by remember { mutableIntStateOf(AppSettings.foldedRows(context)) }
    // The settings can change while history waits behind another page.
    LifecycleResumeEffect(Unit) {
        layout = AppSettings.favoriteLayout(context)
        namesFavorites = AppSettings.namesFavorites(context)
        foldedRows = AppSettings.foldedRows(context)
        onPauseOrDispose {}
    }
    val tiled = layout != FavoriteLayout.LIST
    var naming by remember { mutableStateOf<HistoryEntry?>(null) }
    var suggestTiles by remember { mutableStateOf(false) }
    var tilesSuggested by remember { mutableStateOf(prefs.getBoolean(PREF_TILES_SUGGESTED, false)) }
    val board = remember(haptics) { TileBoard(haptics) }
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
    val magnetic = remember(scope) { MagneticList(scope) }
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
        // A tile deleted waits for the undo to pass, so undoing brings it back from where it went.
        val tile = tiled && entry.favorite
        if (tile) board.undoable.add(entry.text)
        scope.launch {
            val result = snackbar.showLatest(
                message = context.getString(R.string.history_deleted),
                actionLabel = context.getString(R.string.undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                haptics.tap()
                store.restore(entry)
                board.undoable.remove(entry.text)
            } else if (tile) {
                board.forget(entry.text)
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
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val zone = remember { ZoneId.systemDefault() }
            val density = LocalDensity.current
            val groups = remember(recent) {
                recent.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
            }
            // Folded, the favorites show their first few rows, which in the list are favorites.
            val foldable = favorites.size > foldedRows && !reordering
            val ordered = if (reordering) order.mapNotNull { text -> favorites.find { it.text == text } } else favorites
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
            val tileTexts = remember(favorites) {
                favorites.associate {
                    val kind = context.getString(Cards.kindOf(Classifier.classify(it.text, it.format)).label)
                    it.text to (tileTitle(context, it) to kind)
                }
            }
            val tileWidths = remember(tileTexts, typography, measurer, chrome) {
                tileTexts.mapValues { (_, texts) ->
                    val name = measurer.measure(texts.first, typography.bodyLarge, maxLines = 1, softWrap = false).size.width
                    val kind = measurer.measure(texts.second, typography.bodyMedium, maxLines = 1, softWrap = false).size.width
                    max(name, kind) + chrome
                }
            }
            board.keys = if (board.key != null) board.order.filter { it in favoritesByKey } else favorites.map { it.text }
            board.widths = tileWidths
            board.available = available
            board.gap = gap
            board.columns = if (layout == FavoriteLayout.GRID) gridColumns(available, gap, with(density) { GRID_COLUMN.roundToPx() }) else null
            val tileArrangement = board.arrange()
            val tilesFoldable = tiled && tileArrangement.rows > foldedRows
            board.rowsShown = if (tilesFoldable && folded) foldedRows else tileArrangement.rows

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

            // While a favorite is held near the top or bottom of the visible list, and favorites
            // reach past that edge, the list scrolls toward them. Only the list's content moves: the
            // held row or tile stays under the finger, taking each scroll off its drag, the top bar
            // keeps its size since the scroll does not pass through it, and the list goes no further
            // than the first or last favorite plus [REORDER_EDGE]. It stops as the finger lifts.
            val edge = with(density) { REORDER_EDGE.toPx() }
            val fastest = with(density) { REORDER_SCROLL_SPEED.toPx() }
            // The list is drawn behind the navigation bar, so its edge for scrolling is the top of the bar.
            val barHeight = with(density) { padding.calculateBottomPadding().toPx() }
            fun rowReach(info: LazyListLayoutInfo, held: String, bottom: Float): HeldReach? {
                val rows = info.visibleItemsInfo
                val row = rows.firstOrNull { it.key == "entry:$held" } ?: return null
                if (order.isEmpty()) return null
                val top = row.offset + dragOffset
                // The held row is a favorite in view, so the first one out of view lies above and the
                // last one below.
                val first = rows.firstOrNull { it.key == "entry:${order.first()}" }
                val lastRow = rows.firstOrNull { it.key == "entry:${order.last()}" }
                return HeldReach(
                    top = top,
                    bottom = top + row.size,
                    above = first?.let { -it.offset.toFloat() } ?: Float.POSITIVE_INFINITY,
                    below = lastRow?.let { it.offset + it.size - bottom } ?: Float.POSITIVE_INFINITY,
                )
            }
            fun tileReach(info: LazyListLayoutInfo, bottom: Float): HeldReach? {
                val block = info.visibleItemsInfo.firstOrNull { it.key == TILES_KEY } ?: return null
                val top = block.offset + board.at.y
                return HeldReach(top, top + board.size.height, -block.offset.toFloat(), block.offset + block.size - bottom)
            }
            val listHeld = dragging.takeIf { reordering && settling == null }
            val tileHeld = board.key.takeIf { tiled && board.lifted && board.moved }
            val held = listHeld ?: tileHeld
            LaunchedEffect(held, tiled, edge, fastest, barHeight) {
                if (held == null) return@LaunchedEffect
                // The way the list has been scrolling, which it keeps up until the margin even once
                // the favorites no longer reach past the edge.
                var going = 0
                var last = 0L
                while (true) {
                    val now = withFrameNanos { it }
                    val seconds = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                    last = now
                    val info = listState.layoutInfo
                    // Offset 0 is the top of the visible list, just below the top bar; the bottom is
                    // where the navigation bar starts covering the list, which runs on behind it.
                    val bottom = info.viewportEndOffset - barHeight
                    // How far the favorites reach past each edge.
                    val reach = if (tiled) tileReach(info, bottom) else rowReach(info, held, bottom)
                    if (reach == null) {
                        going = 0
                        continue
                    }
                    val upDepth = (edge - reach.top) / edge
                    val downDepth = (reach.bottom - (bottom - edge)) / edge
                    val way = when {
                        upDepth > 0f && (reach.above > 0f || (going < 0 && reach.above > -edge)) -> -1
                        downDepth > 0f && (reach.below > 0f || (going > 0 && reach.below > -edge)) -> 1
                        else -> 0
                    }
                    going = way
                    if (way == 0 || seconds == 0f) continue
                    val depth = (if (way < 0) upDepth else downDepth).coerceIn(0f, 1f)
                    // Slows on the way to the margin rather than stopping at full speed.
                    val left = (if (way < 0) reach.above else reach.below) + edge
                    // Within half a pixel the list has arrived, so the easing does not creep on forever.
                    if (left < 0.5f) continue
                    val speed = min(fastest * depth, left * REORDER_SCROLL_EASE)
                    if (speed <= 0f) continue
                    val consumed = listState.scrollBy(way * speed * seconds)
                    if (tiled) {
                        board.scrolled(consumed)
                    } else {
                        dragOffset += consumed
                        followDrag()
                    }
                }
            }
            val actions = EntryActions(
                onOpen = { haptics.tap(); open(it) },
                onFavorite = { entry, on ->
                    if (on && namesFavorites && !hasTitle(context, entry.text, entry.format, entry.label)) {
                        naming = entry
                    } else {
                        haptics.toggle(on)
                        store.setFavorite(entry.text, entry.format, on)
                    }
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
                            foldable = if (tiled) tilesFoldable else foldable,
                            folded = folded,
                            hidden = if (tiled) {
                                tileArrangement.spots.count { it.row >= foldedRows }
                            } else {
                                favorites.size - foldedRows
                            },
                            onToggle = {
                                haptics.tap()
                                folded = !folded
                                prefs.edit().putBoolean(PREF_FAVORITES_FOLDED, folded).apply()
                            },
                            // Tiles are dragged straight away, so only the list has a mode for it.
                            reorderable = !tiled && favorites.size > 1,
                            reordering = reordering && !tiled,
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
                    if (tiled) {
                        item(key = TILES_KEY) {
                            FavoriteTiles(
                                board = board,
                                favorites = favoritesByKey,
                                arrangement = tileArrangement,
                                list = magnetic,
                                actions = actions,
                                onShift = { entry, by ->
                                    val index = favorites.indexOfFirst { it.text == entry.text }
                                    if (index >= 0) {
                                        haptics.step()
                                        store.moveFavorite(entry.text, index + by)
                                    }
                                },
                                // Folded tiles unfold for one picked up, and stay unfolded after.
                                onLift = {
                                    if (tilesFoldable && folded) {
                                        folded = false
                                        prefs.edit().putBoolean(PREF_FAVORITES_FOLDED, false).apply()
                                    }
                                },
                                onReorder = { store.setFavoriteOrder(board.order.toList()) },
                                // Above the rows around it, so a tile dragged or swiped past the
                                // favorites stays in sight over them.
                                modifier = animateRow().zIndex(1f),
                            )
                        }
                    } else itemsIndexed(shownFavorites, key = { _, e -> "entry:${e.text}" }) { index, entry ->
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

            // Tiles fit as many favorites in a row as their names allow; once there are enough
            // favorites that tiles would at least halve their rows, history suggests them, once.
            val worthTiles = !tiled && !tilesSuggested && !reordering && favorites.size >= SUGGEST_TILES_AT &&
                arrangeTiles(favorites.map { it.text }, { tileWidths[it] ?: 0 }, available, gap, null).rows * 2 <=
                favorites.size
            LaunchedEffect(worthTiles) {
                if (!worthTiles) return@LaunchedEffect
                tilesSuggested = true
                prefs.edit().putBoolean(PREF_TILES_SUGGESTED, true).apply()
                suggestTiles = true
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

    naming?.let { entry ->
        NameFavoriteDialog(
            current = entry.label ?: defaultTitle(context, entry.text, entry.format),
            onDismiss = { naming = null },
            onName = { name ->
                store.setFavorite(entry.text, entry.format, true, name)
                naming = null
            },
        )
    }

    if (suggestTiles) {
        AlertDialog(
            onDismissRequest = { suggestTiles = false },
            icon = { Icon(painterResource(R.drawable.ic_view_quilt), contentDescription = null) },
            title = { Text(stringResource(R.string.tiles_suggest_title)) },
            text = { Text(stringResource(R.string.tiles_suggest_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.confirm()
                        layout = FavoriteLayout.COMPACT
                        AppSettings.setFavoriteLayout(context, FavoriteLayout.COMPACT)
                        suggestTiles = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.tiles_suggest_use)) }
            },
            dismissButton = {
                TextButton(onClick = { haptics.tap(); suggestTiles = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.tiles_suggest_keep))
                }
            },
        )
    }

    renaming?.let { entry ->
        RenameDialog(
            current = entry.label ?: defaultTitle(context, entry.text, entry.format),
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
internal class EntryActions(
    val onOpen: (HistoryEntry) -> Unit,
    val onFavorite: (HistoryEntry, Boolean) -> Unit,
    val onShortcut: (HistoryEntry, Boolean) -> Unit,
    val shortcutsFull: Boolean,
    val onRename: (HistoryEntry) -> Unit,
    val onDelete: (HistoryEntry) -> Unit,
    val topFavorite: String?,
    val onMoveToTop: (HistoryEntry) -> Unit,
)

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
    var menu by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val magnet = rememberRowMagnet(list, entry.text, group, index, with(density) { DETACH_AT.toPx() })
    var width by remember { mutableIntStateOf(0) }
    val swipe = rememberEntrySwipe(
        entry = entry,
        group = group,
        index = index,
        list = list,
        flyTo = { width.toFloat() },
        onDelete = actions.onDelete,
        onFavorite = actions.onFavorite,
    )
    // What a swipe shows behind the row takes the row's own shape, rounding with it as it is pulled
    // and comes loose, so it matches the corners of the neighbours that round toward it.
    val shape = segmentShape(index, count, magnet.topStart.value.value, magnet.bottomStart.value.value)
    Box(
        modifier
            .onSizeChanged { width = it.width }
            .trackTouch { touch = it }
            .entrySwipe(swipe),
    ) {
        SwipeBackground(swipe.direction, swipe.armed, swipe.becomesFavorite, shape, Modifier.matchParentSize())
        Box(Modifier.graphicsLayer { translationX = swipe.shift.value.value + magnet.lean.value.value }) {
            EntryItem(
                entry,
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

/** [trailingOverride] replaces what the row shows at its end, as the handle does while reordering. */
@Composable
private fun EntryItem(
    entry: HistoryEntry,
    shape: Shape,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    trailingOverride: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val context = LocalContext.current
    val payload = remember(entry.text, entry.format) { Classifier.classify(entry.text, entry.format) }
    val kind = remember(payload) { Cards.kindOf(payload) }
    val shown = remember(payload) { Cards.shownText(context, payload) }
    val time = remember(entry.time) { DateFormat.getTimeFormat(context).format(Date(entry.time)) }
    // Favorites take the tertiary colour, so they stay apart from the history at a glance. A lifted
    // row turns tertiary container itself, which the favorite's avatar then blends into, leaving
    // its icon in on tertiary container, the colour a reorderable list gives a dragged row's icon.
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (entry.favorite) colors.tertiaryContainer else colors.primaryContainer,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "entry-icon-container",
    )
    val onContainer by animateColorAsState(
        if (entry.favorite) colors.onTertiaryContainer else colors.onPrimaryContainer,
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
