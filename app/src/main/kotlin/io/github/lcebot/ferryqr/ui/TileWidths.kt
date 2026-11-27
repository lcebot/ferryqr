package io.github.lcebot.ferryqr.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.lcebot.ferryqr.dispatch.ContentNames
import kotlin.math.max

/**
 * How wide each favorite's tile is, by its content: the longer of its name and its kind, the two
 * lines it shows, with what the list item adds beside them. Each tile is measured once for what it
 * shows, as [favorites] give it, so a favorite moved, renamed or added measures only itself again.
 */
@Composable
internal fun rememberTileWidths(favorites: List<ContentNames>, chrome: Int): Map<String, Int> {
    val context = LocalContext.current
    val typography = MaterialTheme.typography
    val measurer = rememberTextMeasurer()
    // The process keeps an entry's names for as long as its content, name and language stay as they
    // are, so names kept are names still measured right.
    val measured = remember(typography, measurer, chrome) { HashMap<ContentNames, Int>() }
    return remember(favorites, measured) {
        val widths = favorites.associate { shown ->
            shown.content.text to measured.getOrPut(shown) {
                val name = measurer.measure(shown.tileTitle, typography.bodyLarge, maxLines = 1, softWrap = false).size.width
                val kind = measurer.measure(context.getString(shown.kind.label), typography.bodyMedium, maxLines = 1, softWrap = false)
                    .size.width
                max(name, kind) + chrome
            }
        }
        // Only what is still shown stays measured.
        measured.keys.retainAll(favorites.toSet())
        widths
    }
}
