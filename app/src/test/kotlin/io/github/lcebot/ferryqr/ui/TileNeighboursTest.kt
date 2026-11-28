package io.github.lcebot.ferryqr.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileNeighboursTest {
    private fun arrange(widths: List<Int>, columns: Int?): TileArrangement {
        val keys = widths.indices.map { "t$it" }
        return arrangeTiles(keys, { widths[it.drop(1).toInt()] }, 330, 10, columns)
    }

    /** Ten tiles in a grid of four columns, and tiles of mixed widths flowing in rows, with spans across several columns. */
    private val arrangements = listOf(
        arrange(List(10) { 60 }, columns = 4),
        arrange(listOf(60, 150, 60, 230, 60, 60, 60, 100, 160, 60, 60), columns = 4),
        arrange(listOf(90, 120, 60, 140, 80, 70, 200, 60, 110, 90), columns = null),
    )

    /**
     * How far each tile of [row] leans when the finger moves toward the end and toward the start, in
     * the order of the row. The pulled tile counts as what the swipe shows in its place, which leans
     * as a tile beside it does, and a tile that takes no part leans by nothing.
     */
    private fun leans(arrangement: TileArrangement, pulled: String, row: Int, rowsShown: Int): List<Pair<Float, Float>> {
        val relations = tileNeighbours(pulled, arrangement, rowsShown)
        return arrangement.inRow(row).map { tile ->
            if (tile.key == pulled) {
                NEAR_TILE_SHARE to NEAR_TILE_SHARE
            } else {
                val relation = relations[tile.key]
                (relation?.towardEnd ?: 0f) to (relation?.towardStart ?: 0f)
            }
        }
    }

    @Test
    fun leaningNeverNarrowsAGapAlongARow() {
        for (arrangement in arrangements) {
            for (pulled in arrangement.spots) {
                for (row in 0 until arrangement.rows) {
                    val leans = leans(arrangement, pulled.key, row, arrangement.rows)
                    val where = "pulling ${pulled.key}, row $row"
                    // Toward the end, each tile leans at least as far as the one before it, and toward
                    // the start, at least as far as the one after it.
                    for ((before, after) in leans.zipWithNext()) {
                        assertTrue(after.first >= before.first, "toward the end, $where: $leans")
                        assertTrue(before.second >= after.second, "toward the start, $where: $leans")
                    }
                }
            }
        }
    }

    @Test
    fun theTilesBesideThePulledOneLeanWithItInBothDirections() {
        // The second row of the grid holds t4 to t7.
        val grid = arrangements[0]
        val relations = tileNeighbours("t6", grid, grid.rows)
        assertEquals(NEAR_TILE_SHARE, relations.getValue("t7").towardEnd, 1e-6f)
        assertEquals(NEAR_TILE_SHARE, relations.getValue("t5").towardStart, 1e-6f)
        // Ahead of the pulled tile in the way the finger goes, a tile two steps away moves with it;
        // behind it, the same tile falls away.
        assertEquals(NEAR_TILE_SHARE, relations.getValue("t4").towardStart, 1e-6f)
        assertTrue(relations.getValue("t4").towardEnd < NEAR_TILE_SHARE)
        assertFalse("t6" in relations)
    }

    @Test
    fun onlyTheRowsShownTakePart() {
        val grid = arrangements[0]
        val relations = tileNeighbours("t1", grid, rowsShown = 2)
        assertTrue(relations.keys.none { grid.byKey.getValue(it).row >= 2 })
        assertTrue("t5" in relations)
    }
}
