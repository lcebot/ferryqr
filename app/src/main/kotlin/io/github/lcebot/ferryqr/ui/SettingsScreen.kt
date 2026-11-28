// The slider's value indicator is a Label, an experimental Material 3 API; the expressive
// components here are stable.
@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.lcebot.ferryqr.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Label
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.LongCodeEnlarge
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.SensitiveKind
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenFerry: () -> Unit) {
    SettingsPage(title = R.string.settings_title, onBack = onBack) {
        item(key = "ferry") {
            SettingsSection(R.string.settings_ferry_title, first = true) { row { shape -> FerryEntry(onOpenFerry, shape) } }
        }
        item(key = "history") { HistorySettings() }
        item(key = "favorites") { FavoriteSettings() }
        item(key = "display") { DisplaySettings() }
        item(key = "create") { CreateSettings() }
    }
}

/**
 * The way into the ferry settings, first since handing codes to apps is what FerryQR is for. A
 * second tap while the page opens does nothing, so it never opens twice.
 */
@Composable
private fun FerryEntry(onOpen: () -> Unit, shape: Shape) {
    val haptics = rememberHaptics()
    val once = rememberOpenOnce()
    SegmentedListItem(
        onClick = {
            once {
                haptics.tap()
                onOpen()
            }
        },
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = settingColors(),
        contentPadding = SETTING_ROW_PADDING,
        // Deliberately centred, as the switch rows are, against the list's rule that top-aligns the
        // icons of a row three lines tall, which a long summary or a large font makes this one.
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = {
            SettingIcon { Icon(painterResource(R.drawable.ic_apps), contentDescription = null, modifier = Modifier.size(24.dp)) }
        },
        trailingContent = { Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null) },
        supportingContent = { Text(stringResource(R.string.settings_open_in_summary)) },
    ) {
        Text(stringResource(R.string.settings_open_in_title))
    }
}

/** Which kinds of sensitive content are kept in history after a scan, one switch for each. */
@Composable
private fun HistorySettings() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // Named for what the switches do, so on reads as kept.
    SettingsSection(R.string.settings_keep_title, intro = R.string.settings_keep_intro) {
        SensitiveKind.entries.forEach { kind ->
            row { shape ->
                val setting = remember(kind) { AppSettings.of(context).keeps(kind) }
                val keep by setting.flow.collectAsStateWithLifecycle()
                SwitchItem(
                    title = keptLabel(kind),
                    checked = keep,
                    shape = shape,
                    onChange = { on ->
                        haptics.toggle(on)
                        setting.value = on
                    },
                )
            }
        }
    }
}

/**
 * Favorites in the order they come into use: whether a new favorite is named before it is kept, how
 * history lays them out, and how many rows they fold down to. Favorites show as a list or as tiles;
 * only tiles can line up in a grid, so that choice opens under the layout while tiles are chosen.
 */
@Composable
private fun FavoriteSettings() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val settings = remember { AppSettings.of(context) }
    val tiles by settings.favoriteTiles.flow.collectAsStateWithLifecycle()
    val grid by settings.tileGrid.flow.collectAsStateWithLifecycle()
    val naming by settings.namesFavorites.flow.collectAsStateWithLifecycle()
    val rows by settings.foldedRows.flow.collectAsStateWithLifecycle()
    SettingsSection(R.string.favorites_title) {
        row { shape ->
            SwitchItem(
                title = R.string.settings_name_favorites,
                checked = naming,
                shape = shape,
                onChange = { on ->
                    haptics.toggle(on)
                    settings.namesFavorites.value = on
                },
            )
        }
        row { shape ->
            SegmentedListItem(
                shapes = ListItemDefaults.shapes(shape = shape),
                colors = settingColors(),
                contentPadding = SETTING_ROW_PADDING,
                supportingContent = {
                    // The buttons keep the space from the title that controls keep from a description.
                    Column(
                        modifier = Modifier.padding(top = SETTING_CONTROL_GAP),
                        verticalArrangement = Arrangement.spacedBy(SETTING_CONTROL_GAP),
                    ) {
                        ConnectedChoice(
                            options = listOf(false, true),
                            selected = tiles,
                            label = { if (it) R.string.favorite_layout_tiles else R.string.favorite_layout_list },
                            onSelect = { settings.favoriteTiles.value = it },
                        )
                        ChoiceDescription(if (tiles) R.string.favorite_layout_tiles_desc else R.string.favorite_layout_list_desc)
                    }
                },
            ) {
                Text(stringResource(R.string.settings_favorite_layout))
            }
        }
        conditionalRow(visible = tiles) { shape ->
            SwitchItem(
                title = R.string.settings_tile_grid,
                description = R.string.settings_tile_grid_desc,
                checked = grid,
                shape = shape,
                onChange = { on ->
                    haptics.toggle(on)
                    settings.tileGrid.value = on
                },
            )
        }
        row { shape ->
            SegmentedListItem(
                shapes = ListItemDefaults.shapes(shape = shape),
                colors = settingColors(),
                contentPadding = SETTING_ROW_PADDING,
                supportingContent = {
                    // The slider runs from 0 to 1, a stop for each value between the ends. While held, its
                    // value indicator shows the value above the handle: a pill in the inverse surface
                    // colours with the value centred in it, padded as Material's own slider label is.
                    val first = AppSettings.FOLDED_ROWS.first
                    val span = AppSettings.FOLDED_ROWS.last - first
                    val slider = remember { SliderState(value = (rows - first).toFloat() / span, steps = span - 1) }
                    val interactions = remember { MutableInteractionSource() }
                    Slider(
                        state = slider,
                        onValueChange = { value ->
                            slider.value = value
                            val picked = first + (value * span).roundToInt()
                            if (picked != rows) {
                                haptics.step()
                                settings.foldedRows.value = picked
                            }
                        },
                        interactionSource = interactions,
                        thumb = {
                            Label(
                                label = {
                                    Box(
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = VALUE_INDICATOR_MIN_WIDTH, minHeight = VALUE_INDICATOR_MIN_HEIGHT)
                                            .background(MaterialTheme.colorScheme.inverseSurface, CircleShape)
                                            .padding(VALUE_INDICATOR_PADDING),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            rows.toString(),
                                            style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                                            color = MaterialTheme.colorScheme.inverseOnSurface,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                },
                                interactionSource = interactions,
                            ) {
                                SliderDefaults.Thumb(interactionSource = interactions)
                            }
                        },
                        modifier = Modifier.padding(top = SETTING_CONTROL_GAP),
                    )
                },
            ) {
                // At rest the value sits on the title's line as a list item's trailing supporting text,
                // rather than in the trailing slot beside the slider, so the slider runs the row's full
                // width. Figures of one width keep the title still as the value changes.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_folded_rows), modifier = Modifier.weight(1f))
                    Text(
                        rows.toString(),
                        style = MaterialTheme.typography.labelSmall.withTabularFigures(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = SETTING_CONTROL_GAP),
                    )
                }
            }
        }
    }
}

/** How enlarged codes are shown: long codes tilted or stretched, and the screen at full brightness. */
@Composable
private fun DisplaySettings() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val settings = remember { AppSettings.of(context) }
    val mode by settings.longCodeEnlarge.flow.collectAsStateWithLifecycle()
    val bright by settings.fullBrightness.flow.collectAsStateWithLifecycle()
    SettingsSection(R.string.settings_display_title) {
        row { shape ->
            SegmentedListItem(
                shapes = ListItemDefaults.shapes(shape = shape),
                colors = settingColors(),
                contentPadding = SETTING_ROW_PADDING,
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(SETTING_CONTROL_GAP)) {
                        Text(stringResource(R.string.settings_long_codes_desc))
                        ConnectedChoice(
                            options = LongCodeEnlarge.entries,
                            selected = mode,
                            label = ::enlargeLabel,
                            onSelect = { settings.longCodeEnlarge.value = it },
                        )
                        ChoiceDescription(enlargeDescription(mode))
                    }
                },
            ) {
                Text(stringResource(R.string.settings_long_codes))
            }
        }
        row { shape ->
            SwitchItem(
                title = R.string.settings_full_brightness,
                description = R.string.settings_full_brightness_desc,
                checked = bright,
                shape = shape,
                onChange = { on ->
                    haptics.toggle(on)
                    settings.fullBrightness.value = on
                },
            )
        }
    }
}

/**
 * The description of the option chosen in the row above it. Another option's description fades in
 * over it while the row resizes to the new text on the section's own spring, so the rows below
 * move to their places rather than jump.
 */
@Composable
private fun ChoiceDescription(@StringRes description: Int) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    AnimatedContent(
        targetState = description,
        transitionSpec = { crossfadeResizing(effects, spatial) },
        modifier = Modifier.fillMaxWidth(),
        label = "choice-description",
    ) { shown ->
        Text(stringResource(shown))
    }
}

/** Whether the create page starts with the format chosen last time rather than choosing one itself. */
@Composable
private fun CreateSettings() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val setting = remember { AppSettings.of(context).rememberFormat }
    val keepFormat by setting.flow.collectAsStateWithLifecycle()
    SettingsSection(R.string.generate_title) {
        row { shape ->
            SwitchItem(
                title = R.string.settings_remember_format,
                description = R.string.settings_remember_format_desc,
                checked = keepFormat,
                shape = shape,
                onChange = { on ->
                    haptics.toggle(on)
                    setting.value = on
                },
            )
        }
    }
}

/**
 * A slider's value indicator, as Material's own slider label measures it: 10dp beside the value,
 * the value indicator's 12dp above and below it, and no smaller than 28 by 32dp.
 */
private val VALUE_INDICATOR_PADDING = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
private val VALUE_INDICATOR_MIN_WIDTH = 28.dp
private val VALUE_INDICATOR_MIN_HEIGHT = 32.dp

@StringRes
private fun keptLabel(kind: SensitiveKind): Int = when (kind) {
    SensitiveKind.WIFI_PASSWORD -> R.string.keep_wifi_password
    SensitiveKind.BOARDING_PASS -> R.string.kind_boarding_pass
    SensitiveKind.ESIM -> R.string.kind_esim
    SensitiveKind.PASSKEY -> R.string.kind_fido
    SensitiveKind.OTP -> R.string.kind_otp
}

@StringRes
private fun enlargeLabel(mode: LongCodeEnlarge): Int = when (mode) {
    LongCodeEnlarge.TILT -> R.string.enlarge_tilt
    LongCodeEnlarge.STRETCH -> R.string.enlarge_stretch
    LongCodeEnlarge.FULL_SCREEN -> R.string.enlarge_full_screen
}

@StringRes
private fun enlargeDescription(mode: LongCodeEnlarge): Int = when (mode) {
    LongCodeEnlarge.TILT -> R.string.enlarge_tilt_desc
    LongCodeEnlarge.STRETCH -> R.string.enlarge_stretch_desc
    LongCodeEnlarge.FULL_SCREEN -> R.string.enlarge_full_screen_desc
}
