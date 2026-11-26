package io.github.lcebot.ferryqr.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TileArrangementTest {

    @Test
    fun rowsBreakWhereTheyComeOutMostAlike() {
        // Packed in turn, 50 and 40 would fill one row and leave 20 alone in the next, stretched five
        // times over; breaking after 50 instead stretches the two full rows far less.
        val rows = balancedRows(intArrayOf(50, 40, 20, 90, 10), 100, 0)
        assertEquals(listOf(0..0, 1..2, 3..4), rows)
    }
}
