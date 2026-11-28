package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

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
 * The corners of a segmented list, as the expressive list tokens give them: the group's outer
 * corners large, 16dp, and those between its rows extra small, 4dp. A pressed row rounds to the
 * large corner all round, so the outer corners stay put as it morphs.
 */
internal val OUTER_CORNER = 16.dp
internal val INNER_CORNER = 4.dp

/**
 * A section's header, in the primary colour above its rows, as settings, history and the format
 * picker show them. [top] and [bottom] are its space from what comes before and after, for a list
 * that adds a gap of its own between its items.
 */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier, top: Dp = SECTION_TOP, bottom: Dp = SECTION_GAP) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = LIST_TEXT_INSET, end = LIST_TEXT_INSET, top = top, bottom = bottom),
    )
}

/**
 * An intro or a note beside the rows of a section or inside one, in the muted body type a list
 * item's supporting text takes.
 */
@Composable
internal fun SectionNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * A section's header with [actions] at its end, spaced from the list as a header without them is.
 * The actions are taller than the title's line and centred on it, reaching into the space above and
 * below rather than pushing the section's rows further away than another section's are.
 */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit) {
    val title = MaterialTheme.typography.titleSmall
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = LIST_TEXT_INSET, top = SECTION_TOP, bottom = SECTION_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = title, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        val line = with(LocalDensity.current) { title.lineHeight.roundToPx() }
        Row(
            modifier = Modifier.layout { measurable, constraints ->
                val buttons = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                layout(buttons.width, line) { buttons.place(0, (line - buttons.height) / 2) }
            },
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * Which corners of a segment of a grouped list or of the favorites' tiles are corners of the group's
 * outline, and so take the large corner, where the others take the small one.
 */
internal class SegmentCorners(val topStart: Boolean, val topEnd: Boolean, val bottomStart: Boolean, val bottomEnd: Boolean) {
    /** A row's corners, large along its [top] edge when it is the group's first and along its [bottom] edge when its last. */
    constructor(top: Boolean, bottom: Boolean) : this(top, top, bottom, bottom)
}

/**
 * Expressive grouped list: large corners outside a group, small ones between its rows. The corners
 * follow a row that becomes the first or last of its group, as when a neighbour is deleted, where
 * the list item's own segmented shapes would switch at once.
 */
@Composable
fun segmentShape(index: Int, count: Int): Shape = rememberSegmentShape(SegmentCorners(top = index == 0, bottom = index == count - 1))

/**
 * The shape of a row or a tile with [corners] large, each corner following as it becomes or stops
 * being one on the fast spatial motion the list item morphs its own shape on, and rounding further
 * toward the large corner as far as [magnet] rounds it while an entry nearby is swiped. Read while
 * composing its own row or tile only, since the list item morphs between its shapes only when given
 * rounded corners as they are.
 */
@Composable
internal fun rememberSegmentShape(corners: SegmentCorners, magnet: Magnet? = null): Shape {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    return RoundedCornerShape(
        topStart = segmentCorner(corners.topStart, magnet?.run { topStart ?: topEnd }, spec, "segment-top-start"),
        topEnd = segmentCorner(corners.topEnd, magnet?.run { topEnd ?: topStart }, spec, "segment-top-end"),
        bottomEnd = segmentCorner(corners.bottomEnd, magnet?.run { bottomEnd ?: bottomStart }, spec, "segment-bottom-end"),
        bottomStart = segmentCorner(corners.bottomStart, magnet?.run { bottomStart ?: bottomEnd }, spec, "segment-bottom-start"),
    )
}

/** One corner of [rememberSegmentShape]: large while [outer], and at least as round as [rounding] has it. */
@Composable
private fun segmentCorner(
    outer: Boolean,
    rounding: SpringFollower<Float, AnimationVector1D>?,
    spec: AnimationSpec<Dp>,
    label: String,
): Dp {
    val base by animateDpAsState(if (outer) OUTER_CORNER else INNER_CORNER, spec, label = label)
    val round = if (rounding == null) 0f else rememberRounding(rounding).value
    return maxOf(base, lerp(INNER_CORNER, OUTER_CORNER, round))
}
