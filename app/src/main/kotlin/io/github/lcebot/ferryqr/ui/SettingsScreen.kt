// LargeFlexibleTopAppBar and ToggleButton are expressive APIs; the top bar's scroll behavior is an
// experimental Material 3 API.
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.LongCodeEnlarge
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.SensitiveKind

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenFerry: () -> Unit) {
    SettingsPage(title = R.string.settings_title, onBack = onBack) {
        item(key = "ferry") { FerryEntry(onOpenFerry) }
        item(key = "history") { HistorySettings() }
        item(key = "display") { DisplaySettings() }
        item(key = "create") { CreateSettings() }
    }
}

/**
 * A settings page: a large top bar that collapses as the list scrolls, over [content] laid out in
 * segmented groups.
 */
@Composable
internal fun SettingsPage(@StringRes title: Int, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    val haptics = rememberHaptics()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

/**
 * The way into the ferry settings, first since handing codes to apps is what FerryQR is for. A
 * second tap while the page opens does nothing, so it never opens twice.
 */
@Composable
private fun FerryEntry(onOpen: () -> Unit) {
    val haptics = rememberHaptics()
    var opening by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        opening = false
        onPauseOrDispose {}
    }
    Surface(
        onClick = {
            if (!opening) {
                opening = true
                haptics.tap()
                onOpen()
            }
        },
        shape = segmentShape(0, 1),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_apps),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_open_in_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_open_in_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painterResource(R.drawable.ic_arrow_forward),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Which kinds of sensitive content are kept in history after a scan, one switch for each. */
@Composable
private fun HistorySettings() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val kinds = SensitiveKind.entries
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(R.string.history_title)
        Text(
            stringResource(R.string.settings_keep_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
        )
        kinds.forEachIndexed { index, kind ->
            var keep by remember(kind) { mutableStateOf(AppSettings.keeps(context, kind)) }
            SwitchItem(
                title = keptLabel(kind),
                checked = keep,
                shape = segmentShape(index, kinds.size),
                onChange = { on ->
                    haptics.toggle(on)
                    keep = on
                    AppSettings.setKeeps(context, kind, on)
                },
            )
        }
    }
}

/** How enlarged codes are shown: long codes tilted or stretched, and the screen at full brightness. */
@Composable
private fun DisplaySettings() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var mode by remember { mutableStateOf(AppSettings.longCodeEnlarge(context)) }
    var bright by remember { mutableStateOf(AppSettings.fullBrightness(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(R.string.settings_display_title)
        Surface(shape = segmentShape(0, 2), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Text(stringResource(R.string.settings_long_codes), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_long_codes_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    LongCodeEnlarge.entries.forEachIndexed { index, option ->
                        ToggleButton(
                            checked = option == mode,
                            onCheckedChange = {
                                if (it) {
                                    haptics.step()
                                    mode = option
                                    AppSettings.setLongCodeEnlarge(context, option)
                                }
                            },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                LongCodeEnlarge.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        ) {
                            Text(stringResource(enlargeLabel(option)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(
                    stringResource(enlargeDescription(mode)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SwitchItem(
            title = R.string.settings_full_brightness,
            description = R.string.settings_full_brightness_desc,
            checked = bright,
            shape = segmentShape(1, 2),
            onChange = { on ->
                haptics.toggle(on)
                bright = on
                AppSettings.setFullBrightness(context, on)
            },
        )
    }
}

/** Whether the create page starts with the format chosen last time rather than choosing one itself. */
@Composable
private fun CreateSettings() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var keepFormat by remember { mutableStateOf(AppSettings.rememberFormat(context)) }
    Column {
        SectionTitle(R.string.generate_title)
        SwitchItem(
            title = R.string.settings_remember_format,
            description = R.string.settings_remember_format_desc,
            checked = keepFormat,
            shape = segmentShape(0, 1),
            onChange = { on ->
                haptics.toggle(on)
                keepFormat = on
                AppSettings.setRememberFormat(context, on)
            },
        )
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 28.dp, bottom = 14.dp),
    )
}

/** A setting that is on or off; the whole item toggles it, the way the system's own settings do. */
@Composable
private fun SwitchItem(
    @StringRes title: Int,
    checked: Boolean,
    shape: Shape,
    onChange: (Boolean) -> Unit,
    @StringRes description: Int? = null,
) {
    Surface(
        checked = checked,
        onCheckedChange = onChange,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Switch },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                if (description != null) {
                    Text(
                        stringResource(description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

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
