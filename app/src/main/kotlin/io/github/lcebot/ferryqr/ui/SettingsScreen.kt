@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.annotation.StringRes
import androidx.compose.material3.Switch
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.LongCodeEnlarge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.Launch
import io.github.lcebot.ferryqr.dispatch.Targets
import io.github.lcebot.ferryqr.openin.OpenInApp
import io.github.lcebot.ferryqr.openin.OpenInApps
import io.github.lcebot.ferryqr.openin.ModuleState
import io.github.lcebot.ferryqr.openin.OpenInModule
import io.github.lcebot.ferryqr.openin.Strategy

/** How a code reaches an app's scanner, in the terms the settings use. */
private enum class Method(@StringRes val label: Int, @StringRes val description: Int) {
    Direct(R.string.method_direct, R.string.method_direct_desc),
    Camera(R.string.method_camera, R.string.method_camera_desc),
}

/** One app in the settings. [fixed] is its only method and [reason] says why; both are null where the user chooses. */
private class AppSetting(val pkg: String, @StringRes val name: Int, val fixed: Method?, @StringRes val reason: Int?)

private val REASONS = mapOf(
    OpenInApps.DIDI to R.string.method_didi_reason,
    OpenInApps.MEITUAN to R.string.method_meituan_reason,
)

/** Only a replaced result has an alternative, the camera; the other ways are the only ones that work. */
private fun settingOf(app: OpenInApp): AppSetting {
    val name = Targets.of(app.pkg)?.name ?: error("${app.pkg} has no name")
    return when (app.strategy) {
        is Strategy.ReplaceResult -> AppSetting(app.pkg, name, fixed = null, reason = null)
        Strategy.FeedCamera -> AppSetting(app.pkg, name, Method.Camera, REASONS.getValue(app.pkg))
        is Strategy.FinishWithResult -> AppSetting(app.pkg, name, Method.Direct, REASONS.getValue(app.pkg))
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val haptics = rememberHaptics()
    val apps = remember { OpenInApps.all.map(::settingOf) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current

    // Coming back from LSPosed, or from an app that restarted, the framework may say otherwise.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { OpenInModule.refresh() }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "header") {
                OpenInHeader()
            }
            itemsIndexed(apps, key = { _, app -> app.pkg }) { index, app ->
                AppSettingItem(app, segmentShape(index, apps.size))
            }
            item(key = "display") { DisplaySettings() }
            item(key = "create") { CreateSettings() }
        }
    }
}

/**
 * What the module does, then the two methods named once, so each app's buttons need no words of
 * their own unless the app is limited to one method.
 */
@Composable
private fun OpenInHeader() {
    Column(
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.settings_open_in_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.settings_open_in_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.settings_methods_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        Method.entries.forEach { method ->
            Column(Modifier.padding(top = 8.dp)) {
                Text(stringResource(method.label), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(method.description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppSettingItem(app: AppSetting, shape: Shape) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val name = stringResource(app.name)
    val installed = remember(app.pkg) { Launch.isInstalled(context, app.pkg) }
    val state by OpenInModule.state(app.pkg).collectAsStateWithLifecycle()
    var cameraFeed by remember(app.pkg) { mutableStateOf(OpenInModule.cameraFeed(context, app.pkg)) }
    val method = app.fixed ?: if (cameraFeed) Method.Camera else Method.Direct
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSecondaryContainer) {
                        AppIcon(app.pkg, R.drawable.ic_apps, Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    val working = installed && (state == ModuleState.Loaded || state == ModuleState.Enabled)
                    Text(
                        when {
                            !installed -> stringResource(R.string.app_not_installed)
                            else -> when (state) {
                                ModuleState.Inactive -> stringResource(R.string.module_inactive)
                                ModuleState.OutOfScope -> stringResource(R.string.module_out_of_scope)
                                ModuleState.Enabled -> stringResource(R.string.module_enabled, name)
                                ModuleState.Loaded -> stringResource(R.string.module_active)
                                ModuleState.Outdated -> stringResource(R.string.module_outdated, name)
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (working) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            MethodButtons(
                selected = method,
                choosable = app.fixed == null,
                onSelect = { chosen ->
                    haptics.toggle(chosen == Method.Camera)
                    cameraFeed = chosen == Method.Camera
                    OpenInModule.setCameraFeed(context, app.pkg, cameraFeed)
                },
            )
            // Only an app limited to one method says why; the methods themselves are described above.
            if (app.reason != null) {
                Text(stringResource(app.reason), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A connected button group of the methods. An app with one method shows it chosen and the other
 * unavailable, so every app reads the same way.
 */
@Composable
private fun MethodButtons(selected: Method, choosable: Boolean, onSelect: (Method) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        Method.entries.forEachIndexed { index, method ->
            val checked = method == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = { if (it) onSelect(method) },
                enabled = choosable || checked,
                shapes = if (index == 0) {
                    ButtonGroupDefaults.connectedLeadingButtonShapes()
                } else {
                    ButtonGroupDefaults.connectedTrailingButtonShapes()
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        role = Role.RadioButton
                        // The chosen method stays drawn as chosen, but cannot be changed.
                        if (!choosable) disabled()
                    },
            ) {
                Text(stringResource(method.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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

/** A setting that is on or off; the whole item toggles it, the way the system's own settings do. */
@Composable
private fun SwitchItem(@StringRes title: Int, @StringRes description: Int, checked: Boolean, shape: Shape, onChange: (Boolean) -> Unit) {
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
                Text(
                    stringResource(description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
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
