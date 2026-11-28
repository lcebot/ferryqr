package io.github.lcebot.ferryqr.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The entries swiped away and deleted, by content, from when the swipe acts until the undo session
 * that deleted them lets its deletions go. Undoing names those it brings back, and each takes up its
 * swipe again, whether it is composed again or was still composed as it faded out, and flies back
 * into its place from where it went, in [scope], the list's. One brought back stays until it lands,
 * or else until a later session lets its deletions go, as one brought back out of view never comes
 * into view to fly back.
 */
@Stable
internal class SwipeDepartures(private val scope: CoroutineScope) {
    private val departed = mutableStateMapOf<String, Departure>()

    /** Entries brought back and ready to fly back, with their turns, gathered before any of them starts. */
    private val ready = ArrayList<Pair<Int, EntrySwipe>>()

    /**
     * Keeps [swipe], which has swiped the entry with content [key] away to delete it in the undo
     * session [session], while undoing can bring the entry back.
     */
    fun depart(key: String, swipe: EntrySwipe, session: Long) {
        departed[key] = Departure(swipe, session)
    }

    /** Whether the entry with content [key] was swiped away and deleted, and undoing can still bring it back or is bringing it back. */
    fun isDeparted(key: String): Boolean = key in departed

    /** The swipe the entry with content [key] flies back with, from when undoing brings it back until it lands. */
    fun returning(key: String): EntrySwipe? = departed[key]?.takeIf { it.turn != null }?.swipe

    /** Takes the contents of the entries an undo brings back, in the order they were deleted, earliest first. */
    fun undone(contents: List<String>) {
        contents.forEachIndexed { turn, content -> departed[content]?.turn = turn }
    }

    /**
     * The undo session [session] has let its deletions go for good: the entries swiped away in it are
     * let go, and so are those an earlier undo brought back that have not flown back since. Sessions
     * end in turn, so all that is left of an earlier one is the entries its undo brought back.
     */
    fun sessionEnded(session: Long) {
        departed.filterValues { it.session <= session }.keys.forEach { departed.remove(it) }
    }

    /** [swipe] has brought the entry with content [key] back into its place, or a finger caught it on its way. */
    fun landed(key: String, swipe: EntrySwipe) {
        if (departed[key]?.swipe === swipe) departed.remove(key)
    }

    /**
     * Flies [swipe], of the entry with content [key], back into its place in its turn. Every entry
     * an undo brings back rejoins the list in the same frame, their gaps opening together, so those
     * ready are gathered until the next frame. They then fly back in the order they were deleted,
     * the first at once, so its return overlaps the gaps opening, and each later one a little after
     * the one before, as [returnDelay] spaces them. An entry already on its way is left to it.
     */
    fun returnInTurn(key: String, swipe: EntrySwipe) {
        if (swipe.returning) return
        swipe.returning = true
        ready += (departed[key]?.turn ?: Int.MAX_VALUE) to swipe
        if (ready.size > 1) return
        scope.launch {
            withFrameNanos {}
            val inTurn = ready.sortedBy { (turn, _) -> turn }
            ready.clear()
            inTurn.forEachIndexed { turn, (_, back) ->
                launch {
                    delay(returnDelay(turn))
                    back.comeBack()
                }
            }
        }
    }
}

/**
 * An entry swiped away and deleted: its [swipe], the undo [session] that deleted it and, if undone,
 * brought it back, and once undoing brings it back, its [turn] among those brought back with it.
 */
private class Departure(val swipe: EntrySwipe, val session: Long) {
    var turn by mutableStateOf<Int?>(null)
}

/**
 * How long after the first of the entries one undo brings back the second starts flying back: long
 * enough to read as following it, well short of the return itself.
 */
private const val RETURN_STAGGER_MS = 50f

/**
 * Each later wait between two returns lasts this share of the wait before it. The waits shrink, so
 * a long run of returns closes up rather than trailing behind, and however many there are, the
 * last starts within RETURN_STAGGER_MS / (1 - RETURN_STAGGER_DECAY) of the first, a quarter of a
 * second, while the first ones are still on their way, so the returns read as one.
 */
private const val RETURN_STAGGER_DECAY = 0.8f

/** How long after the first the return in [turn] starts, counting from 0 for the first, which starts at once. */
private fun returnDelay(turn: Int): Long =
    (RETURN_STAGGER_MS * (1f - RETURN_STAGGER_DECAY.pow(turn)) / (1f - RETURN_STAGGER_DECAY)).roundToLong()
