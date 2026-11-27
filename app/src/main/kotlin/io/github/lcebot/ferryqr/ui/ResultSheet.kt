// rememberBottomSheetState is an experimental Material 3 API.
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.CardAction
import io.github.lcebot.ferryqr.dispatch.CardSection
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.generate.CodeGenerator
import io.github.lcebot.ferryqr.generate.CodeRules
import io.github.lcebot.ferryqr.generate.CodeType
import io.github.lcebot.ferryqr.scan.MINI_PROGRAM_FORMAT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A result card in a sheet of its own, which shows what the card's actions report. [onHandoff]
 * follows an action another app took over. With [closing] set, the sheet slides away as when
 * dismissed, and then [onDismiss] follows.
 */
@Composable
fun ResultSheet(card: ResultCard, onDismiss: () -> Unit, onHandoff: () -> Unit, closing: Boolean = false) {
    val state = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        state.hide()
        dismiss()
    }
    CardSheet(onDismissRequest = onDismiss, sheetState = state, onHandoff = onHandoff) { onAction, onMessage ->
        ResultCardContent(
            card = card,
            onAction = onAction,
            onMessage = onMessage,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        )
    }
}

/** A code's picture together with the format it is drawn in. */
private data class Drawing(val bitmap: Bitmap, val type: CodeType)

/**
 * The body of a result card, shared by the scanner's sheet and the image sheet. A code drawn again
 * in history can be drawn in another format for the reader at hand, for as long as the card is open;
 * the entry keeps the format it was read in. [onMessage] shows what the code's toolbar reports.
 */
@Composable
fun ResultCardContent(
    card: ResultCard,
    onAction: (CardAction) -> Unit,
    onMessage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val original = remember(card.format) { CodeType.ofReaderName(card.format) }
    // Keyed on the content rather than the card, which is built again whenever an app's readiness
    // changes, so the format picked stays until another code is shown.
    var shown by rememberSaveable(card.raw, card.format) { mutableStateOf<CodeType?>(null) }
    var picking by remember { mutableStateOf(false) }
    // A mini program code means something only as one; its content drawn in another format would not.
    val switchable = card.exportable && card.raw.isNotEmpty() && card.format.isNotEmpty() && card.format != MINI_PROGRAM_FORMAT
    // What the card's own picture is drawn as: the original when the content fits it, as history
    // redraws it, else the QR code it falls back to.
    val drawnAs = remember(card.raw, original) {
        original?.takeIf { CodeRules.problem(card.raw, it) == null } ?: CodeType.QR
    }
    // Drawn off the main thread. The picture and its format change together once the new drawing is
    // ready, so the code shown until then keeps the look of its own format.
    val redrawn by produceState<Drawing?>(null, card.raw, shown) {
        value = shown?.let { type ->
            withContext(Dispatchers.Default) { CodeGenerator.draw(card.raw, type) }?.let { Drawing(it, type) }
        }
    }
    val drawing = redrawn?.takeIf { shown != null } ?: card.image?.let { Drawing(it, drawnAs) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(card.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(card.kind),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            // Only read content can be kept; a picture of an unread code has nothing to store.
            if (card.raw.isNotEmpty() && card.format.isNotEmpty()) {
                FavoriteToggle(card.raw, card.format, Modifier.offset(x = 12.dp))
            }
        }
        drawing?.let { (bitmap, type) ->
            CodeImage(
                bitmap = bitmap,
                format = type,
                long = type.long && bitmap.width > bitmap.height,
                drawTall = if (switchable) {
                    { ratio -> CodeGenerator.draw(card.raw, type, ratio) }
                } else {
                    null
                },
                onChangeFormat = if (switchable) {
                    { picking = true }
                } else {
                    null
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            // In history the original is out of sight, so the drawing can be kept or passed on.
            if (card.exportable) CodeToolbar(bitmap, onMessage = onMessage)
        }
        if (card.headline.isNotEmpty()) {
            SelectionContainer {
                Text(
                    card.headline,
                    style = if (card.headline.length > 60) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.headlineSmall,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        card.body?.let { body ->
            SelectionContainer {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        card.sections.forEach { CardSectionView(it) }
        // A card built again as an app becomes ready can gain or lose notes and change its actions.
        // Both are one item of the column, so the gap between them comes and goes with the notes
        // rather than staying behind while they are gone, and none trails a card without actions.
        if (card.notes.isNotEmpty() || card.actions.isNotEmpty()) {
            Column {
                CardNotes(card.notes)
                val gap by animateDpAsState(
                    if (card.notes.isNotEmpty() && card.actions.isNotEmpty()) 16.dp else 4.dp,
                    MaterialTheme.motionScheme.defaultSpatialSpec(),
                    label = "notes-gap",
                )
                CardActions(card.actions, onAction, Modifier.padding(top = gap))
            }
        }
    }
    if (picking) {
        FormatPickerSheet(
            text = card.raw,
            selected = shown ?: drawnAs,
            original = original,
            onPick = { type ->
                shown = type.takeUnless { it == original }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun CardSectionView(section: CardSection) {
    val colors = MaterialTheme.colorScheme
    when (section) {
        is CardSection.Route -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            section.label?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = colors.primary) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(section.from, style = MaterialTheme.typography.displaySmall)
                Icon(
                    painterResource(R.drawable.ic_flight),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    // The glyph points up; along the route it points from the first airport to the second.
                    modifier = Modifier.size(28.dp).rotate(90f),
                )
                Text(section.to, style = MaterialTheme.typography.displaySmall)
            }
            if (section.caption.isNotEmpty()) {
                Text(section.caption, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
            }
        }
        is CardSection.Fields -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            section.rows.chunked(2).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    line.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                    if (line.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        is CardSection.Raw -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.card_raw), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            SelectionContainer {
                Text(
                    section.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(colors.surfaceContainerHighest)
                        .padding(12.dp),
                )
            }
        }
    }
}
