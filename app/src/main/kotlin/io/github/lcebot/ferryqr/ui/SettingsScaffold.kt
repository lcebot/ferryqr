// No opt-in: ToggleButton and the connected button shapes are stable APIs.
package io.github.lcebot.ferryqr.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lcebot.ferryqr.R
import kotlinx.coroutines.launch

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
    // A tapped tab moves the page on the theme's spring, and the indicator heads for the page the
    // pager is going to, so both set off together rather than the indicator waiting for halfway.
    val pageSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    // The top bar keeps one colour as it collapses, since the tabs under it, which do not change
    // theirs, would otherwise part from it; the tabs' own divider marks where the content begins.
    val surface = MaterialTheme.colorScheme.surface
    SettingsScaffold(
        title,
        onBack,
        TopAppBarDefaults.topAppBarColors(containerColor = surface, scrolledContainerColor = surface),
        tabs = {
            PrimaryTabRow(selectedTabIndex = pager.targetPage, containerColor = surface) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pager.targetPage == index,
                        onClick = {
                            haptics.tap()
                            scope.launch { pager.animateScrollToPage(index, animationSpec = pageSpec) }
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
                        IconButton(onClick = { haptics.tap(); onBack() }, shapes = IconButtonDefaults.shapes()) {
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

/** A setting that is on or off; the whole item toggles it, the way the system's own settings do. */
@Composable
internal fun SwitchItem(
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

/**
 * A connected button group choosing one of [options], each named by the string [label] gives it.
 * A [locked] group keeps [selected] drawn as chosen and the other options unavailable, so a choice
 * that cannot be changed reads the same way as one that can.
 */
@Composable
internal fun <T> ConnectedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    locked: Boolean = false,
    haptic: Haptics.(T) -> Unit = { step() },
) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            val checked = option == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = {
                    if (it) {
                        haptics.haptic(option)
                        onSelect(option)
                    }
                },
                colors = settingToggleColors(),
                enabled = !locked || checked,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        role = Role.RadioButton
                        // The chosen option stays drawn as chosen, but cannot be changed.
                        if (locked) disabled()
                    },
            ) {
                Text(stringResource(label(option)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The container colour every settings row shares, a step up from the page. */
@Composable
internal fun settingColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

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
