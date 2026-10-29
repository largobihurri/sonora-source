package io.sonora.feature.settings.duplicates

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The copies' columns in the duplicates comparison, beside its fixed column of labels: how wide each is, and where a
 * sideways scroll of them settles. Pure, in any one unit (pixels or dp).
 */
internal object ComparisonColumns {

    /**
     * Each copy's column width in [available] room (beside the labels) for [copies] copies. Copies that fit at [min]
     * or wider share the room, up to [max] each, so two copies sit side by side on a phone; [max] limits only this
     * sharing. More copies split the room into as many whole columns as fit, each between [min] and twice [min] and
     * together filling it exactly, so a scroll that settles on a column edge ([snapDelta]) shows every copy on screen
     * whole, with no strip of the next one. A room narrower than [min] is one column.
     */
    fun width(available: Float, copies: Int, min: Float, max: Float): Float {
        if (copies <= 0 || available <= 0f) return max
        val shared = available / copies
        if (shared >= min) return shared.coerceAtMost(max)
        val whole = floor(available / min).toInt().coerceAtLeast(1)
        return available / whole
    }

    /**
     * How far a scroll at [scroll] (of at most [maxScroll]) still moves to settle, given [column] wide columns and the
     * release [velocity] (positive toward the later copies): to the next column edge in a fling's direction, else to
     * the nearest one, never past either end.
     */
    fun snapDelta(scroll: Float, column: Float, maxScroll: Float, velocity: Float): Float {
        if (column <= 0f) return 0f
        val columns = scroll / column
        val index = when {
            velocity > 0f -> ceil(columns)
            velocity < 0f -> floor(columns)
            else -> columns.roundToInt().toFloat()
        }
        return (index * column).coerceIn(0f, maxOf(0f, maxScroll)) - scroll
    }
}
