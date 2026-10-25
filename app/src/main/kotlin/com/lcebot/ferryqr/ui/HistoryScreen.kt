@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.lcebot.ferryqr.ui

import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lcebot.ferryqr.R
import com.lcebot.ferryqr.content.Classifier
import com.lcebot.ferryqr.dispatch.ActionResult
import com.lcebot.ferryqr.dispatch.Cards
import com.lcebot.ferryqr.dispatch.ResultCard
import com.lcebot.ferryqr.history.HistoryEntry
import com.lcebot.ferryqr.history.HistoryStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.launch

private val OUTER_CORNER = 24.dp
private val INNER_CORNER = 6.dp

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current ?: return
    val haptics = rememberHaptics()
    val store = remember { HistoryStore.get(context) }
    val entries by store.entries.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf<ResultCard?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val subtitle: (@Composable () -> Unit)? = if (entries.isEmpty()) {
        null
    } else {
        { Text(pluralStringResource(R.plurals.history_count, entries.size, entries.size)) }
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
                    if (entries.isNotEmpty()) {
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
            val groups = remember(entries) {
                entries.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
            }
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groups.forEach { (day, items) ->
                    item(key = "day-$day") { DayHeader(day, items.first().time) }
                    itemsIndexed(items, key = { _, e -> "${e.time}-${e.text.hashCode()}" }) { index, entry ->
                        HistoryRow(
                            entry = entry,
                            shape = segmentShape(index, items.size),
                            onClick = {
                                haptics.tap()
                                showing = Cards.build(activity, Classifier.classify(entry.text), entry.format)
                            },
                        )
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

    showing?.let { card ->
        ResultSheet(
            card = card,
            onDismiss = { showing = null },
            onAction = { action -> onResult(action.run(activity)) },
        )
    }
}

@Composable
private fun DayHeader(day: LocalDate, sample: Long) {
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
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun HistoryRow(entry: HistoryEntry, shape: Shape, onClick: () -> Unit) {
    val context = LocalContext.current
    val kind = remember(entry.text) { Cards.kindOf(Classifier.classify(entry.text)) }
    val time = remember(entry.time) { DateFormat.getTimeFormat(context).format(Date(entry.time)) }
    ListItem(
        onClick = onClick,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialShapes.Cookie4Sided.toShape())
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(kind.icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        trailingContent = { Text(time, style = MaterialTheme.typography.labelMedium) },
        supportingContent = { Text(stringResource(kind.label)) },
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(entry.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** Expressive grouped list: large corners outside a day's group, small ones between its rows. */
private fun segmentShape(index: Int, count: Int): Shape {
    val top = if (index == 0) OUTER_CORNER else INNER_CORNER
    val bottom = if (index == count - 1) OUTER_CORNER else INNER_CORNER
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}
