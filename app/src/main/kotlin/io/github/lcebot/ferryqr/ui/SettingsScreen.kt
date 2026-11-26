// The top bar's scroll behavior is an experimental Material 3 API; the expressive components here
// are stable.
@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.lcebot.ferryqr.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Label
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.lcebot.ferryqr.AppSettings
import io.github.lcebot.ferryqr.FavoriteLayout
import io.github.lcebot.ferryqr.LongCodeEnlarge
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.content.SensitiveKind
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenFerry: () -> Unit) {
    SettingsPage(title = R.string.settings_title, onBack = onBack) {
        item(key = "ferry") { SettingsSection(R.string.settings_ferry_title, first = true) { FerryEntry(onOpenFerry) } }
        item(key = "history") { HistorySettings() }
        item(key = "favorites") { FavoriteSettings() }
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
    SettingsScaffold(title, onBack, TopAppBarDefaults.topAppBarColors()) { padding -> SettingsList(padding, content) }
}

/**
 * A settings page split into [tabs], primary tabs under the top bar as Material 3 places them over
 * a page's own content. Each tab is a list of its own, which [content] fills for the tab's index,
 * reached by its tab or by swiping, and the top bar collapses as any of them scrolls.
 */
@Composable
internal fun TabbedSettingsPage(
    @StringRes title: Int,
    tabs: List<Int>,
    onBack: () -> Unit,
    content: LazyListScope.(Int) -> Unit,
) {
    val haptics = rememberHaptics()
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    // The top bar keeps one colour as it collapses, since the tabs under it, which do not change
    // theirs, would otherwise part from it; the tabs' own divider marks where the content begins.
    val surface = MaterialTheme.colorScheme.surface
    SettingsScaffold(
        title,
        onBack,
        TopAppBarDefaults.topAppBarColors(containerColor = surface, scrolledContainerColor = surface),
        tabs = {
            PrimaryTabRow(selectedTabIndex = pager.currentPage, containerColor = surface) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pager.currentPage == index,
                        onClick = {
                            haptics.tap()
                            scope.launch { pager.animateScrollToPage(index) }
                        },
                        text = { Text(stringResource(tab), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(pager, modifier = Modifier.fillMaxSize()) { page ->
            SettingsList(padding) { content(page) }
        }
    }
}

/** The large top bar that collapses as the page scrolls, with [tabs] under it, over [content]. */
@Composable
private fun SettingsScaffold(
    @StringRes title: Int,
    onBack: () -> Unit,
    colors: TopAppBarColors,
    tabs: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val haptics = rememberHaptics()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(title)) },
                    navigationIcon = {
                        IconButton(onClick = { haptics.tap(); onBack() }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                        }
                    },
                    colors = colors,
                    scrollBehavior = scrollBehavior,
                )
                tabs()
            }
        },
        content = content,
    )
}

/**
 * A page's [content] laid out in segmented groups, clear of the bars by [padding]. It fills the
 * page, so a short list starts at the top of a pager's page rather than in its middle.
 */
@Composable
private fun SettingsList(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection) + 16.dp,
            end = padding.calculateEndPadding(layoutDirection) + 16.dp,
            top = padding.calculateTopPadding() + PAGE_TOP,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        content = content,
    )
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
    SegmentedListItem(
        onClick = {
            if (!opening) {
                opening = true
                haptics.tap()
                onOpen()
            }
        },
        shapes = ListItemDefaults.shapes(shape = segmentShape(0, 1)),
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
    val kinds = SensitiveKind.entries
    // Named for what the switches do, so on reads as kept.
    SettingsSection(R.string.settings_keep_title, intro = R.string.settings_keep_intro) {
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

/**
 * How history lays out favorites, how many rows they fold down to, and whether a new favorite is
 * named before it is kept.
 */
@Composable
private fun FavoriteSettings() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var layout by remember { mutableStateOf(AppSettings.favoriteLayout(context)) }
    var naming by remember { mutableStateOf(AppSettings.namesFavorites(context)) }
    var rows by remember { mutableIntStateOf(AppSettings.foldedRows(context)) }
    SettingsSection(R.string.favorites_title) {
        SegmentedListItem(
            shapes = ListItemDefaults.shapes(shape = segmentShape(0, 3)),
            colors = settingColors(),
            contentPadding = SETTING_ROW_PADDING,
            supportingContent = {
                // The buttons keep the space from the title that controls keep from a description.
                Column(
                    modifier = Modifier.padding(top = SETTING_CONTROL_GAP),
                    verticalArrangement = Arrangement.spacedBy(SETTING_CONTROL_GAP),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        FavoriteLayout.entries.forEachIndexed { index, option ->
                            ToggleButton(
                                checked = option == layout,
                                colors = settingToggleColors(),
                                onCheckedChange = {
                                    if (it) {
                                        haptics.step()
                                        layout = option
                                        AppSettings.setFavoriteLayout(context, option)
                                    }
                                },
                                shapes = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    FavoriteLayout.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                            ) {
                                Text(stringResource(layoutLabel(option)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Text(stringResource(layoutDescription(layout)))
                }
            },
        ) {
            Text(stringResource(R.string.settings_favorite_layout))
        }
        SegmentedListItem(
            shapes = ListItemDefaults.shapes(shape = segmentShape(1, 3)),
            colors = settingColors(),
            contentPadding = SETTING_ROW_PADDING,
            supportingContent = {
                // The slider runs from 0 to 1, a stop for each value between the ends. While held, its
                // value indicator shows the value above the handle.
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
                            rows = picked
                            AppSettings.setFoldedRows(context, picked)
                        }
                    },
                    interactionSource = interactions,
                    thumb = {
                        Label(
                            label = {
                                PlainTooltip {
                                    Text(rows.toString(), style = MaterialTheme.typography.labelLarge)
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
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = SETTING_CONTROL_GAP),
                )
            }
        }
        SwitchItem(
            title = R.string.settings_name_favorites,
            checked = naming,
            shape = segmentShape(2, 3),
            onChange = { on ->
                haptics.toggle(on)
                naming = on
                AppSettings.setNamesFavorites(context, on)
            },
        )
    }
}

/** How enlarged codes are shown: long codes tilted or stretched, and the screen at full brightness. */
@Composable
private fun DisplaySettings() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var mode by remember { mutableStateOf(AppSettings.longCodeEnlarge(context)) }
    var bright by remember { mutableStateOf(AppSettings.fullBrightness(context)) }
    SettingsSection(R.string.settings_display_title) {
        SegmentedListItem(
            shapes = ListItemDefaults.shapes(shape = segmentShape(0, 2)),
            colors = settingColors(),
            contentPadding = SETTING_ROW_PADDING,
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(SETTING_CONTROL_GAP)) {
                    Text(stringResource(R.string.settings_long_codes_desc))
                    Row(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        LongCodeEnlarge.entries.forEachIndexed { index, option ->
                            ToggleButton(
                                checked = option == mode,
                                colors = settingToggleColors(),
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
                    Text(stringResource(enlargeDescription(mode)))
                }
            },
        ) {
            Text(stringResource(R.string.settings_long_codes))
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
    SettingsSection(R.string.generate_title) {
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

/** A group of settings under its header, with an optional [intro] between them. */
@Composable
internal fun SettingsSection(
    @StringRes title: Int,
    @StringRes intro: Int? = null,
    first: Boolean = false,
    rows: @Composable ColumnScope.() -> Unit,
) {
    Column {
        SectionHeader(stringResource(title), top = if (first) 0.dp else SECTION_TOP)
        if (intro != null) {
            Text(
                stringResource(intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = LIST_TEXT_INSET, end = LIST_TEXT_INSET, bottom = SECTION_GAP),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap), content = rows)
    }
}

/** A section's header, in the primary colour above its rows, as settings and history show them. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier, top: Dp = SECTION_TOP) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = LIST_TEXT_INSET, end = LIST_TEXT_INSET, top = top, bottom = SECTION_GAP),
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
    // The click overload rather than the checked one: that one would colour a row that is on as
    // selected, and read it as a checkbox.
    SegmentedListItem(
        onClick = { onChange(!checked) },
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = settingColors(),
        contentPadding = if (description != null) SETTING_ROW_PADDING else SETTING_LINE_PADDING,
        // Deliberately centred, against the list's rule that top-aligns the switch of a row three
        // lines tall: there it floats at the top of the row, away from the text it belongs to.
        verticalAlignment = Alignment.CenterVertically,
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        supportingContent = description?.let { { Text(stringResource(it)) } },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
    ) {
        Text(stringResource(title))
    }
}

/** The container colour every settings row shares, a step up from the page. */
@Composable
internal fun settingColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/*
 * One set of spaces for the grouped lists of settings and history, on the 4dp grid. Text outside
 * the rows, headers and notes, runs almost as wide as the rows, just 4dp in from their edges, so it
 * does not sit flush with an edge that rounds away at its corners; indented to the rows' own text
 * it would read as a narrow column. A section starts 24dp below the one before, and its header and
 * note keep 12dp from what follows, the same as a note from its controls and controls from each
 * other.
 */
internal val LIST_TEXT_INSET = 4.dp
internal val SECTION_TOP = 24.dp
internal val SECTION_GAP = 12.dp

/**
 * The space between the bars and a page's first content. The space between sections separates one
 * from the next, so the first section, with nothing above it, goes without it.
 */
private val PAGE_TOP = 16.dp

internal val SETTING_CONTROL_GAP = 12.dp

/**
 * Setting rows keep their text 16dp from every edge. A row of one line is 56dp tall at least, which
 * centres its text 16dp from the top and bottom with 12dp of padding; the list tokens' 10dp would
 * bring the text of taller rows, and of rows holding controls, closer to the edges than that, and
 * closer than the 12dp between a row's own text and controls.
 */
internal val SETTING_ROW_PADDING = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
internal val SETTING_LINE_PADDING = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/**
 * Unchecked toggle buttons one container role above the row they sit in. Their own default is the
 * surface container, made for a surface background; on a row already that colour they would vanish.
 */
@Composable
internal fun settingToggleColors() = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)

/** A setting's icon, on a round secondary container. */
@Composable
internal fun SettingIcon(icon: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSecondaryContainer, content = icon)
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
private fun layoutLabel(layout: FavoriteLayout): Int = when (layout) {
    FavoriteLayout.LIST -> R.string.favorite_layout_list
    FavoriteLayout.COMPACT -> R.string.favorite_layout_compact
    FavoriteLayout.GRID -> R.string.favorite_layout_grid
}

@StringRes
private fun layoutDescription(layout: FavoriteLayout): Int = when (layout) {
    FavoriteLayout.LIST -> R.string.favorite_layout_list_desc
    FavoriteLayout.COMPACT -> R.string.favorite_layout_compact_desc
    FavoriteLayout.GRID -> R.string.favorite_layout_grid_desc
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
