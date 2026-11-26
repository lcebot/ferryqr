package io.github.lcebot.ferryqr.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.lcebot.ferryqr.history.HistoryEntry
import kotlin.math.max

/**
 * How wide each favorite's tile is: the longer of its name and its kind, the two lines it shows,
 * with what the list item adds beside them. Each tile is measured once for what it shows, so a
 * favorite moved, renamed or added measures only itself again.
 */
@Composable
internal fun rememberTileWidths(favorites: List<HistoryEntry>, chrome: Int): Map<String, Int> {
    val context = LocalContext.current
    val typography = MaterialTheme.typography
    val measurer = rememberTextMeasurer()
    val measured = remember(typography, measurer, chrome) { HashMap<Triple<String, String, String?>, Int>() }
    return remember(favorites, measured) {
        val widths = favorites.associate { entry ->
            entry.text to measured.getOrPut(Triple(entry.text, entry.format, entry.label)) {
                val shown = presentationOf(context, entry)
                val name = measurer.measure(shown.tileTitle, typography.bodyLarge, maxLines = 1, softWrap = false).size.width
                val kind = measurer.measure(context.getString(shown.kind.label), typography.bodyMedium, maxLines = 1, softWrap = false)
                    .size.width
                max(name, kind) + chrome
            }
        }
        // Only what is still shown stays measured.
        val kept = favorites.mapTo(HashSet()) { Triple(it.text, it.format, it.label) }
        measured.keys.retainAll(kept)
        widths
    }
}
