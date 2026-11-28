@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.ImageScan
import io.github.lcebot.ferryqr.ImageScanViewModel
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.StillCode
import io.github.lcebot.ferryqr.dispatch.CardAction
import io.github.lcebot.ferryqr.dispatch.Cards
import io.github.lcebot.ferryqr.dispatch.ResultCard

private val TILE_WIDTH = 96.dp
private val THUMBNAIL_SIZE = 80.dp

/**
 * The result of a shared or opened image as a single card. The sheet starts with a reading state,
 * then shows either an empty state or the card, with a code chooser on top when the image holds more
 * than one code.
 */
@Composable
fun ImageScanSheet(vm: ImageScanViewModel, onClose: () -> Unit) {
    val haptics = rememberHaptics()
    val result by vm.result.collectAsStateWithLifecycle()
    // The card offers apps by what is installed and which apps the module serves, either of which may
    // change while the sheet is out of sight, so both are asked again each time it appears.
    RefreshAppFactsOnResume()

    // Felt as the image is read, which the view model tells once, rather than whenever the sheet
    // is composed with its result, as it is again when the activity is made again.
    LaunchedEffect(vm) {
        vm.outcomes.collect { read ->
            val found = read is ImageScan.Read && (read.codes.isNotEmpty() || read.miniCrop != null)
            if (found) haptics.confirm() else haptics.reject()
        }
    }

    var selected by rememberSaveable { mutableIntStateOf(0) }
    val scanned = result as? ImageScan.Read
    val index = scanned?.codes?.takeIf { it.isNotEmpty() }?.let { selected.coerceIn(it.indices) }
    LaunchedEffect(scanned, index) { scanned?.let { vm.show(it, index) } }
    // Read here rather than where the card shows, so the sheet keeps its one reading state until
    // the first card is ready.
    val card by vm.card.collectAsStateWithLifecycle()
    val hasCard = scanned != null && (index != null || scanned.miniCrop != null)
    val shown = result.takeUnless { hasCard && card == null }

    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val resize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    CardSheet(onClosed = { onClose() }) { onAction, onMessage, close ->
        AnimatedContent(
            targetState = shown,
            contentKey = { read ->
                when {
                    read !is ImageScan.Read -> read
                    read.codes.isNotEmpty() -> "codes"
                    read.miniCrop != null -> "mini"
                    else -> "none"
                }
            },
            transitionSpec = { crossfadeResizing(fade, resize) },
            label = "image-sheet",
        ) { read ->
            when {
                read == null -> Reading()
                // The sheet reads on until the first card exists, so these always have one to show.
                read is ImageScan.Read && read.codes.isNotEmpty() -> card?.let {
                    CodesFound(
                        read,
                        card = it,
                        selected = selected,
                        onSelect = { picked -> selected = picked },
                        onAction = onAction,
                        onMessage = onMessage,
                    )
                }
                read is ImageScan.Read && read.miniCrop != null -> card?.let {
                    MiniFound(it, onAction = onAction, onMessage = onMessage)
                }
                read is ImageScan.Read -> SheetMessage(
                    icon = R.drawable.ic_search_off,
                    title = R.string.image_no_code_title,
                    body = R.string.image_no_code_body,
                    onClose = close,
                )
                else -> SheetMessage(
                    icon = R.drawable.ic_broken_image,
                    title = R.string.image_unreadable,
                    body = R.string.image_unreadable_body,
                    onClose = close,
                )
            }
        }
    }
}

@Composable
private fun Reading() {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        LoadingIndicator(Modifier.size(56.dp))
        Text(
            stringResource(R.string.image_reading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SheetMessage(@DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int, onClose: () -> Unit) {
    val haptics = rememberHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { haptics.tap(); onClose() },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.close))
        }
    }
}

@Composable
private fun CodesFound(
    read: ImageScan.Read,
    card: ResultCard,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAction: (CardAction) -> Unit,
    onMessage: (Int) -> Unit,
) {
    val haptics = rememberHaptics()
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (read.codes.size > 1) {
            CodeChooser(read.codes, selected) { picked ->
                if (picked != selected) haptics.step()
                onSelect(picked)
            }
        }
        CardCrossfade(
            card = card,
            onAction = onAction,
            onMessage = onMessage,
            cardModifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** A probable mini program code, the only thing found in the image, offered to WeChat. */
@Composable
private fun MiniFound(card: ResultCard, onAction: (CardAction) -> Unit, onMessage: (Int) -> Unit) {
    CardCrossfade(
        card = card,
        onAction = onAction,
        onMessage = onMessage,
        cardModifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    )
}

/**
 * [card] with [cardModifier], crossfading into the next one while the sheet resizes to it. Keyed on
 * the code, so a card built again as an app becomes ready updates in place and keeps what was done
 * on it, while another code crossfades in. The card shown stays until the next one is ready.
 */
@Composable
private fun CardCrossfade(
    card: ResultCard,
    onAction: (CardAction) -> Unit,
    onMessage: (Int) -> Unit,
    cardModifier: Modifier,
) {
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val resize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    AnimatedContent(
        targetState = card,
        contentKey = { it.raw to it.format },
        transitionSpec = { crossfadeResizing(fade, resize) },
        label = "image-card",
    ) { shown ->
        ResultCardContent(card = shown, onAction = onAction, onMessage = onMessage, modifier = cardModifier)
    }
}

/** Shown above the card only when the image holds several codes. */
@Composable
private fun CodeChooser(codes: List<StillCode>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        // The header's own inset counts toward the 24dp the sheet's content keeps from its edges.
        SectionHeader(
            pluralStringResource(R.plurals.image_code_count, codes.size, codes.size),
            Modifier.padding(horizontal = 24.dp - LIST_TEXT_INSET),
            top = 0.dp,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(codes, key = { _, code -> code.found.key }) { index, code ->
                CodeTile(code, index == selected) { onSelect(index) }
            }
        }
    }
}

@Composable
private fun CodeTile(code: StillCode, selected: Boolean, onClick: () -> Unit) {
    val thumbnail = remember(code.thumbnail) { code.thumbnail?.asImageBitmap() }
    // Only a mini program code located but not read comes without content.
    val content = code.content
    val kind = content?.kind ?: Cards.MINI_PROGRAM_KIND
    val description = codeDescription(content)
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        effects,
        label = "tile-container",
    )
    // The border is always drawn and fades with the fill on the same spec, so the two parts of the
    // tile change together. It fades through the primary colour made clear, so no other hue shows
    // on the way.
    val primary = MaterialTheme.colorScheme.primary
    val borderColor by animateColorAsState(
        if (selected) primary else primary.copy(alpha = 0f),
        effects,
        label = "tile-border",
    )
    Surface(
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.largeIncreased,
        color = container,
        border = BorderStroke(2.dp, borderColor),
        modifier = Modifier.width(TILE_WIDTH),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(THUMBNAIL_SIZE)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = description,
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier.size(THUMBNAIL_SIZE),
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.ic_qr_code_2),
                        contentDescription = description,
                        tint = Color.Black,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(painterResource(kind.icon), contentDescription = null, modifier = Modifier.size(14.dp))
                Text(
                    stringResource(kind.label),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
