// No opt-in: ToggleButton and the connected button shapes are stable APIs.
package io.github.lcebot.ferryqr.ui

import android.content.Context
import android.icu.text.Collator
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.dispatch.ActiveFerries
import io.github.lcebot.ferryqr.dispatch.ActiveFerry
import io.github.lcebot.ferryqr.dispatch.DefaultApp
import io.github.lcebot.ferryqr.dispatch.DefaultApps
import io.github.lcebot.ferryqr.dispatch.Handling
import io.github.lcebot.ferryqr.dispatch.Launch
import io.github.lcebot.ferryqr.dispatch.Route
import io.github.lcebot.ferryqr.dispatch.SystemFeature
import io.github.lcebot.ferryqr.dispatch.SystemFeatures
import io.github.lcebot.ferryqr.openin.ModuleState
import io.github.lcebot.ferryqr.openin.OpenInApps
import io.github.lcebot.ferryqr.openin.OpenInModule
import io.github.lcebot.ferryqr.openin.Strategy

/** How a code reaches an app's scanner, in the terms the settings use. */
private enum class Method(@StringRes val label: Int, @StringRes val description: Int) {
    Direct(R.string.method_direct, R.string.method_direct_desc),
    Camera(R.string.method_camera, R.string.method_camera_desc),
}

/** One app in the settings. [fixed] is its only method and [reason] says why; both are null where the user chooses. */
private class AppSetting(val pkg: String, @StringRes val name: Int, val fixed: Method?, @StringRes val reason: Int?)

/** The space between a list item's leading icon and its text. */
private val LEADING_TO_TEXT = 16.dp

private val REASONS = mapOf(
    OpenInApps.DIDI to R.string.method_didi_reason,
    OpenInApps.MEITUAN to R.string.method_meituan_reason,
)

/** Only a replaced result has an alternative, the camera; the other ways are the only ones that work. */
private fun settingOf(ferry: ActiveFerry): AppSetting {
    val app = OpenInApps.all.first { it.pkg == ferry.pkg }
    return when (app.strategy) {
        is Strategy.ReplaceResult -> AppSetting(app.pkg, ferry.name, fixed = null, reason = null)
        Strategy.FeedCamera -> AppSetting(app.pkg, ferry.name, Method.Camera, REASONS.getValue(app.pkg))
        is Strategy.FinishWithResult -> AppSetting(app.pkg, ferry.name, Method.Direct, REASONS.getValue(app.pkg))
    }
}

/**
 * Where codes go, in two tabs by whether the LSPosed module is needed. With it: each app the module
 * works in, with whether it works there and the method the app takes codes by. Without it: the apps
 * FerryQR reaches through their own entries, the features the system provides, and the app the
 * system picks for each kind of content, none of which has anything to set.
 */
@Composable
fun FerrySettingsScreen(onBack: () -> Unit) {
    // Each group lists its rows by name as the display language sorts them, the way an index reads.
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val collator = remember(locale) { Collator.getInstance(locale) }
    fun <T> sorted(items: List<T>, name: (T) -> Int) = items.sortedWith(compareBy(collator) { context.getString(name(it)) })
    val modules = remember(collator) { sorted(ActiveFerries.all.filter { it.route == Route.MODULE }) { it.name }.map(::settingOf) }
    val entries = remember(collator) { sorted(ActiveFerries.all.filter { it.route == Route.ENTRY }) { it.name } }
    val features = remember(collator) { sorted(SystemFeatures.all) { it.name } }
    val defaults = remember(collator) { sorted(DefaultApps.all) { it.name } }

    // Coming back from LSPosed, or from an app that restarted, the framework may say otherwise.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { OpenInModule.refresh() }
    }

    TabbedSettingsPage(
        title = R.string.settings_open_in_title,
        tabs = listOf(R.string.ferry_tab_module, R.string.ferry_tab_entry),
        onBack = onBack,
    ) { tab ->
        if (tab == 0) {
            item(key = "header") { OpenInHeader() }
            itemsIndexed(modules, key = { _, app -> app.pkg }) { index, app ->
                AppSettingItem(app, segmentShape(index, modules.size))
            }
        } else {
            item(key = "apps") {
                SettingsSection(R.string.ferry_group_apps, R.string.settings_entries_intro, first = true) {
                    entries.forEachIndexed { index, ferry -> EntryItem(ferry, segmentShape(index, entries.size)) }
                }
            }
            item(key = "system") {
                SettingsSection(R.string.ferry_group_system, R.string.ferry_group_system_intro) {
                    features.forEachIndexed { index, feature -> FeatureItem(feature, segmentShape(index, features.size)) }
                }
            }
            item(key = "defaults") {
                SettingsSection(R.string.ferry_group_defaults, R.string.ferry_group_defaults_intro) {
                    defaults.forEachIndexed { index, app -> DefaultAppItem(app, segmentShape(index, defaults.size)) }
                }
            }
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
        modifier = Modifier.padding(start = LIST_TEXT_INSET, end = LIST_TEXT_INSET, bottom = SECTION_GAP),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        Text(
            stringResource(R.string.settings_open_in_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.settings_methods_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Method.entries.forEach { method ->
            Column {
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

/**
 * One app or feature, laid out with the setting rows' measures rather than as a list item: [below]
 * spans the whole row under it, and a list item would either start it under the name, in its
 * supporting content, or keep its minimum height above it and leave a gap that grows and shrinks
 * with the status text. A [working] row's [status] stands out in the primary colour; anything else
 * reads as a note.
 */
@Composable
private fun AppRow(
    icon: @Composable () -> Unit,
    name: String,
    status: String,
    working: Boolean,
    shape: Shape,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val layoutDirection = LocalLayoutDirection.current
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.padding(
                    start = SETTING_ROW_PADDING.calculateStartPadding(layoutDirection),
                    end = SETTING_ROW_PADDING.calculateEndPadding(layoutDirection),
                    top = SETTING_ROW_PADDING.calculateTopPadding(),
                    bottom = if (below == null) SETTING_ROW_PADDING.calculateBottomPadding() else 0.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LEADING_TO_TEXT),
            ) {
                SettingIcon(icon)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (working) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (below != null) Column(
                // Level with the row's edges, and its padding below, as every setting row keeps.
                modifier = Modifier.padding(
                    start = SETTING_ROW_PADDING.calculateStartPadding(layoutDirection),
                    end = SETTING_ROW_PADDING.calculateEndPadding(layoutDirection),
                    top = SETTING_CONTROL_GAP,
                    bottom = SETTING_ROW_PADDING.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(SETTING_CONTROL_GAP),
                content = below,
            )
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
    val working = installed && (state == ModuleState.Loaded || state == ModuleState.Enabled)
    val status = when {
        !installed -> stringResource(R.string.app_not_installed)
        else -> when (state) {
            ModuleState.Inactive -> stringResource(R.string.module_inactive)
            ModuleState.OutOfScope -> stringResource(R.string.module_out_of_scope)
            ModuleState.Enabled -> stringResource(R.string.module_enabled, name)
            ModuleState.Loaded -> stringResource(R.string.module_active)
            ModuleState.Outdated -> stringResource(R.string.module_outdated, name)
        }
    }
    AppRow({ AppIcon(app.pkg, R.drawable.ic_apps, Modifier.size(24.dp)) }, name, status, working, shape) {
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
        if (app.reason != null) Note(app.reason)
    }
}

/** An app reached through an entry of its own: nothing to set, only whether it is there and what it opens. */
@Composable
private fun EntryItem(ferry: ActiveFerry, shape: Shape) {
    val installed = rememberOnResume { ferry.installed(it) }
    val status = stringResource(if (installed) R.string.entry_ready else R.string.app_not_installed)
    AppRow({ AppIcon(ferry.pkg, R.drawable.ic_apps, Modifier.size(24.dp)) }, stringResource(ferry.name), status, installed, shape) {
        ferry.summary?.let { Note(it) }
    }
}

/** A feature of the system: whether this device has it, and what it does. */
@Composable
private fun FeatureItem(feature: SystemFeature, shape: Shape) {
    val context = LocalContext.current
    val supported = remember(feature) { feature.supported(context) }
    val status = stringResource(if (supported) R.string.entry_ready else R.string.feature_unsupported)
    AppRow({ FeatureIcon(feature.icon) }, stringResource(feature.name), status, supported, shape) {
        Note(feature.summary)
    }
}

/** A kind of content and the app the system hands it to, which a change of default apps changes. */
@Composable
private fun DefaultAppItem(app: DefaultApp, shape: Shape) {
    val handling = rememberOnResume { app.handling(it) }
    val context = LocalContext.current
    val status = when (handling) {
        is Handling.App -> stringResource(R.string.default_opens_in, Launch.appLabel(context, handling.pkg) ?: handling.pkg)
        Handling.Asks -> stringResource(R.string.default_asks)
        Handling.None -> stringResource(R.string.default_none)
    }
    AppRow({ FeatureIcon(app.icon) }, stringResource(app.name), status, handling !is Handling.None, shape)
}

@Composable
private fun FeatureIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
}

/**
 * [read] again each time the page comes back, since what it reads, such as whether an app is
 * installed or which app is the default, can change in the system's settings meanwhile.
 */
@Composable
private fun <T> rememberOnResume(read: (Context) -> T): T {
    val context = LocalContext.current
    var value by remember { mutableStateOf(read(context)) }
    LifecycleResumeEffect(Unit) {
        value = read(context)
        onPauseOrDispose {}
    }
    return value
}

@Composable
private fun Note(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                colors = settingToggleColors(),
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
