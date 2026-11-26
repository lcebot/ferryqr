// The menu item shapes belong to the expressive menus, which are experimental.
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
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

/**
 * Expressive grouped list: large corners outside a group, small ones between its rows. The corners
 * follow a row that becomes the first or last of its group, as when a neighbour is deleted, where
 * the list item's own segmented shapes would switch at once.
 */
@Composable
fun segmentShape(index: Int, count: Int): Shape = segmentShape(index, count, roundTop = 0f, roundBottom = 0f)

/**
 * [roundTop] and [roundBottom], from 0 to 1, round a row's corners further toward the large corner,
 * as a swipe does to the row pulled and those around it.
 */
@Composable
internal fun segmentShape(index: Int, count: Int, roundTop: Float, roundBottom: Float): Shape {
    // On the fast spatial motion the list item morphs its own shape on.
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val top by animateDpAsState(if (index == 0) OUTER_CORNER else INNER_CORNER, spec, label = "row-top")
    val bottom by animateDpAsState(if (index == count - 1) OUTER_CORNER else INNER_CORNER, spec, label = "row-bottom")
    val shownTop = maxOf(top, lerp(INNER_CORNER, OUTER_CORNER, roundTop.coerceIn(0f, 1f)))
    val shownBottom = maxOf(bottom, lerp(INNER_CORNER, OUTER_CORNER, roundBottom.coerceIn(0f, 1f)))
    return RoundedCornerShape(topStart = shownTop, topEnd = shownTop, bottomStart = shownBottom, bottomEnd = shownBottom)
}

@Composable
fun menuItemShape(index: Int, count: Int): Shape = when {
    count == 1 -> MenuDefaults.standaloneItemShape
    index == 0 -> MenuDefaults.leadingItemShape
    index == count - 1 -> MenuDefaults.trailingItemShape
    else -> MenuDefaults.middleItemShape
}
