@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.lcebot.ferryqr.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lcebot.ferryqr.R
import com.lcebot.ferryqr.dispatch.CardAction
import com.lcebot.ferryqr.dispatch.ResultCard

@Composable
fun ResultSheet(card: ResultCard, onDismiss: () -> Unit, onAction: (CardAction) -> Unit) {
    val state = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        ResultCardContent(
            card = card,
            onAction = onAction,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        )
    }
}

/** The body of a result card, shared by the scanner's sheet and the image sheet. */
@Composable
fun ResultCardContent(card: ResultCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(card.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(card.kind),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        card.image?.let { bitmap ->
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier.size(160.dp),
                )
            }
        }
        SelectionContainer {
            Text(
                card.headline,
                style = if (card.headline.length > 60) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.headlineSmall,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
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
        card.notes.forEach { note ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        val primary = card.actions.firstOrNull()
        if (primary != null) {
            Button(
                onClick = { onAction(primary) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                ActionIcon(primary)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(primary.label))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            card.actions.drop(1).forEach { action ->
                FilledTonalButton(onClick = { onAction(action) }, shapes = ButtonDefaults.shapes()) {
                    ActionIcon(action)
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(action.label))
                }
            }
        }
    }
}

@Composable
private fun ActionIcon(action: CardAction) {
    val app = action.app
    if (app != null) AppIcon(app, action.icon, Modifier.size(ButtonDefaults.IconSize))
    else Icon(painterResource(action.icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
}
