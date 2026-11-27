@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import io.github.lcebot.ferryqr.dispatch.CardAction
import io.github.lcebot.ferryqr.dispatch.CardSource
import io.github.lcebot.ferryqr.dispatch.Kind
import io.github.lcebot.ferryqr.dispatch.Origin
import io.github.lcebot.ferryqr.dispatch.ReadContent
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.isLocatedMiniCode

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

    LaunchedEffect(result) {
        when (val read = result) {
            null -> {}
            ImageScan.Unreadable -> haptics.reject()
            is ImageScan.Read ->
                if (read.detections.isEmpty() && read.miniCrop == null) haptics.reject() else haptics.confirm()
        }
    }

    var selected by rememberSaveable { mutableIntStateOf(0) }
    val scanned = result as? ImageScan.Read
    val index = scanned?.detections?.takeIf { it.isNotEmpty() }?.let { selected.coerceIn(it.indices) }
    val content = if (scanned != null && index != null) scanned.contents[index] else null
    LaunchedEffect(content) { content?.let(vm::shown) }
    // Built here rather than where the card shows, so the sheet keeps its one reading state until
    // the first card is ready. A mini program code that could not be read has no content to route;
    // WeChat gets its picture.
    val source = remember(scanned, index) {
        when {
            scanned == null -> null
            index != null -> scanned.locatedCrops[index]?.let { CardSource.LocatedMini(it, live = false) }
                ?: CardSource.Read(scanned.contents[index], Origin.IMAGE)
            else -> scanned.miniCrop?.let { CardSource.LocatedMini(it, live = false) }
        }
    }
    val card = rememberResultCard(source)
    val shown = result.takeUnless { source != null && card == null }

    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val resize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    CardSheet(onClosed = { onClose() }) { onAction, onMessage, close ->
        AnimatedContent(
            targetState = shown,
            contentKey = { read ->
                when {
                    read !is ImageScan.Read -> read
                    read.detections.isNotEmpty() -> "codes"
                    read.miniCrop != null -> "mini"
                    else -> "none"
                }
            },
            transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)) using SizeTransform(clip = false) { _, _ -> resize } },
            label = "image-sheet",
        ) { read ->
            when {
                read == null -> Reading()
                // The sheet reads on until the first card exists, so these always have one to show.
                read is ImageScan.Read && read.detections.isNotEmpty() -> card?.let {
                    Found(
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
private fun Found(
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
        if (read.detections.size > 1) {
            CodeChooser(read.thumbnails, read.detections, read.contents, selected) { picked ->
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
        transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)) using SizeTransform(clip = false) { _, _ -> resize } },
        label = "image-card",
    ) { shown ->
        ResultCardContent(card = shown, onAction = onAction, onMessage = onMessage, modifier = cardModifier)
    }
}

/** Shown above the card only when the image holds several codes. */
@Composable
private fun CodeChooser(
    thumbnails: List<Bitmap?>,
    detections: List<Detection>,
    contents: List<ReadContent>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            pluralStringResource(R.plurals.image_code_count, detections.size, detections.size),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(detections, key = { _, d -> d.text }) { index, detection ->
                CodeTile(thumbnails.getOrNull(index), detection, contents[index], index == selected) { onSelect(index) }
            }
        }
    }
}

@Composable
private fun CodeTile(picture: Bitmap?, detection: Detection, content: ReadContent, selected: Boolean, onClick: () -> Unit) {
    val thumbnail = remember(picture) { picture?.asImageBitmap() }
    val kind = if (detection.isLocatedMiniCode) Kind(R.string.kind_mini_program, R.drawable.ic_qr_code_scanner) else content.kind
    val description = if (detection.isLocatedMiniCode) stringResource(R.string.mini_headline) else detection.text
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        effects,
        label = "tile-container",
    )
    Surface(
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.largeIncreased,
        color = container,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
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
