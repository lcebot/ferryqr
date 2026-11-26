@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.ImageScanViewModel
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.Classifier
import io.github.lcebot.ferryqr.dispatch.Cards
import io.github.lcebot.ferryqr.dispatch.Kind
import io.github.lcebot.ferryqr.dispatch.Origin
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.scan.Detection
import io.github.lcebot.ferryqr.scan.isLocatedMiniCode
import io.github.lcebot.ferryqr.scan.locatedCrop
import io.github.lcebot.ferryqr.scan.StillResult
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TILE_WIDTH = 96.dp
private val THUMBNAIL_SIZE = 80.dp

/** Margin around a code's thumbnail, as a share of the code's longer side. */
private const val THUMBNAIL_MARGIN = 0.15f

/**
 * The result of a shared or opened image as a single card. The sheet starts with a reading state,
 * then shows either an empty state or the card, with a code chooser on top when the image holds more
 * than one code.
 */
@Composable
fun ImageScanSheet(vm: ImageScanViewModel, onClose: () -> Unit) {
    val haptics = rememberHaptics()
    val result by vm.result.collectAsStateWithLifecycle()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onClose() }
    }

    LaunchedEffect(result) {
        when (val read = result) {
            null -> {}
            StillResult.Unreadable -> haptics.reject()
            is StillResult.Read ->
                if (read.detections.isEmpty() && read.miniCodes.isEmpty()) haptics.reject() else haptics.confirm()
        }
    }

    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    ModalSheet(onDismissRequest = onClose, sheetState = sheetState) {
        AnimatedContent(
            targetState = result,
            contentKey = { read ->
                when {
                    read !is StillResult.Read -> read
                    read.detections.isNotEmpty() -> "codes"
                    read.miniCodes.isNotEmpty() -> "mini"
                    else -> "none"
                }
            },
            transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)).using(SizeTransform(clip = false)) },
            label = "image-sheet",
        ) { read ->
            when {
                read == null -> Reading()
                read is StillResult.Read && read.detections.isNotEmpty() -> Found(read, onShown = vm::shown, onHandoff = onClose)
                read is StillResult.Read && read.miniCodes.isNotEmpty() -> MiniFound(read, onHandoff = onClose)
                read is StillResult.Read -> SheetMessage(
                    icon = R.drawable.ic_search_off,
                    title = R.string.image_no_code_title,
                    body = R.string.image_no_code_body,
                    onClose = ::dismiss,
                )
                else -> SheetMessage(
                    icon = R.drawable.ic_broken_image,
                    title = R.string.image_unreadable,
                    body = R.string.image_unreadable_body,
                    onClose = ::dismiss,
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
private fun Found(read: StillResult.Read, onShown: (Detection) -> Unit, onHandoff: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val detection = read.detections[selected.coerceIn(read.detections.indices)]
    val ready = ferriesReady()
    // Built off the main thread, as it asks the package manager which apps there are. The card shown
    // stays until the next one is ready, and the sheet keeps reading until the first is.
    val card by produceState<ResultCard?>(null, detection, ready) {
        value = withContext(Dispatchers.Default) {
            // A mini program code that could not be read has no content to route; WeChat gets its picture.
            val crop = if (detection.isLocatedMiniCode) detection.locatedCrop(read.frame) else null
            if (crop != null) Cards.miniProgram(activity, crop, live = false)
            else Cards.build(activity, Classifier.classify(detection.text, detection.format), detection.format, Origin.IMAGE)
        }
    }

    LaunchedEffect(detection) { onShown(detection) }

    // The sheet has no snackbar host, so the few messages it needs are toasts.
    val onResult = rememberActionResultHandler(onHandoff, context::toast)

    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (read.detections.size > 1) {
            CodeChooser(read.frame, read.detections, selected) { index ->
                if (index != selected) haptics.step()
                selected = index
            }
        }
        AnimatedContent(
            targetState = card,
            transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)).using(SizeTransform(clip = false)) },
            label = "image-card",
        ) { shown ->
            if (shown == null) {
                Reading()
            } else {
                ResultCardContent(
                    card = shown,
                    onAction = { action -> onResult(action.run(activity)) },
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

/** A probable mini program code, the only thing found in the image, offered to WeChat. */
@Composable
private fun MiniFound(read: StillResult.Read, onHandoff: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val crop = remember(read) { read.miniCodes.first().crop(read.frame) } ?: return
    val ready = ferriesReady()
    val card by produceState<ResultCard?>(null, crop, ready) {
        value = withContext(Dispatchers.Default) { Cards.miniProgram(activity, crop, live = false) }
    }
    val onResult = rememberActionResultHandler(onHandoff, context::toast)
    val shown = card
    if (shown == null) {
        Reading()
        return
    }
    ResultCardContent(
        card = shown,
        onAction = { action -> onResult(action.run(activity)) },
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    )
}

/** Shown above the card only when the image holds several codes. */
@Composable
private fun CodeChooser(frame: Bitmap, detections: List<Detection>, selected: Int, onSelect: (Int) -> Unit) {
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
                CodeTile(frame, detection, index == selected) { onSelect(index) }
            }
        }
    }
}

@Composable
private fun CodeTile(frame: Bitmap, detection: Detection, selected: Boolean, onClick: () -> Unit) {
    val thumbnail = remember(frame, detection) { thumbnail(frame, detection)?.asImageBitmap() }
    val kind = remember(detection) {
        if (detection.isLocatedMiniCode) Kind(R.string.kind_mini_program, R.drawable.ic_qr_code_scanner)
        else Cards.kindOf(Classifier.classify(detection.text, detection.format))
    }
    val description = if (detection.isLocatedMiniCode) stringResource(R.string.mini_headline) else detection.text
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        effects,
        label = "tile-container",
    )
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
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
                    .clip(RoundedCornerShape(12.dp))
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

/** The code's own region of the image with a little margin, or null when its position is unknown. */
private fun thumbnail(frame: Bitmap, detection: Detection): Bitmap? {
    if (detection.corners.isEmpty()) return null
    val bounds = detection.bounds
    val margin = max(bounds.width(), bounds.height()) * THUMBNAIL_MARGIN
    val region = Rect(
        (bounds.left - margin).roundToInt(),
        (bounds.top - margin).roundToInt(),
        (bounds.right + margin).roundToInt(),
        (bounds.bottom + margin).roundToInt(),
    )
    if (!region.intersect(0, 0, frame.width, frame.height) || region.isEmpty) return null
    return Bitmap.createBitmap(frame, region.left, region.top, region.width(), region.height())
}
