// rememberBottomSheetState is an experimental Material 3 API.
@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.lcebot.ferryqr.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.generate.CodeFamily
import io.github.lcebot.ferryqr.generate.CodeGenerator
import io.github.lcebot.ferryqr.generate.CodeProblem
import io.github.lcebot.ferryqr.generate.CodeType
import io.github.lcebot.ferryqr.generate.Generated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How tall each format's preview is; wide enough tiles show a barcode at a readable size. */
private val PREVIEW_HEIGHT = 72.dp

/**
 * The tile's corners and padding, from the shape and spacing scales. The preview inside is as much
 * less rounded as the padding is wide, so the two outlines run parallel.
 */
private val TILE_CORNER = 16.dp
private val TILE_PADDING = 12.dp
private val PREVIEW_CORNER = TILE_CORNER - TILE_PADDING

/**
 * White space around every preview, the same for all of them. Previews are drawn without their
 * quiet zones, which differ from format to format and would make some codes look far smaller.
 */
private val PREVIEW_PADDING = 8.dp

/** A format as the picker shows it: the code drawn in it, or why it cannot hold the text. */
private class FormatOption(val type: CodeType, val preview: Bitmap?, val problem: CodeProblem?)

/**
 * Every format FerryQR can draw, each previewed with [text] itself so the right one is found by
 * sight rather than by name, grouped into 2D codes, product barcodes and other barcodes. A format
 * that cannot hold the text is greyed out with the reason in place of its preview. [selected] is
 * marked, and [original] is the format the code was read in, when it came from a scan.
 */
@Composable
fun FormatPickerSheet(
    text: String,
    selected: CodeType?,
    original: CodeType?,
    onPick: (CodeType) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptics()
    // Every tile is there from the start at its full size, and each preview fades in as it is drawn,
    // so the sheet opens at its height rather than growing once the previews are ready.
    val options = remember(text) { mutableStateMapOf<CodeType, FormatOption>() }
    LaunchedEffect(text) {
        for (type in CodeType.entries) {
            options[type] = withContext(Dispatchers.Default) {
                // With nothing typed yet, every format can still be chosen; there is just nothing to preview.
                if (text.isEmpty()) return@withContext FormatOption(type, null, null)
                when (val result = CodeGenerator.generate(text, type, quietZone = false, withAlternatives = false)) {
                    is Generated.Code -> FormatOption(type, result.bitmap, null)
                    is Generated.Unfit -> FormatOption(type, null, result.problem)
                }
            }
        }
    }
    val sheet = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Text(
            stringResource(R.string.format_pick_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.selectableGroup(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CodeFamily.entries.forEach { family ->
                item(key = "family:$family", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(family.label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(CodeType.entries.filter { it.family == family }, key = { it.name }) { type ->
                    FormatTile(
                        type = type,
                        option = options[type],
                        selected = type == selected,
                        original = type == original,
                        onClick = {
                            haptics.step()
                            onPick(type)
                        },
                    )
                }
            }
        }
    }
}

/** [option] is null until the preview of [type] is drawn; the tile can be picked once the format is known to fit. */
@Composable
private fun FormatTile(type: CodeType, option: FormatOption?, selected: Boolean, original: Boolean, onClick: () -> Unit) {
    val available = option?.problem == null
    val colors = MaterialTheme.colorScheme
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val textAlpha by animateFloatAsState(if (available) 1f else 0.38f, fade, label = "format-text")
    val previewColor by animateColorAsState(
        if (available) Color.White else colors.surfaceContainerHighest,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "format-preview-color",
    )
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = option != null && available,
        shape = RoundedCornerShape(TILE_CORNER),
        color = colors.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
    ) {
        Column(Modifier.padding(TILE_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PREVIEW_HEIGHT)
                    .clip(RoundedCornerShape(PREVIEW_CORNER))
                    .background(previewColor),
                contentAlignment = Alignment.Center,
            ) {
                FormatPreview(option, fade)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(type.label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false).graphicsLayer { alpha = textAlpha },
                )
                if (original) {
                    Text(
                        stringResource(R.string.format_original),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onTertiaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.tertiaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (selected) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                stringResource(type.note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { alpha = textAlpha },
            )
        }
    }
}

/**
 * What a format's tile shows above its name: nothing while [option] is still drawn, then the code
 * in it, or a placeholder with nothing typed, or why the text does not fit, fading in on [fade].
 */
@Composable
private fun FormatPreview(option: FormatOption?, fade: FiniteAnimationSpec<Float>) {
    AnimatedContent(
        targetState = option,
        transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
        label = "format-preview",
    ) { shown ->
        val preview = shown?.preview
        val problem = shown?.problem
        when {
            shown == null -> Box(Modifier.fillMaxSize())
            preview != null -> {
                val image = remember(preview) { preview.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                    // Filling the box in both directions lets Fit scale the code up; with only the
                    // width fixed, the image keeps its height of one pixel per module.
                    modifier = Modifier.fillMaxSize().padding(PREVIEW_PADDING),
                )
            }
            problem == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_qr_code_2), contentDescription = null, tint = Color.Black.copy(alpha = 0.38f))
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    problemText(problem),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** Why a format cannot hold a text, in a few words. */
@Composable
fun problemText(problem: CodeProblem): String = when (problem) {
    CodeProblem.TooLong -> stringResource(R.string.format_too_long)
    CodeProblem.DigitsOnly -> stringResource(R.string.format_digits_only)
    is CodeProblem.DigitCount -> stringResource(problem.requirement)
    is CodeProblem.CheckDigit -> stringResource(R.string.format_check_digit, problem.expected.toString())
    CodeProblem.EvenDigits -> stringResource(R.string.format_needs_even)
    CodeProblem.Characters -> stringResource(R.string.format_characters)
    CodeProblem.Rules -> stringResource(R.string.format_rules)
}
