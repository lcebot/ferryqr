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
    fun compactRowsFillTheWidthButTheLast() {
        val tiles = compact(listOf("a", "b", "c", "d"))
        // 30 and 40 fit with a gap; 50 more would not, so it starts the next row with 20.
        assertEquals(2, tiles.rows)
        assertEquals(listOf("a", "b"), tiles.inRow(0).map { it.key })
        // The first row shares its 28 left over, 14 each; the last keeps its tiles' own widths.
        assertEquals(listOf(0 to 44, 46 to 54, 0 to 50, 52 to 20), tiles.spots.map { it.x to it.width })
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
    fun aHeldTileTakesThePlaceOfTheTileItIsOver() {
        val keys = listOf("a", "b", "c", "d")
        val tiles = compact(keys)
        // Anywhere over "b", "d" takes its place, and "b" moves aside toward where "d" was.
        listOf(50f, 95f).forEach { x ->
            val hover = tiles.hover("d", keys, Offset(x, 5f), tiles.rows, even)!!
            assertEquals("b", hover.over)
            assertEquals(1, hover.at)
        }
        // Over "c", "a" takes its place, and "b" and "c" move up toward where "a" was.
        assertEquals(2, tiles.hover("a", keys, Offset(10f, 17f), tiles.rows, even)!!.at)
        // Over its own place, it stays.
        assertNull(tiles.hover("d", keys, Offset(60f, 17f), tiles.rows, even))
    }

    @Test
    fun aHeldTilePastTheEndOfARowFollowsItsLast() {
        val keys = listOf("a", "b", "c", "d")
        val tiles = compact(keys)
        val hover = tiles.hover("a", keys, Offset(90f, 17f), tiles.rows, even)!!
        assertEquals("end:1", hover.over)
        assertEquals(3, hover.at)
        // With one row shown, a finger far below still reads as the first row.
        assertEquals(1, tiles.hover("a", keys, Offset(99f, 500f), 1, even)!!.at)
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

    @Test
    fun neighboursFollowSharedEdges() {
        // "a" and "b" share the first row; below, "c" overlaps "a" and "d" overlaps "b".
        val tiles = grid(listOf("a", "b", "c", "d"))
        val near = tileNeighbours("a", tiles, tiles.rows)
        assertEquals(setOf("b", "c", "d"), near.keys)
        // Beside it leans most, below it less, and "d", two steps away either way, less again.
        assertEquals(0.21f, near.getValue("b").share, 0.0001f)
        assertEquals(0.21f * 0.6f, near.getValue("c").share, 0.0001f)
        assertEquals(0.21f * 0.6f * 0.5f, near.getValue("d").share, 0.0001f)
        // Each rounds on the sides facing "a", and only the nearest stay rounded once it is loose.
        assertEquals(Sides.START, near.getValue("b").sides)
        assertEquals(Sides.TOP, near.getValue("c").sides)
        assertEquals(Sides.TOP or Sides.START, near.getValue("d").sides)
        assertEquals(0, near.getValue("d").looseSides)
        // Folded to its first row, only "b" takes part.
        assertEquals(setOf("b"), tileNeighbours("a", tiles, 1).keys)
    }
}
