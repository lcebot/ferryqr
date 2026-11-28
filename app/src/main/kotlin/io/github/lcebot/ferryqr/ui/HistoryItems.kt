package io.github.lcebot.ferryqr.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshots.Snapshot
import io.github.lcebot.ferryqr.history.HistoryEntry
import java.time.LocalDate

/** The group the favorites form for a swipe; the other rows group by day. */
internal const val FAVORITES_GROUP = "favorites"

/** The list item holding the favorites' title. */
internal const val FAVORITES_HEADER_KEY = "header:favorites"

/** The list item holding the favorites as tiles. */
internal const val TILES_KEY = "favorites:tiles"

/** The content type every row shares. */
private const val ROW_TYPE = "row"

/**
 * One item of the history list, under the [key] the list knows it by and of a [contentType] that
 * only items composed alike share. The list emits every item from one place, so a row that moves
 * between the favorites and a day stays the same composition and changes its look on its own motion.
 */
internal sealed class HistoryItem(val key: Any, val contentType: Any) {
    data object FavoritesHeader : HistoryItem(FAVORITES_HEADER_KEY, "favorites-header")

    data object Tiles : HistoryItem(TILES_KEY, "favorites-tiles")

    /** The title of [day], over [entries], the entries scanned that day. */
    class DayHeader(val day: LocalDate, val entries: List<HistoryEntry>) : HistoryItem("header:$day", "day-header")

    /** The row of [entry], at [index] of the [count] rows of [group], [FAVORITES_GROUP] or a day. */
    class Row(key: String, val entry: HistoryEntry, val group: Any, val index: Int, val count: Int) : HistoryItem(key, ROW_TYPE) {
        val favorite: Boolean get() = group == FAVORITES_GROUP
    }
}

/**
 * The items of the history list, and the key each row goes by, for [listState], the list showing
 * them. A row keeps its key as it moves within its group. One that moves between the favorites and
 * a day keeps it too, and slides from its old place to its new one, when its old place was in view
 * as the move came and the list will lay its new place out in view. Otherwise it takes a key of its
 * own in its new place, so the move shows as the row fading out where it was and fading in where it
 * goes: the list would start a row sliding in from out of view at a spot by the edge of the view,
 * and hold one sliding out of view at such a spot, fixed to the view rather than among the rows,
 * until it settles. A key once changed stays with the row until its next move.
 */
internal class HistoryItems(private val listState: LazyListState) {
    /** The key each row listed goes by. */
    private var keys: Map<String, String> = emptyMap()

    /** The group each row listed belongs to. */
    private var groups: Map<String, Any> = emptyMap()

    /** How many times each row has taken a key of its own, for those that have. */
    private val generations = HashMap<String, Int>()

    /** The key of the row showing the entry with content [text]. */
    fun rowKey(text: String): Any = keys[text] ?: keyOf(text, generations[text] ?: 0)

    /**
     * The items for [section] and the other entries by [days]; the favorites show only when
     * [hasFavorites]. Built when one of these changes, never on a frame of its own, and only then
     * does it read where the rows were last laid out.
     */
    fun build(hasFavorites: Boolean, section: FavoritesSection, days: Map<LocalDate, List<HistoryEntry>>): List<HistoryItem> {
        val slots = ArrayList<Any>()
        if (hasFavorites) {
            slots += HistoryItem.FavoritesHeader
            if (section.tiled) {
                slots += HistoryItem.Tiles
            } else {
                section.rows.forEachIndexed { index, entry -> slots += Slot(entry, FAVORITES_GROUP, index, section.rows.size) }
            }
        }
        days.forEach { (day, entries) ->
            slots += HistoryItem.DayHeader(day, entries)
            entries.forEachIndexed { index, entry -> slots += Slot(entry, day, index, entries.size) }
        }
        val moving = slots.indices.filter { at ->
            val slot = slots[at] as? Slot ?: return@filter false
            val before = groups[slot.entry.text]
            before != null && before != slot.group
        }
        // Read once, where the rows were in the frame before the change, and without making the
        // page compose again as the list scrolls.
        val sliding = if (moving.isEmpty()) emptySet() else Snapshot.withoutReadObservation { sliding(slots, moving) }

        val nextKeys = HashMap<String, String>()
        val nextGroups = HashMap<String, Any>()
        val items = slots.mapIndexed { at, slot ->
            if (slot !is Slot) return@mapIndexed slot as HistoryItem
            val text = slot.entry.text
            val renewed = at in moving && at !in sliding
            if (renewed) generations[text] = (generations[text] ?: 0) + 1
            val key = keys[text]?.takeUnless { renewed } ?: keyOf(text, generations[text] ?: 0)
            nextKeys[text] = key
            nextGroups[text] = slot.group
            HistoryItem.Row(key, slot.entry, slot.group, slot.index, slot.count)
        }
        keys = nextKeys
        groups = nextGroups
        return items
    }

    /**
     * Which of the rows at [moving] among [slots] slide to their new places: those whose old place
     * was in view and whose new place the list will lay out in view. Where that is comes from how
     * the list lays its items out after a change, as the tiles foresee where the tile in hand lands.
     * The list keeps its first visible item where it was, found by its key, or by its index once its
     * key is gone, and lays the other items out from it, each as tall as it was laid out or, when it
     * was not, as tall as a laid out item of its content type. Where the items from the first one on
     * end inside the view, the list scrolls back by the room left, as far as the items before it
     * allow. Every moving row is foreseen in the same new list, so several moving at once place each
     * other.
     */
    private fun sliding(slots: List<Any>, moving: List<Int>): Set<Int> {
        val info = listState.layoutInfo
        val laidOut = info.visibleItemsInfo
        val byKey = laidOut.associateBy { it.key }
        val gap = info.mainAxisItemSpacing
        // The view is the part of the list inside its content padding, below the top bar and above
        // the navigation bar, and the list's offset 0 is its top.
        val viewEnd = info.viewportEndOffset - info.afterContentPadding
        fun inView(top: Int, size: Int) = top < viewEnd && top + size > 0
        fun keyBefore(slot: Any): Any? = when (slot) {
            is HistoryItem -> slot.key
            is Slot -> keys[slot.entry.text]
            else -> null
        }
        fun sizeOf(slot: Any): Int? {
            keyBefore(slot)?.let { byKey[it] }?.let { return it.size }
            val type = if (slot is HistoryItem) slot.contentType else ROW_TYPE
            return laidOut.firstOrNull { it.contentType == type }?.size
        }

        val firstIndex = listState.firstVisibleItemIndex
        val firstKey = laidOut.firstOrNull { it.index == firstIndex }?.key
        val keyed = if (firstKey == null) -1 else slots.indexOfFirst { keyBefore(it) == firstKey }
        // A moving row the list keeps its place by would carry the list along to the row's new
        // place, unless the row keeps its index. Anywhere else it takes a key of its own, so the list
        // keeps its place by the index instead.
        val anchorMoves = keyed in moving && keyed != firstIndex
        var anchor = if (keyed >= 0 && !anchorMoves) keyed else firstIndex
        var anchorTop = -listState.firstVisibleItemScrollOffset
        if (anchor > slots.lastIndex) {
            anchor = slots.lastIndex
            anchorTop = 0
        }

        var end = anchorTop
        var after = anchor
        while (after <= slots.lastIndex && end < viewEnd) {
            end += (sizeOf(slots[after]) ?: return emptySet()) + if (after < slots.lastIndex) gap else 0
            after++
        }
        if (end < viewEnd) {
            var room = -anchorTop
            var before = anchor - 1
            while (room < viewEnd - end && before >= 0) {
                room += (sizeOf(slots[before]) ?: return emptySet()) + gap
                before--
            }
            anchorTop += minOf(viewEnd - end, room)
        }

        // Where each item the list lays out in view starts, walking from the first one to both
        // edges of the view; the items past them are out of view.
        val tops = HashMap<Int, Int>()
        var top = anchorTop
        for (at in anchor..slots.lastIndex) {
            if (top >= viewEnd) break
            tops[at] = top
            top += (sizeOf(slots[at]) ?: return emptySet()) + gap
        }
        top = anchorTop
        for (at in anchor - 1 downTo 0) {
            if (top <= 0) break
            top -= (sizeOf(slots[at]) ?: return emptySet()) + gap
            tops[at] = top
        }

        return moving.filterTo(HashSet()) { at ->
            val text = (slots[at] as Slot).entry.text
            val was = keys[text]?.let { byKey[it] } ?: return@filterTo false
            val lands = tops[at] ?: return@filterTo false
            !(at == keyed && anchorMoves) && inView(was.offset, was.size) && inView(lands, was.size)
        }
    }

    /** A row before its key is known. */
    private class Slot(val entry: HistoryEntry, val group: Any, val index: Int, val count: Int)

    /**
     * The key of the row of [text] after it has taken a key of its own [generation] times. The
     * number goes before the colon, so no content can make one key look like another.
     */
    private fun keyOf(text: String, generation: Int): String =
        if (generation == 0) "entry:$text" else "entry$generation:$text"
}
