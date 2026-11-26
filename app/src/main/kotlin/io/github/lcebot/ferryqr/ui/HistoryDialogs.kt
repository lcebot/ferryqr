@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.HistoryDialog
import io.github.lcebot.ferryqr.HistoryViewModel
import io.github.lcebot.ferryqr.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The name an exported history file is offered under. */
private const val EXPORT_NAME = "ferryqr-history.json"

/**
 * Offers to export a history file that could not be read in full, once [show] says the history is
 * read, so what it held can be recovered by hand; letting it go removes it. Closed without either,
 * it comes back the next time history opens. [message] tells how an export went.
 */
@Composable
internal fun DamagedHistoryDialog(vm: HistoryViewModel, show: Boolean, message: (Int) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val damaged by vm.damaged.collectAsStateWithLifecycle()
    var closed by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use(vm::exportDamaged) }.getOrNull() == true
            }
            if (done) vm.forgetDamaged()
            message(if (done) R.string.history_damaged_exported else R.string.history_damaged_export_failed)
        }
    }
    if (!damaged || closed || !show) return
    AlertDialog(
        onDismissRequest = { closed = true },
        icon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null) },
        title = { Text(stringResource(R.string.history_damaged_title)) },
        text = { Text(stringResource(R.string.history_damaged_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.tap()
                    closed = true
                    export.launch(EXPORT_NAME)
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.history_damaged_export)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    haptics.tap()
                    closed = true
                    vm.forgetDamaged()
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.history_damaged_discard)) }
        },
    )
}

/**
 * The dialog the page shows over its list, if any; [message] tells how an action went. Naming an
 * entry as it becomes a favorite is the favorite requester's own dialog.
 */
@Composable
internal fun HistoryDialogs(vm: HistoryViewModel, dialog: HistoryDialog?, message: (Int) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
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
                        message(R.string.history_cleared)
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
        is HistoryDialog.Rename -> RenameDialog(
            current = shown.entry.content.names(context, shown.entry.label).shortTitle,
            onDismiss = { vm.show(null) },
            onRename = { name -> vm.rename(shown.entry, name) },
        )
        null -> Unit
    }
}
