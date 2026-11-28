package io.github.lcebot.ferryqr.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileArrangementTest {

    @Test
    fun rowsBreakWhereTheyComeOutMostAlike() {
        // Packed in turn, 50 and 40 would fill one row and leave 20 alone in the next, stretched five
        // times over; breaking after 50 instead stretches the two full rows far less.
        val rows = balancedRows(intArrayOf(50, 40, 20, 90, 10), 100, 0)
        assertEquals(listOf(0..0, 1..2, 3..4), rows)
    }

    /** The tiles of [widths], named by their place, arranged without a grid. */
    private fun flowing(vararg widths: Int, available: Int = 330, gap: Int = 10, columns: Int? = null): TileArrangement {
        val keys = widths.indices.map { "t$it" }
        return arrangeTiles(keys, { widths[it.drop(1).toInt()] }, available, gap, columns)
    }

    /** Where each tile of [row] starts and how wide it is. */
    private fun TileArrangement.spans(row: Int): List<Pair<Int, Int>> = inRow(row).map { it.x to it.width }

    @Test
    fun aLastRowThatFillsWithinBoundsIsFilled() {
        // The last row's two tiles fill the row stretched by 320 / 220, within LAST_ROW_FILL, and
        // the gap between them is left as it is.
        val tiles = flowing(100, 100, 100, 120, 100)
        assertEquals(2, tiles.rows)
        assertEquals(listOf(0 to 103, 113 to 104, 227 to 103), tiles.spans(0))
        assertEquals(listOf(0 to 175, 185 to 145), tiles.spans(1))
    }

    @Test
    fun aLastRowThatWouldStretchTooFarEndsShort() {
        // Filled, the last row would stretch by 320 / 170, so it stretches as the row above does,
        // by 310 / 300, and ends short.
        val tiles = flowing(100, 100, 100, 100, 70)
        assertEquals(listOf(0 to 103, 113 to 73), tiles.spans(1))
        val end = tiles.inRow(1).last().let { it.x + it.width }
        assertTrue(end < 330)
    }

    @Test
    fun aLastRowStretchedNoMoreThanTheRowsAboveIsFilled() {
        // Past LAST_ROW_FILL, at 330 / 210, but less than the row above, at 330 / 200.
        val tiles = flowing(200, 210)
        assertEquals(listOf(0 to 330), tiles.spans(0))
        assertEquals(listOf(0 to 330), tiles.spans(1))
    }

    @Test
    fun gridTilesSpanTheColumnsTheirWidthsNeed() {
        // Four columns 75 wide with 10 between, so a column and the gap after it make 85.
        val tiles = flowing(60, 100, 200, 500, 75, columns = 4)
        assertEquals(4, tiles.rows)
        assertEquals(listOf(0 to 75, 85 to 160), tiles.spans(0))
        // Three columns do not fit after the three taken, so the tile starts a row.
        assertEquals(listOf(0 to 245), tiles.spans(1))
        // Wider than the row, a tile spans every column and ends at the edge.
        assertEquals(listOf(0 to 330), tiles.spans(2))
        // Exactly one column wide, a tile takes one column.
        assertEquals(listOf(0 to 75), tiles.spans(3))
        val first = tiles.byKey.getValue("t0")
        val second = tiles.byKey.getValue("t1")
        assertTrue(first.first && !first.last && !second.first && second.last)
    }

    @Test
    fun rowsPastThoseMeasuredFollowAtTheTallestHeight() {
        val heights = RowHeights(intArrayOf(50, 80), 10)
        assertEquals(0, heights.top(0))
        assertEquals(60, heights.top(1))
        assertEquals(150, heights.top(2))
        assertEquals(240, heights.top(3))
        assertEquals(80, heights.height(3))
        for (row in 0..5) assertEquals(heights.height(row) + 10, heights.top(row + 1) - heights.top(row), "row $row")
        assertEquals(230, heights.total(3))
        assertEquals(2, heights.rowAt(200f, 4))
        assertEquals(3, heights.rowAt(1000f, 4))
    }

    @Test
    fun rowsBeforeAnyAreMeasuredStackByTheGap() {
        val heights = RowHeights(IntArray(0), 10)
        assertEquals(0, heights.top(0))
        assertEquals(20, heights.top(2))
        assertEquals(0, heights.height(2))
    }
}
