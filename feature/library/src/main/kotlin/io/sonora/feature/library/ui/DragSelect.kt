package io.sonora.feature.library.ui

/** A row's vertical extent, [top] up to (not including) [bottom], in the list's coordinates. */
internal data class RowSpan(val key: String, val top: Float, val bottom: Float)

/** The row whose span holds [y], or `null` when [y] is over none (a header, a gap, past the ends). */
internal fun rowContaining(y: Float, rows: List<RowSpan>): String? = rows.firstOrNull { y >= it.top && y < it.bottom }?.key

/**
 * The row under a finger at [y]: the one holding it, else the one nearest to it, so a finger past the first or last
 * row, or over a section header between two, still has a row to extend the selection to.
 */
internal fun nearestRow(y: Float, rows: List<RowSpan>): String? =
    rowContaining(y, rows) ?: rows.minByOrNull { if (y < it.top) it.top - y else y - it.bottom }?.key

/** Whether a press at [x] in a list [width] wide is in the trailing [zone] that holds the selection circles. */
internal fun inCircleZone(x: Float, width: Float, zone: Float): Boolean = x >= width - zone

/**
 * How fast a list scrolls, in px per second, under a finger at [y] while it paints a selection: negative (up) within
 * [zone] px below the viewport's [top], positive (down) within [zone] px above its [bottom], zero elsewhere. It grows
 * linearly from zero at the zone's inner border to [maxSpeed] at the edge and stays there past it. A viewport shorter
 * than two zones splits between them. [top] and [bottom] are where the rows are visible: not under the floating bars.
 */
internal fun autoScrollSpeed(y: Float, top: Float, bottom: Float, zone: Float, maxSpeed: Float): Float {
    if (zone <= 0f || bottom <= top) return 0f
    val room = minOf(zone, (bottom - top) / 2f)
    val intoTop = (top + room - y) / room
    val intoBottom = (y - (bottom - room)) / room
    return when {
        intoTop > 0f -> -maxSpeed * intoTop.coerceAtMost(1f)
        intoBottom > 0f -> maxSpeed * intoBottom.coerceAtMost(1f)
        else -> 0f
    }
}

/**
 * One selecting drag over the rows [order]ed as listed. It starts on the [anchor] row and paints every row from there
 * to the finger with the anchor's new state ([selecting]: an unpicked anchor picks, a picked one unpicks). Each result
 * is computed from the rows picked before the drag ([base]), so moving back restores the rows the finger left.
 */
internal class DragPaint private constructor(
    private val order: List<String>,
    private val base: Set<String>,
    private val anchor: Int,
    val selecting: Boolean,
) {
    private var painted: Set<String> = paintTo(anchor)

    /** The picked rows once the finger is over row [key]; unchanged when [key] is not listed. */
    fun extendTo(key: String): Set<String> {
        val index = order.indexOf(key)
        if (index >= 0) painted = paintTo(index)
        return painted
    }

    private fun paintTo(index: Int): Set<String> {
        val range = order.subList(minOf(anchor, index), maxOf(anchor, index) + 1)
        return if (selecting) base + range else base - range.toSet()
    }

    companion object {
        /** A drag from row [anchorKey] with [picked] rows picked; `null` when that row is not in [order]. */
        fun begin(order: List<String>, picked: Set<String>, anchorKey: String): DragPaint? {
            val anchor = order.indexOf(anchorKey)
            return if (anchor < 0) null else DragPaint(order, picked, anchor, selecting = anchorKey !in picked)
        }
    }
}
