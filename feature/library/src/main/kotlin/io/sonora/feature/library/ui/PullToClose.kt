package io.sonora.feature.library.ui

/**
 * Rules of pulling an album or artist page down to close it (as Back does): the drag starts on the header while the
 * list is at its top, the page follows the finger, and on release it closes past [CLOSE_FRACTION] of the height or on
 * a downward fling; otherwise it springs back.
 */
internal object PullToClose {
    const val CLOSE_FRACTION = 0.25f

    /** A finger down at [downY] may pull: the list is scrolled to its very top and the finger is on the header. */
    fun canStart(firstVisibleIndex: Int, firstVisibleOffset: Int, downY: Float, headerBottom: Float?): Boolean =
        firstVisibleIndex == 0 && firstVisibleOffset == 0 && headerBottom != null && downY <= headerBottom

    /** The page's offset after the finger moved [dy] down (negative is up); it never rises above its place. */
    fun dragged(offset: Float, dy: Float): Float = (offset + dy).coerceAtLeast(0f)

    /** Whether a release at [offset] with vertical [velocity] (px/s, down is positive) closes the page. */
    fun shouldClose(offset: Float, velocity: Float, height: Float, flingVelocity: Float): Boolean = when {
        offset <= 0f -> false
        velocity >= flingVelocity -> true
        velocity <= -flingVelocity -> false
        else -> offset >= height * CLOSE_FRACTION
    }

    /** How far the page has gone, 0 in place to 1 off the bottom. */
    fun progress(offset: Float, height: Float): Float = if (height <= 0f) 0f else (offset / height).coerceIn(0f, 1f)
}
