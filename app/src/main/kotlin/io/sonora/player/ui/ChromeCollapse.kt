package io.sonora.player.ui

/**
 * Whether the floating bottom chrome (tab bar and mini player) is minimised, as on iOS 26: it collapses after
 * [collapseAfter] of net scroll toward the content's end and expands after [expandAfter] of net scroll toward its start,
 * at the top of the content, or on [expand] (a tap on the compact pill, a tab switch, Now Playing closing). Pushing or
 * popping a screen keeps the current size. Distances are in pixels of what the content actually scrolled, so overscroll, the stretch relaxing and content
 * too short to scroll never count.
 *
 * Hysteresis: travel is net (a small reversal subtracts instead of resetting), the two directions have their own
 * thresholds, and both are forgotten on every change, so the decaying end of a fling or finger jitter cannot toggle the
 * state back and forth.
 */
internal class ChromeCollapse(private val collapseAfter: Float, private val expandAfter: Float) {
    var collapsed: Boolean = false
        private set

    private var towardEnd = 0f
    private var towardStart = 0f

    /**
     * One nested scroll step along the scroll axis: [consumed] is what the content scrolled (negative toward its end, as
     * nested scroll signs a finger moving up) and [left] what it could not take. Returns whether [collapsed] changed.
     */
    fun onScroll(consumed: Float, left: Float): Boolean {
        // Pulled toward the start with nothing left to scroll: the content is at its top.
        if (left > TopLeftover) return expand()
        when {
            consumed < 0f -> {
                towardEnd += -consumed
                towardStart = (towardStart + consumed).coerceAtLeast(0f)
            }
            consumed > 0f -> {
                towardStart += consumed
                towardEnd = (towardEnd - consumed).coerceAtLeast(0f)
            }
            else -> return false
        }
        return when {
            !collapsed && towardEnd >= collapseAfter -> change(collapse = true)
            collapsed && towardStart >= expandAfter -> change(collapse = false)
            else -> false
        }
    }

    /**
     * A screen appeared or left (a push or a pop): the chrome keeps its size, as on iOS, and only the partial travel
     * measured on the previous screen is forgotten. Returns whether [collapsed] changed, which is never.
     */
    fun onScreenChanged(): Boolean {
        towardEnd = 0f
        towardStart = 0f
        return false
    }

    /** Expands (if collapsed) and forgets any partial travel. Returns whether [collapsed] changed. */
    fun expand(): Boolean = change(collapse = false)

    private fun change(collapse: Boolean): Boolean {
        towardEnd = 0f
        towardStart = 0f
        if (collapsed == collapse) return false
        collapsed = collapse
        return true
    }

    private companion object {
        /** Lists can leave a sub-pixel remainder mid-content; only a real leftover means the top was reached. */
        const val TopLeftover = 0.5f
    }
}
