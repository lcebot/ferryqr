@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import io.github.lcebot.ferryqr.openin.OpenInHelper
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
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { OpenInHelper.refresh() }
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
                SectionHeader(R.string.settings_open_in_title, R.string.settings_open_in_intro)
            }
            itemsIndexed(apps, key = { _, app -> app.pkg }) { index, app ->
                AppSettingItem(app, segmentShape(index, apps.size))
            }
        }
    }
}

@Composable
private fun SectionHeader(@StringRes title: Int, @StringRes intro: Int) {
    Column(
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppSettingItem(app: AppSetting, shape: Shape) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val name = stringResource(app.name)
    val installed = remember(app.pkg) { Launch.isInstalled(context, app.pkg) }
    val state by OpenInHelper.state(app.pkg).collectAsStateWithLifecycle()
    var cameraFeed by remember(app.pkg) { mutableStateOf(OpenInHelper.cameraFeed(context, app.pkg)) }
    val method = app.fixed ?: if (cameraFeed) Method.Camera else Method.Direct
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

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
                    OpenInHelper.setCameraFeed(context, app.pkg, cameraFeed)
                },
            )
            val description = if (app.reason != null) stringResource(app.reason) else stringResource(method.description, name)
            AnimatedContent(targetState = description, transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) }) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
