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
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.roundToInt
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.launch

private val OUTER_CORNER = 24.dp
private val INNER_CORNER = 6.dp

/** With more favorites than this, the section can fold down to its first few. */
private const val FOLDED_FAVORITES = 4

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
    val ready = targetsReady()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val favorites = remember(entries) { entries.filter { it.favorite }.sortedByDescending { it.favoritedAt } }
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
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
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
            val foldable = favorites.size > FOLDED_FAVORITES
            val shownFavorites = if (foldable && folded) favorites.take(FOLDED_FAVORITES) else favorites
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
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(context.getString(R.string.shortcut_added))
                        }
                    }
                },
                shortcutsFull = shortcutsFull,
                onRename = { haptics.tap(); renaming = it },
                onDelete = ::delete,
            )
            LazyColumn(
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
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
                            modifier = animateRow(),
                        )
                    }
                    itemsIndexed(shownFavorites, key = { _, e -> "entry:${e.text}" }) { index, entry ->
                        EntryRow(entry, segmentShape(index, shownFavorites.size), actions, animateRow())
                    }
                }
                groups.forEach { (day, items) ->
                    item(key = "header:$day") { DayHeader(day, items.first().time, animateRow()) }
                    itemsIndexed(items, key = { _, e -> "entry:${e.text}" }) { index, entry ->
                        EntryRow(entry, segmentShape(index, items.size), actions, animateRow())
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
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.history_cleared)) }
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
            ActionResult.Handoff, ActionResult.Done -> {
                haptics.tap()
                showing = null
            }
            ActionResult.Copied -> {
                haptics.confirm()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.copied)) }
                }
            }
            ActionResult.Unavailable -> {
                haptics.reject()
                scope.launch { snackbar.showSnackbar(context.getString(R.string.no_app)) }
            }
        }
    }

    showing?.let { entry ->
        // The original code is no longer in sight, so every entry is shown with its code drawn
        // again, to be scanned by another phone or kept as an image. The card is built again once
        // the framework reports which apps take content.
        val card = remember(entry, ready) {
            val built = Cards.build(activity, Classifier.classify(entry.text, entry.format), entry.format, Origin.HISTORY)
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

private class EntryActions(
    val onOpen: (HistoryEntry) -> Unit,
    val onFavorite: (HistoryEntry, Boolean) -> Unit,
    val onShortcut: (HistoryEntry, Boolean) -> Unit,
    val shortcutsFull: Boolean,
    val onRename: (HistoryEntry) -> Unit,
    val onDelete: (HistoryEntry) -> Unit,
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
private fun FavoritesHeader(foldable: Boolean, folded: Boolean, hidden: Int, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
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
        modifier = modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * How far a row must travel, as a share of its width, before letting go deletes it or changes its
 * favorite: half, the default swipe threshold of Android's ItemTouchHelper and of the first
 * Material swipe to dismiss. A swipe is decided by distance rather than speed, so a short quick flick
 * springs back, as a full swipe does on iOS, and the tick felt at this point always means that
 * letting go will act.
 */
private const val SWIPE_COMMIT = 0.5f

/** A release faster than this toward the row's rest place takes a committed swipe back; Compose's fling threshold. */
private val SWIPE_RETURN_VELOCITY = 125.dp

/**
 * One entry: a tap opens it, a long press opens its menu, a swipe toward the start deletes it, and a
 * swipe toward the end makes it a favorite or stops it being one. A favorite swipe slides the row
 * back once done. The swipe state lives only as long as the row, so an entry brought back by undo
 * arrives settled.
 */
@Composable
private fun EntryRow(entry: HistoryEntry, shape: Shape, actions: EntryActions, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    var menu by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val swipe = remember { AnchoredDraggableState(SwipeToDismissBoxValue.Settled) }
    var width by remember { mutableIntStateOf(0) }
    val offset = swipe.offset.takeUnless { it.isNaN() } ?: 0f
    val direction = when {
        offset == 0f -> SwipeToDismissBoxValue.Settled
        (offset > 0f) != rtl -> SwipeToDismissBoxValue.StartToEnd
        else -> SwipeToDismissBoxValue.EndToStart
    }
    val armed = width > 0 && abs(offset) >= width * SWIPE_COMMIT
    LaunchedEffect(armed) { if (armed) haptics.step() }

    val decay = rememberSplineBasedDecay<Float>()
    val settle = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val returnVelocity = with(density) { SWIPE_RETURN_VELOCITY.toPx() }
    val fling = remember(swipe, decay, settle, returnVelocity, rtl) {
        snapFlingBehavior(
            snapLayoutInfoProvider = object : SnapLayoutInfoProvider {
                // No glide first: where the row ends up depends on where it was let go, not on speed.
                override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f

                override fun calculateSnapOffset(velocity: Float): Float {
                    val at = swipe.offset.takeUnless { it.isNaN() } ?: return 0f
                    val committed = width > 0 && abs(at) >= width * SWIPE_COMMIT
                    val flungBack = at != 0f && velocity * at < 0f && abs(velocity) >= returnVelocity
                    val target = when {
                        !committed || flungBack -> SwipeToDismissBoxValue.Settled
                        (at > 0f) != rtl -> SwipeToDismissBoxValue.StartToEnd
                        else -> SwipeToDismissBoxValue.EndToStart
                    }
                    return swipe.anchors.positionOf(target) - at
                }
            },
            decayAnimationSpec = decay,
            snapAnimationSpec = settle,
        )
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
    LaunchedEffect(swipe.settledValue) {
        when (swipe.settledValue) {
            SwipeToDismissBoxValue.EndToStart -> delete(current)
            SwipeToDismissBoxValue.StartToEnd -> {
                favorite(current, !current.favorite)
                swipe.animateTo(SwipeToDismissBoxValue.Settled, settle)
            }
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }

    Box(
        modifier.onSizeChanged { size ->
            width = size.width
            val end = size.width.toFloat()
            swipe.updateAnchors(
                DraggableAnchors {
                    SwipeToDismissBoxValue.Settled at 0f
                    SwipeToDismissBoxValue.StartToEnd at if (rtl) -end else end
                    SwipeToDismissBoxValue.EndToStart at if (rtl) end else -end
                },
            )
        },
    ) {
        SwipeBackground(direction, armed, becomesFavorite, shape, Modifier.matchParentSize())
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .anchoredDraggable(swipe, Orientation.Horizontal, flingBehavior = fling),
        ) {
            EntryItem(entry, shape, onClick = { actions.onOpen(entry) }, onLongClick = { menu = true })
        }
        EntryMenu(
            entry = entry,
            expanded = menu,
            onDismiss = { menu = false },
            actions = actions,
        )
    }
}

@Composable
private fun EntryItem(entry: HistoryEntry, shape: Shape, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val payload = remember(entry.text, entry.format) { Classifier.classify(entry.text, entry.format) }
    val kind = remember(payload) { Cards.kindOf(payload) }
    val shown = remember(payload) { Cards.shownText(context, payload) }
    val time = remember(entry.time) { DateFormat.getTimeFormat(context).format(Date(entry.time)) }
    // Favorites take the tertiary colour, so they stay apart from the history at a glance.
    val container by animateColorAsState(
        if (entry.favorite) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "entry-icon-container",
    )
    val onContainer by animateColorAsState(
        if (entry.favorite) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "entry-icon",
    )
    // A favorite is kept for what it is rather than when it was scanned.
    val trailing: (@Composable () -> Unit)? = if (entry.favorite) null else {
        { Text(time, style = MaterialTheme.typography.labelMedium) }
    }
    ListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(R.string.entry_more),
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
        Text(entry.label ?: shown, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
 * Expressive grouped list: large corners outside a group, small ones between its rows. The corners
 * follow a row that becomes the first or last of its group, as when a neighbour is deleted.
 */
@Composable
fun segmentShape(index: Int, count: Int): Shape {
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Dp>()
    val top by animateDpAsState(if (index == 0) OUTER_CORNER else INNER_CORNER, spec, label = "row-top")
    val bottom by animateDpAsState(if (index == count - 1) OUTER_CORNER else INNER_CORNER, spec, label = "row-bottom")
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}
