package io.sonora.feature.library.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max

/**
 * Rules of dragging a library row's chevron to the left to push its page with the finger, as an iOS table view pushes:
 * the drag starts only in the row's trailing strip, where the chevron is, and claims the finger only when it moves
 * left more than it moves up or down, so the list still scrolls. The page's leading edge follows the finger in from the
 * right; on release it is pushed past [COMMIT_FRACTION] of the width or on a leftward fling, otherwise it slides back.
 */
internal object ChevronPush {
    const val COMMIT_FRACTION = 0.5f

    /** How a drag that started on the chevron is read once the finger moved. */
    enum class Claim { Undecided, Push, Yield }

    /**
     * A finger down at [downX] may drag the page in: it is within the right [strip] of a row [width] wide. Never in a
     * right-to-left layout ([rtl]): the chevron is then on the left while the stack still pushes from the right.
     */
    fun canStart(downX: Float, width: Float, strip: Float, rtl: Boolean = false): Boolean =
        !rtl && width > 0f && downX >= width - strip && downX <= width

    /**
     * The part of a row ([rowWidth] x [rowHeight], row coordinates) taken from the system's right-edge back gesture so a
     * drag aimed at the chevron starts in the app: all of the system zone that reaches into the row ([zoneWidth] from its
     * right edge, the measured gesture inset), at most [maxHeight] tall (the row's share of the per-edge budget) and
     * centred on the chevron, which is centred in the row. `null` when no zone reaches the row, or in a right-to-left
     * layout, where no drag starts.
     */
    fun backExclusion(rowWidth: Float, rowHeight: Float, zoneWidth: Float, maxHeight: Float, rtl: Boolean): Rect? {
        if (rtl || rowWidth <= 0f || rowHeight <= 0f || zoneWidth <= 0f || maxHeight <= 0f) return null
        val height = minOf(rowHeight, maxHeight)
        val top = (rowHeight - height) / 2f
        return Rect((rowWidth - zoneWidth).coerceAtLeast(0f), top, rowWidth, top + height)
    }

    /** The page's offset (distance from the right edge) when the drag starts, its leading edge under the finger. */
    fun startOffset(downX: Float, width: Float): Float = (width - downX).coerceIn(0f, width)

    /**
     * After the finger travelled [travelX], [travelY] (px, left and up negative): undecided inside the touch [slop], a
     * push when it went left more than vertically, otherwise the list keeps the finger (a tie included).
     */
    fun claim(travelX: Float, travelY: Float, slop: Float): Claim = when {
        max(abs(travelX), abs(travelY)) < slop -> Claim.Undecided
        travelX < 0f && abs(travelX) > abs(travelY) -> Claim.Push
        else -> Claim.Yield
    }

    /** The page's offset after the finger moved [dx] (negative is left); it never goes back past its rest off screen. */
    fun dragged(offset: Float, dx: Float): Float = (offset - dx).coerceAtLeast(0f)

    /** How far the page has come in, 0 off the right edge to 1 in place. */
    fun progress(offset: Float, width: Float): Float = if (width <= 0f) 0f else (offset / width).coerceIn(0f, 1f)

    /**
     * Whether a release at [offset] with horizontal [velocityX] (px/s, left is negative) pushes the page. A pointer the
     * system took back ([cancelled]) never does.
     */
    fun shouldPush(offset: Float, velocityX: Float, width: Float, flingVelocity: Float, cancelled: Boolean): Boolean = when {
        cancelled -> false
        offset <= 0f -> false
        velocityX <= -flingVelocity -> true
        velocityX >= flingVelocity -> false
        else -> offset >= width * COMMIT_FRACTION
    }
}
