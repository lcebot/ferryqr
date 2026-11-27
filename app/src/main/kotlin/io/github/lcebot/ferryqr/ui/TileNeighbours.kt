package io.github.lcebot.ferryqr.ui

/**
 * The share of the finger's travel the tiles beside a pulled tile lean by: a little more than the
 * rows next to a pulled row, so that the tiles three steps away still visibly move.
 */
internal const val NEAR_TILE_SHARE = 0.21f

/** How much more weakly a tile is pulled across an edge above or below it than across one beside it. */
private const val VERTICAL_SHARE = 0.6f

/** How much more weakly a tile one step further from a pulled tile leans than the nearer one. */
private const val NEIGHBOUR_DECAY = 0.5f

/** How many steps from a pulled tile the tiles around it lean. */
private const val NEIGHBOUR_STEPS = 3

/**
 * How the tiles stand to the pulled tile [pulled], a notification's swipe carried over to two
 * dimensions. Tiles that share an edge are neighbours: those beside each other in a row, and those
 * in rows next to each other that overlap. The tiles next to the pulled one are a step from it,
 * their neighbours two, and so on to [NEIGHBOUR_STEPS]. A tile leans by [NEAR_TILE_SHARE], less by
 * [VERTICAL_SHARE] for each edge above or below crossed on the way to it, and by [NEIGHBOUR_DECAY]
 * for each step past the first, along whichever way pulls it most. What the swipe shows behind the
 * pulled tile leans as a tile beside it does.
 *
 * Tiles in a row lean the same way, so a tile ahead of another in the way the finger goes, leaning
 * less, would close the gap between them and run into it. Along each row in the way the finger
 * goes, a tile therefore leans at least as far as the one behind it: the tiles behind the nearest
 * fall away from it, and those ahead of it move with it, so the gaps in a row only ever widen.
 *
 * Only the corners of the tiles next to the pulled one that touch it round, as a notification's
 * do where it meets the one pulled, and they stay rounded once it is loose. Only the first
 * [rowsShown] rows take part.
 */
internal fun tileNeighbours(pulled: String, arrangement: TileArrangement, rowsShown: Int): Map<String, Relation> {
    val origin = arrangement.byKey[pulled] ?: return emptyMap()
    val rows = arrangement.spots.filter { it.row < rowsShown }.groupBy { it.row }
    val edges = HashMap<String, MutableList<Pair<TileSpot, Float>>>()
    fun link(a: TileSpot, b: TileSpot, pull: Float) {
        edges.getOrPut(a.key) { mutableListOf() } += b to pull
        edges.getOrPut(b.key) { mutableListOf() } += a to pull
    }
    rows.forEach { (row, tiles) ->
        for ((a, b) in tiles.zipWithNext()) link(a, b, 1f)
        rows[row + 1]?.forEach { below ->
            tiles.forEach { above ->
                if (above.x < below.x + below.width && below.x < above.x + above.width) link(above, below, VERTICAL_SHARE)
            }
        }
    }
    val strength = HashMap<String, Float>()
    val steps = HashMap<String, Int>()
    var reached = mapOf(pulled to 1f)
    for (step in 1..NEIGHBOUR_STEPS) {
        val next = HashMap<String, Float>()
        reached.forEach { (from, pull) ->
            edges[from]?.forEach { (tile, across) ->
                if (tile.key == pulled) return@forEach
                val value = pull * across * if (step == 1) 1f else NEIGHBOUR_DECAY
                steps.putIfAbsent(tile.key, step)
                if (value > (strength[tile.key] ?: 0f)) {
                    strength[tile.key] = value
                    next[tile.key] = value
                }
            }
        }
        reached = next
    }
    // What the swipe shows in the pulled tile's place counts in its row as a tile beside it.
    fun share(tile: TileSpot) = if (tile.key == pulled) NEAR_TILE_SHARE else NEAR_TILE_SHARE * (strength[tile.key] ?: 0f)
    val towardEnd = HashMap<String, Float>()
    val towardStart = HashMap<String, Float>()
    rows.values.forEach { tiles ->
        var most = 0f
        tiles.forEach { tile ->
            most = maxOf(most, share(tile))
            towardEnd[tile.key] = most
        }
        most = 0f
        tiles.asReversed().forEach { tile ->
            most = maxOf(most, share(tile))
            towardStart[tile.key] = most
        }
    }
    val slack = arrangement.gap
    val keys = (towardEnd.keys + towardStart.keys).filter { it != pulled && ((towardEnd[it] ?: 0f) > 0f || (towardStart[it] ?: 0f) > 0f) }
    return keys.associateWith { key ->
        val tile = arrangement.byKey.getValue(key)
        val touching = if (steps[key] == 1) {
            val end = tile.x + tile.width
            fun along(x: Int) = x >= origin.x - slack && x <= origin.x + origin.width + slack
            when {
                tile.row == origin.row -> if (tile.x < origin.x) Corners.END else Corners.START
                tile.row < origin.row ->
                    (if (along(tile.x)) Corners.BOTTOM_START else 0) or (if (along(end)) Corners.BOTTOM_END else 0)
                else -> (if (along(tile.x)) Corners.TOP_START else 0) or (if (along(end)) Corners.TOP_END else 0)
            }
        } else {
            0
        }
        Relation(towardEnd[key] ?: 0f, towardStart[key] ?: 0f, NEIGHBOUR_ROUNDNESS[0], touching, touching)
    }
}
