package io.github.lcebot.ferryqr.ui

import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import io.github.lcebot.ferryqr.FavoriteLayout
import io.github.lcebot.ferryqr.dispatch.ContentNames
import io.github.lcebot.ferryqr.history.HistoryEntry

/**
 * From this many favorites, where tiles would take at most half the rows the list does, history
 * suggests tiles once.
 */
private const val SUGGEST_TILES_AT = 6

/**
 * The favorites as the history page shows them, worked out in one place from the favorites, the
 * settings that lay them out and the widths their tiles measure, so the page only shows what this
 * says. They show in [layout], as rows of the list or as tiles; they fold when [foldable], and
 * folded while [folded], to the first rows, hiding [hiddenCount] of them. The list shows [rows],
 * the favorites' rows in the order shown, and the tiles are laid out from [tiles] as [arrangement]
 * with [rowsShown] of their rows in view. Favorites are moved through [reorder], the tiles' board
 * or the list's reordering, whichever [layout] uses. Only the list has a mode for moving them,
 * [reorderable] with more than one favorite and [reordering] while it is on, since tiles are
 * dragged straight away. [suggestTiles] says the moment has come to suggest tiles.
 */
internal class FavoritesSection(
    val layout: FavoriteLayout,
    val foldable: Boolean,
    val folded: Boolean,
    val hiddenCount: Int,
    val rows: List<HistoryEntry>,
    val tiles: TileInputs,
    val arrangement: TileArrangement,
    val rowsShown: Int,
    val reorder: FavoritesReorder,
    val reorderable: Boolean,
    val reordering: Boolean,
    val suggestTiles: Boolean,
) {
    val tiled: Boolean get() = layout != FavoriteLayout.LIST
}

/**
 * The favorites section for [favorites], shown by the [names] they go by, in [layout], [folded]
 * or not to [foldedRows], in a page [available] pixels wide inside its margins. [tilesSuggested]
 * says whether tiles were suggested already. It is built again only when one of these changes, or
 * the order [board] or [listReorder] moves the favorites into while one is moved, and it keeps
 * both of them in step with what it shows.
 */
@Composable
internal fun rememberFavoritesSection(
    favorites: List<HistoryEntry>,
    names: List<ContentNames>,
    layout: FavoriteLayout,
    folded: Boolean,
    foldedRows: Int,
    tilesSuggested: Boolean,
    available: Int,
    board: TileBoard,
    listReorder: ListReorderState,
): FavoritesSection {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    LaunchedEffect(favorites) { listReorder.sync(favorites) }
    // Tiles are as wide as their names, measured before they are laid out, so the rows they fill
    // are known for folding them and for suggesting them.
    val widths = rememberTileWidths(names, with(density) { tileChrome(layoutDirection).roundToPx() })
    val gap = with(density) { ListItemDefaults.SegmentedGap.roundToPx() }
    val column = with(density) { GRID_COLUMN.roundToPx() }
    // Read here rather than while building, so moving a favorite builds the section again.
    val tileOrder = if (board.key != null) board.order.toList() else null
    val listOrder = if (listReorder.active) listReorder.order.toList() else null
    val section = remember(
        favorites, layout, folded, foldedRows, tilesSuggested, widths, available, gap, column, tileOrder, listOrder, board, listReorder,
    ) {
        if (layout != FavoriteLayout.LIST) {
            val present = favorites.mapTo(HashSet()) { it.text }
            val keys = tileOrder?.filter { it in present } ?: favorites.map { it.text }
            val columns = if (layout == FavoriteLayout.GRID) gridColumns(available, gap, column) else null
            val tiles = TileInputs(keys, widths, available, gap, columns)
            val arrangement = tiles.arrange()
            val foldable = arrangement.rows > foldedRows
            FavoritesSection(
                layout = layout,
                foldable = foldable,
                folded = folded,
                hiddenCount = arrangement.spots.count { it.row >= foldedRows },
                rows = emptyList(),
                tiles = tiles,
                arrangement = arrangement,
                rowsShown = if (foldable && folded) foldedRows else arrangement.rows,
                reorder = board,
                reorderable = false,
                reordering = false,
                suggestTiles = false,
            )
        } else {
            val reordering = listOrder != null
            // Folded, the favorites show their first few rows, which in the list are favorites.
            val foldable = favorites.size > foldedRows && !reordering
            val byKey = favorites.associateBy { it.text }
            val ordered = listOrder?.mapNotNull { byKey[it] } ?: favorites
            // Tiles fit as many favorites in a row as their names allow; once there are enough
            // favorites that tiles would at least halve their rows, history suggests them, once.
            // The tiles are arranged for that only while the suggestion can still be made.
            val suggestible = !tilesSuggested && !reordering && favorites.size >= SUGGEST_TILES_AT
            val worthTiles = suggestible &&
                arrangeTiles(favorites.map { it.text }, { widths[it] ?: 0 }, available, gap, null).rows * 2 <= favorites.size
            FavoritesSection(
                layout = layout,
                foldable = foldable,
                folded = folded,
                hiddenCount = favorites.size - foldedRows,
                rows = if (foldable && folded) ordered.take(foldedRows) else ordered,
                tiles = TileInputs.NONE,
                arrangement = TileInputs.NONE.arrange(),
                rowsShown = 0,
                reorder = listReorder,
                reorderable = favorites.size > 1,
                reordering = reordering,
                suggestTiles = worthTiles,
            )
        }
    }
    SideEffect { board.update(section.tiles, section.rowsShown) }
    return section
}
