// No opt-in: ToggleButton, the connected button shapes and ListItem are stable APIs.
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
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
import io.github.lcebot.ferryqr.openin.ModuleState
import io.github.lcebot.ferryqr.openin.OpenInApp
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

/**
 * How codes reach other apps through the LSPosed module: what the module does, then each app it
 * works in, with whether it is working there and the method that app takes codes by.
 */
@Composable
fun FerrySettingsScreen(onBack: () -> Unit) {
    val apps = remember { OpenInApps.all.map(::settingOf) }

    // Coming back from LSPosed, or from an app that restarted, the framework may say otherwise.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { OpenInModule.refresh() }
    }

    SettingsPage(title = R.string.settings_open_in_title, onBack = onBack) {
        item(key = "header") { OpenInHeader() }
        itemsIndexed(apps, key = { _, app -> app.pkg }) { index, app ->
            AppSettingItem(app, segmentShape(index, apps.size))
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
    val rowPadding = ListItemDefaults.ContentPadding
    val layoutDirection = LocalLayoutDirection.current
    // The app is a list item, and its buttons span the whole row under it; in the item's supporting
    // content they would start under the app's name instead.
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                // The controls below continue the row, so the item leaves its bottom padding to them.
                contentPadding = PaddingValues(
                    start = rowPadding.calculateStartPadding(layoutDirection),
                    end = rowPadding.calculateEndPadding(layoutDirection),
                    top = rowPadding.calculateTopPadding(),
                ),
                leadingContent = { SettingIcon { AppIcon(app.pkg, R.drawable.ic_apps, Modifier.size(24.dp)) } },
                supportingContent = {
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
                        // Working stands out in the primary colour; anything else reads as a note.
                        color = if (working) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    )
                },
            ) {
                Text(name)
            }
            Column(
                // Level with the list item's own edges, and its padding below.
                modifier = Modifier.padding(
                    start = rowPadding.calculateStartPadding(layoutDirection),
                    end = rowPadding.calculateEndPadding(layoutDirection),
                    top = SETTING_CONTROL_GAP,
                    bottom = rowPadding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(SETTING_CONTROL_GAP),
            ) {
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
                    Text(
                        stringResource(app.reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
