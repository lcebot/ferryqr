@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.CardAction
import io.github.lcebot.ferryqr.dispatch.ResultCard
import io.github.lcebot.ferryqr.generate.CodeExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        card.image?.let { bitmap ->
            CodeImage(
                bitmap,
                tilt = card.format == PDF417_FORMAT,
                exportable = card.exportable,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
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
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        CardActions(card.actions, onAction, Modifier.padding(top = 8.dp))
    }
}

/**
 * The card's actions in reading order, so the main one is met first. The primary action leads as a
 * filled button. Other apps that can open the content follow as tonal buttons that keep their
 * labels, since one app can be offered in more than one way, such as opening the content or only
 * its scanner. What can be done with the content itself comes last as tonal icon buttons, each
 * named in a tooltip and to screen readers. The row wraps when the screen is narrow or the text large.
 */
@Composable
private fun CardActions(actions: List<CardAction>, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val primary = actions.firstOrNull() ?: return
    val (apps, tools) = actions.drop(1).partition { it.app != null }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { onAction(primary) }, shapes = ButtonDefaults.shapes()) {
            ActionIcon(primary, ButtonDefaults.IconSize)
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(labelOf(primary))
        }
        apps.forEach { action ->
            FilledTonalButton(onClick = { onAction(action) }, shapes = ButtonDefaults.shapes()) {
                ActionIcon(action, ButtonDefaults.IconSize)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(labelOf(action))
            }
        }
        tools.forEach { action -> ToolButton(action, onAction) }
    }
}

@Composable
private fun ToolButton(action: CardAction, onAction: (CardAction) -> Unit) {
    val label = labelOf(action)
    Labeled(label) {
        FilledTonalIconButton(
            onClick = { onAction(action) },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            ActionIcon(action, ACTION_ICON_SIZE)
        }
    }
}

private val ACTION_ICON_SIZE = 24.dp

@Composable
private fun labelOf(action: CardAction): String = when (val app = action.appName) {
    null -> stringResource(action.label)
    else -> stringResource(action.label, stringResource(app))
}

@Composable
private fun ActionIcon(action: CardAction, size: Dp) {
    val app = action.app
    if (app != null) AppIcon(app, action.icon, Modifier.size(size))
    else Icon(painterResource(action.icon), contentDescription = null, modifier = Modifier.size(size))
}

/** The code's height while small; a wide linear code grows sideways from it. */
private val CODE_HEIGHT = 160.dp

/** zxing-cpp's name for PDF417, the one format that is enlarged by turning it. */
private const val PDF417_FORMAT = "PDF_417"

/** The white margin [TiltedCode] keeps around the code, so the small card shows it at full width. */
private val TILTED_FRAME = 16.dp

/** What can be done with an exportable code, offered by holding it. */
private class CodeAction(@StringRes val label: Int, @DrawableRes val icon: Int, val run: () -> Unit)

/**
 * The code on white, since readers expect dark modules on light. A tap grows it to the sheet's full
 * width so another phone can scan it from a distance, and a second tap returns it. A PDF417 with
 * [tilt] grows into a square instead and turns as it grows, the same way the create page shows it.
 * An [exportable] code also opens a menu when held, to share, copy or save it as an image.
 */
@Composable
private fun CodeImage(bitmap: Bitmap, tilt: Boolean, exportable: Boolean, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val aspect = bitmap.width.toFloat() / bitmap.height
    var enlarged by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // The sheet covers any snackbar, so results are told by a toast.
    fun export(action: (Context, Bitmap) -> Boolean, @StringRes done: Int?, @StringRes failed: Int) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { action(context, bitmap) }
            if (ok) haptics.confirm() else haptics.reject()
            (if (ok) done else failed)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    fun share() {
        scope.launch {
            val chooser = withContext(Dispatchers.IO) { CodeExport.share(context, bitmap) }
            if (chooser != null) {
                context.startActivity(chooser)
            } else {
                haptics.reject()
                Toast.makeText(context, R.string.code_share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val actions = listOf(
        CodeAction(R.string.code_share, R.drawable.ic_share, ::share),
        // Android 13 and later preview clipboard contents themselves.
        CodeAction(R.string.code_copy, R.drawable.ic_content_copy) {
            export(
                CodeExport::copy,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) null else R.string.code_copied,
                R.string.code_copy_failed,
            )
        },
        CodeAction(R.string.code_save, R.drawable.ic_download) {
            export(CodeExport::save, R.string.code_saved, R.string.code_save_failed)
        },
    )

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val small = minOf(CODE_HEIGHT * maxOf(aspect, 1f), maxWidth)
        val tilted = tilt && aspect > 1f
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
        val width by animateDpAsState(if (enlarged) maxWidth else small, spatial, label = "code-width")
        val height by animateDpAsState(
            targetValue = when {
                !tilted -> (if (enlarged) maxWidth else small) / aspect
                enlarged -> maxWidth
                else -> small / aspect + TILTED_FRAME
            },
            animationSpec = spatial,
            label = "code-height",
        )
        val clickLabel = stringResource(if (enlarged) R.string.code_shrink else R.string.code_enlarge)
        val moreLabel = stringResource(R.string.code_more)
        fun toggle() {
            haptics.tap()
            enlarged = !enlarged
        }
        val openMenu: (() -> Unit)? = if (exportable) ({ menu = true }) else null
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                if (tilted) {
                    // The card turns inside its area, so a ripple on that area would not match its outline.
                    TiltedCode(
                        bitmap,
                        Modifier
                            .size(width, height)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = clickLabel,
                                onLongClickLabel = moreLabel.takeIf { exportable },
                                onLongClick = openMenu,
                                onClick = ::toggle,
                            ),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(width, height)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White)
                            .combinedClickable(
                                onClickLabel = clickLabel,
                                onLongClickLabel = moreLabel.takeIf { exportable },
                                onLongClick = openMenu,
                                onClick = ::toggle,
                            ),
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
                }
                DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                        actions.forEachIndexed { index, action ->
                            DropdownMenuItem(
                                onClick = {
                                    menu = false
                                    action.run()
                                },
                                text = { Text(stringResource(action.label)) },
                                shape = menuItemShape(index, actions.size),
                                leadingIcon = {
                                    Icon(
                                        painterResource(action.icon),
                                        contentDescription = null,
                                        modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = enlarged,
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                label = "code-hint",
            ) { big ->
                val hint = when {
                    exportable && big -> R.string.code_tap_shrink_more
                    exportable -> R.string.code_tap_enlarge_more
                    big -> R.string.code_tap_shrink
                    else -> R.string.code_tap_enlarge
                }
                Text(
                    stringResource(hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
