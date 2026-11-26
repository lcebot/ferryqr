package io.github.lcebot.ferryqr.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rows 100 wide and tiles 10 tall with gaps of 2, which keeps the arithmetic easy to follow. */
class TileArrangementTest {

    private val widths = mapOf("a" to 30, "b" to 40, "c" to 50, "d" to 20)

    private fun compact(keys: List<String>) = arrangeTiles(keys, { widths.getValue(it) }, 100, 2, null)

    private fun grid(keys: List<String>) = arrangeTiles(keys, { widths.getValue(it) }, 100, 2, 4)

    private val even = RowHeights(intArrayOf(10, 10), 2)

    @Test
    fun compactRowsFillTheWidth() {
        val tiles = compact(listOf("a", "b", "c", "d"))
        // 30 and 40 fit with a gap; 50 more would not, so it starts the next row with 20.
        assertEquals(2, tiles.rows)
        val first = tiles.spots.filter { it.row == 0 }
        assertEquals(listOf("a", "b"), first.map { it.key })
        assertEquals(100, first.last().x + first.last().width)
        assertEquals(first[0].x + first[0].width + 2, first[1].x)
        val second = tiles.spots.filter { it.row == 1 }
        assertEquals(100, second.last().x + second.last().width)
        // Each tile keeps at least its own width.
        tiles.spots.forEach { assertTrue(it.width >= widths.getValue(it.key)) }
    }

    @Test
    fun aTileWiderThanTheRowTakesTheRow() {
        val tiles = arrangeTiles(listOf("x", "a"), { if (it == "x") 500 else 30 }, 100, 2, null)
        assertEquals(2, tiles.rows)
        assertEquals(100, tiles.byKey.getValue("x").width)
    }

    @Test
    fun gridTilesSpanWholeColumns() {
        // Columns are 23.5 wide with a pitch of 25.5: 30 needs two, 40 two, 50 three, 20 one.
        val tiles = grid(listOf("a", "b", "c", "d"))
        val a = tiles.byKey.getValue("a")
        val b = tiles.byKey.getValue("b")
        val c = tiles.byKey.getValue("c")
        val d = tiles.byKey.getValue("d")
        assertEquals(0, a.row)
        assertEquals(0, b.row)
        assertEquals(1, c.row)
        assertEquals(1, d.row)
        assertEquals(100, b.x + b.width)
        assertEquals(b.x, a.x + a.width + 2)
        // The second row ends at the last column, so both rows end together.
        assertEquals(100, d.x + d.width)
    }

    @Test
    fun cornersRoundTheOutlineOnly() {
        val tiles = grid(listOf("c", "a"))
        // "c" spans three columns and "a" two, so "a" wraps and its row ends short of the one above.
        val c = tiles.byKey.getValue("c")
        val a = tiles.byKey.getValue("a")
        val top = tiles.cornersOf(c, tiles.rows)
        assertTrue(top.topStart && top.topEnd && !top.bottomStart)
        // The row below is shorter, so the end of this one is a corner of the outline.
        assertTrue(top.bottomEnd)
        val bottom = tiles.cornersOf(a, tiles.rows)
        assertTrue(bottom.bottomStart && bottom.bottomEnd)
        // The longer row above runs on past it, so its top end is inside the outline.
        assertFalse(bottom.topEnd)
    }

    @Test
    fun aHeldTileGoesBeforeOrAfterTheTileItIsOver() {
        val keys = listOf("a", "b", "c", "d")
        val tiles = compact(keys)
        val b = tiles.byKey.getValue("b")
        // Held over the start half of "b", "d" goes before it; over its end half, after it.
        assertEquals(1, tiles.targetFor("d", keys, Offset(b.x + 2f, 5f), tiles.rows, even))
        assertEquals(2, tiles.targetFor("d", keys, Offset(b.x + b.width - 2f, 5f), tiles.rows, even))
        // Over its own place, in the second row, it stays.
        val d = tiles.byKey.getValue("d")
        assertNull(tiles.targetFor("d", keys, Offset(d.x + 2f, 17f), tiles.rows, even))
    }

    @Test
    fun aHeldTileStaysWithinTheRowsShown() {
        val keys = listOf("a", "b", "c", "d")
        val tiles = compact(keys)
        // With one row shown, a finger far below still reads as the first row, here past the end
        // of "b", so "a" follows it.
        assertEquals(1, tiles.targetFor("a", keys, Offset(99f, 500f), 1, even))
    }

    @Test
    fun rowsStackByTheirOwnHeights() {
        // A second row twice as tall, for a name that wrapped, pushes the third down by as much.
        val heights = RowHeights(intArrayOf(10, 20, 10), 2)
        assertEquals(listOf(0, 12, 34), (0..2).map { heights.top(it) })
        assertEquals(44, heights.total(3))
        assertEquals(1, heights.rowAt(33f, 3))
        assertEquals(2, heights.rowAt(34f, 3))
        // A row the measurement has not reached yet takes the tallest.
        assertEquals(20, heights.height(3))
        assertEquals(46, heights.top(3))
    }
}
