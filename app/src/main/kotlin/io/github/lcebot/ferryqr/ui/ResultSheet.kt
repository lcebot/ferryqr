@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.CardAction
import io.github.lcebot.ferryqr.dispatch.ResultCard

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
        card.image?.let { bitmap -> CodeImage(bitmap, Modifier.align(Alignment.CenterHorizontally)) }
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

/** The code's height while small; a wide linear code grows sideways from it. */
private val CODE_HEIGHT = 160.dp

/**
 * The code on white, since readers expect dark modules on light. A tap grows it to the sheet's full
 * width so another phone can scan it from a distance, and a second tap returns it.
 */
@Composable
private fun CodeImage(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val aspect = bitmap.width.toFloat() / bitmap.height
    var enlarged by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val small = minOf(CODE_HEIGHT * maxOf(aspect, 1f), maxWidth)
        val width by animateDpAsState(
            targetValue = if (enlarged) maxWidth else small,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "code-size",
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width, width / aspect)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .clickable(
                        onClickLabel = stringResource(if (enlarged) R.string.code_shrink else R.string.code_enlarge),
                    ) {
                        haptics.tap()
                        enlarged = !enlarged
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = enlarged,
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                label = "code-hint",
            ) { big ->
                Text(
                    stringResource(if (big) R.string.code_tap_shrink else R.string.code_tap_enlarge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
