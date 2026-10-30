@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.lcebot.ferryqr.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.widget.Toast
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.lcebot.ferryqr.ImageScanViewModel
import com.lcebot.ferryqr.R
import com.lcebot.ferryqr.content.Classifier
import com.lcebot.ferryqr.dispatch.ActionResult
import com.lcebot.ferryqr.dispatch.Cards
import com.lcebot.ferryqr.history.HistoryStore
import com.lcebot.ferryqr.scan.Detection
import com.lcebot.ferryqr.scan.StillResult
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
    val helperReady by vm.helperReady.collectAsStateWithLifecycle()
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
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
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
                read is StillResult.Read && read.detections.isNotEmpty() -> Found(read, onHandoff = onClose)
                read is StillResult.Read && read.miniCodes.isNotEmpty() -> MiniFound(read, helperReady, onHandoff = onClose)
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
        modifier = Modifier.fillMaxWidth().height(200.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        LoadingIndicator(Modifier.size(56.dp))
        Text(
            stringResource(R.string.image_reading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SheetMessage(@DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int, onClose: () -> Unit) {
    val haptics = rememberHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
private fun Found(read: StillResult.Read, onHandoff: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val detection = read.detections[selected.coerceIn(read.detections.indices)]
    val card = remember(detection) {
        Cards.build(activity, Classifier.classify(detection.text, detection.format), detection.format)
    }

    LaunchedEffect(detection) { HistoryStore.get(context).add(detection.text, detection.format) }

    // The sheet has no snackbar host, so the few messages it needs are toasts.
    fun onResult(result: ActionResult) {
        when (result) {
            ActionResult.Handoff -> {
                haptics.tap()
                onHandoff()
            }
            ActionResult.Copied -> {
                haptics.confirm()
                // Android 13 and later confirm clipboard writes with their own overlay.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            }
            ActionResult.Unavailable -> {
                haptics.reject()
                Toast.makeText(context, R.string.no_app, Toast.LENGTH_SHORT).show()
            }
            ActionResult.Done -> haptics.tap()
        }
    }

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
            ResultCardContent(
                card = shown,
                onAction = { action -> onResult(action.run(activity)) },
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/** A probable mini program code, the only thing found in the image, offered to WeChat. */
@Composable
private fun MiniFound(read: StillResult.Read, helperReady: Boolean, onHandoff: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val crop = remember(read) { read.miniCodes.first().crop(read.frame) } ?: return
    val card = remember(crop, helperReady) { Cards.miniProgram(activity, crop, helperReady, live = false) }
    ResultCardContent(
        card = card,
        onAction = { action ->
            when (action.run(activity)) {
                ActionResult.Handoff -> {
                    haptics.tap()
                    onHandoff()
                }
                ActionResult.Unavailable -> {
                    haptics.reject()
                    Toast.makeText(context, R.string.no_app, Toast.LENGTH_SHORT).show()
                }
                else -> haptics.tap()
            }
        },
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
    val kind = remember(detection) { Cards.kindOf(Classifier.classify(detection.text, detection.format)) }
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
                        contentDescription = detection.text,
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier.size(THUMBNAIL_SIZE),
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.ic_qr_code_2),
                        contentDescription = detection.text,
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
