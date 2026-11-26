package io.github.lcebot.ferryqr.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a tile goes, with [x] measured from the start edge of the favorites, so the same
 * arrangement mirrors for right-to-left text. [first] and [last] mark the ends of its row. How far
 * down its row is depends on how tall a tile comes out, which only laying one out tells.
 */
internal class TileSpot(
    val key: String,
    val x: Int,
    val width: Int,
    val row: Int,
    val first: Boolean,
    val last: Boolean,
)

/** What a finger over the tiles is over, and where among the other tiles the held one goes for it. */
internal class Hover(val over: String, val at: Int)

internal class TileArrangement(
    val spots: List<TileSpot>,
    val rows: Int,
    private val rowEnds: IntArray,
    val gap: Int,
) {
    val byKey: Map<String, TileSpot> = spots.associateBy { it.key }

    fun inRow(row: Int): List<TileSpot> = spots.filter { it.row == row }

    fun rectOf(spot: TileSpot, heights: RowHeights): Rect {
        val top = heights.top(spot.row).toFloat()
        return Rect(spot.x.toFloat(), top, (spot.x + spot.width).toFloat(), top + heights.height(spot.row))
    }

    /**
     * The favorites read as one block, rounded at the corners of its outline and tight between its
     * tiles, as a segmented list is between its rows. A row shorter than the one above or below
     * leaves the end of the longer one as a corner of the outline too.
     */
    fun cornersOf(spot: TileSpot, rowsShown: Int): TileCorners {
        val lastRow = rowsShown - 1
        val end = spot.x + spot.width
        fun shorter(row: Int) = row < 0 || row > lastRow || row >= rowEnds.size || rowEnds[row] < end - 1
        return TileCorners(
            topStart = spot.first && spot.row == 0,
            topEnd = spot.last && shorter(spot.row - 1),
            bottomStart = spot.first && spot.row == lastRow,
            bottomEnd = spot.last && shorter(spot.row + 1),
        )
    }

    /**
     * What the finger at [finger] is over among the first [rowsShown] rows while the tile [key] is
     * held, and where among the other tiles the held one goes for it, or null over its own place. A
     * tile held over another takes that tile's place, as an icon does on a launcher's home screen,
     * and the tiles between move aside toward the place it left; held past the end of a row, it
     * follows the row's last tile. Each tile counts the half of the gaps beside it as its own.
     */
    fun hover(key: String, keys: List<String>, finger: Offset, rowsShown: Int, heights: RowHeights): Hover? {
        val shown = minOf(rows, rowsShown)
        if (shown <= 0 || key !in keys) return null
        val row = heights.rowAt(finger.y, shown)
        val inRow = inRow(row)
        if (inRow.isEmpty()) return null
        val over = inRow.firstOrNull { finger.x < it.x + it.width + gap / 2f }
        val others = keys.filter { it != key }
        return when {
            over == null -> inRow.last().takeIf { it.key != key }?.let { Hover("end:$row", others.indexOf(it.key) + 1) }
            over.key == key -> null
            else -> Hover(over.key, keys.indexOf(over.key))
        }
    }
}

/**
 * How tall each row of tiles is, as the tiles in it came out, and how far down each starts with
 * [gap] between them. A row not yet measured, as one a drag has just opened, takes the tallest.
 */
internal class RowHeights(private val heights: IntArray, private val gap: Int) {
    private val fallback = heights.maxOrNull() ?: 0
    private val tops = IntArray(heights.size).also { tops ->
        for (row in 1 until heights.size) tops[row] = tops[row - 1] + heights[row - 1] + gap
    }

    fun height(row: Int): Int = heights.getOrElse(row) { fallback }

    fun top(row: Int): Int = when {
        row <= 0 -> 0
        row < tops.size -> tops[row]
        tops.isEmpty() -> row * (fallback + gap)
        else -> tops.last() + heights.last() + gap + (row - tops.size) * (fallback + gap)
    }

    /** How tall the first [rows] rows stand together. */
    fun total(rows: Int): Int = if (rows <= 0) 0 else top(rows - 1) + height(rows - 1)

    /** The row among the first [rows] that [y] falls in, the gap below a row counting as its own. */
    fun rowAt(y: Float, rows: Int): Int {
        for (row in 0 until rows - 1) if (y < top(row + 1)) return row
        return (rows - 1).coerceAtLeast(0)
    }
}

internal class TileCorners(val topStart: Boolean, val topEnd: Boolean, val bottomStart: Boolean, val bottomEnd: Boolean)

/**
 * Lays out tiles of the widths [widthOf] gives, in rows [available] wide.
 *
 * Without [columns], the tiles take as few rows as packing each row as full as it goes needs, but
 * break between rows where the rows come out most alike, as justified text chooses its line breaks
 * rather than filling each line in turn. Each row but the last then widens its tiles in proportion
 * to their widths until it is full, so all of a row's tiles look stretched alike. The last row is
 * filled the same way unless that would stretch its tiles more than [LAST_ROW_FILL] does, or more
 * than the most stretched row above if that is more; else it widens its tiles as much as the tiles
 * above are widened on average and ends short, so they look like the rest rather than either
 * stretched or narrow beside them.
 *
 * With [columns], each tile spans as many columns as its width needs, so tiles line up in columns,
 * and a row ends where the next tile no longer fits.
 */
internal fun arrangeTiles(
    keys: List<String>,
    widthOf: (String) -> Int,
    available: Int,
    gap: Int,
    columns: Int?,
): TileArrangement {
    if (available <= 0 || keys.isEmpty()) return TileArrangement(emptyList(), 0, IntArray(0), gap)
    val spots = ArrayList<TileSpot>(keys.size)
    val rowEnds = ArrayList<Int>()
    fun addRow(row: List<Pair<String, IntRange>>) {
        val index = rowEnds.size
        row.forEachIndexed { i, (key, span) ->
            spots += TileSpot(key, span.first, span.last - span.first, index, i == 0, i == row.lastIndex)
        }
        rowEnds += row.last().second.last
    }
    var row = mutableListOf<Pair<String, IntRange>>()
    if (columns == null) {
        val widths = IntArray(keys.size) { widthOf(keys[it]).coerceIn(1, available) }
        val rows = balancedRows(widths, available, gap)
        fun room(tiles: IntRange) = available - gap * (tiles.last - tiles.first)
        fun fill(tiles: IntRange) = room(tiles).toFloat() / tiles.sumOf { widths[it] }
        val full = rows.dropLast(1)
        val most = full.maxOfOrNull(::fill) ?: 1f
        val above = full.sumOf { it.count() }
        val mean = if (above == 0) 1f else full.sumOf { fill(it).toDouble() * it.count() }.toFloat() / above
        rows.forEachIndexed { index, tiles ->
            val filled = fill(tiles)
            val stretch = when {
                index < rows.lastIndex -> filled
                filled <= maxOf(most, LAST_ROW_FILL) -> filled
                else -> minOf(mean, filled)
            }
            // Placed by the running sum of the widths, so the rounding never adds up and a filled
            // row ends exactly at its edge.
            var before = 0
            addRow(
                tiles.mapIndexed { i, tile ->
                    val start = (before * stretch).roundToInt() + i * gap
                    before += widths[tile]
                    val end = if (stretch == filled && i == tiles.count() - 1) available else (before * stretch).roundToInt() + i * gap
                    keys[tile] to (start..end)
                },
            )
        }
    } else {
        val pitch = (available + gap).toFloat() / columns
        fun edge(column: Int) = (column * pitch).roundToInt()
        var column = 0
        keys.forEach { key ->
            val span = ceil((widthOf(key) + gap) / pitch).toInt().coerceIn(1, columns)
            if (column + span > columns) {
                addRow(row)
                row = mutableListOf()
                column = 0
            }
            row += key to (edge(column)..edge(column + span) - gap)
            column += span
        }
        if (row.isNotEmpty()) addRow(row)
    }
    return TileArrangement(spots, rowEnds.size, rowEnds.toIntArray(), gap)
}

/**
 * How much the last row of tiles may be stretched to fill it, as a factor of the tiles' own widths,
 * when the rows above are stretched less: by half again, which a last row of a few tiles of middling
 * width comes to, while a lone short tile would be stretched far more and is left short instead.
 */
internal const val LAST_ROW_FILL = 1.5f

/**
 * Where tiles of [widths] break into rows [available] wide with [gap] between them, as index ranges.
 * They take as many rows as packing each row in turn needs, the fewest there can be; of the ways to
 * break into that many, the one chosen stretches the rows but the last most alike, by the sum of the
 * squares of how much each would be stretched to fill it, as justified text weighs its line breaks.
 * The last row costs nothing, as it need not be filled.
 */
internal fun balancedRows(widths: IntArray, available: Int, gap: Int): List<IntRange> {
    val n = widths.size
    if (n == 0) return emptyList()
    val sums = LongArray(n + 1)
    for (i in 0 until n) sums[i + 1] = sums[i] + widths[i]
    // Whether the tiles from [from] until [until] fit in a row.
    fun fits(from: Int, until: Int) = until > from && sums[until] - sums[from] + gap.toLong() * (until - from - 1) <= available
    var count = 0
    var at = 0
    while (at < n) {
        var end = at + 1
        while (end < n && fits(at, end + 1)) end++
        count++
        at = end
    }
    if (count == 1) return listOf(0 until n)
    fun cost(from: Int, until: Int): Double {
        val stretch = (available - gap * (until - from - 1)).toDouble() / (sums[until] - sums[from])
        return (stretch - 1) * (stretch - 1)
    }
    // The least cost of the first tiles in the rows so far, and where the last of those rows began.
    var least = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }.also { it[0] = 0.0 }
    val began = Array(count) { IntArray(n + 1) { -1 } }
    for (row in 1 until count) {
        val next = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        for (until in 1..n) {
            for (from in 0 until until) {
                if (least[from] == Double.POSITIVE_INFINITY || !fits(from, until)) continue
                val total = least[from] + cost(from, until)
                if (total < next[until]) {
                    next[until] = total
                    began[row][until] = from
                }
            }
        }
        least = next
    }
    var last = -1
    for (from in 0 until n) {
        if (fits(from, n) && least[from] != Double.POSITIVE_INFINITY && (last < 0 || least[from] < least[last])) last = from
    }
    val rows = ArrayList<IntRange>(count)
    rows += last until n
    var end = last
    for (row in count - 1 downTo 1) {
        val start = began[row][end]
        rows += start until end
        end = start
    }
    return rows.asReversed()
}

/** How many grid columns fit across [available], with at least two. */
internal fun gridColumns(available: Int, gap: Int, column: Int): Int = max(2, (available + gap) / (column + gap))
