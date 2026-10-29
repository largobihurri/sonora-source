package io.sonora.feature.dj.waveform

/**
 * Fixed-width tiles of the scrolling waveform in absolute pixels: tile `i` covers pixels `[i * 256, (i + 1) * 256)`.
 * The tiles cover the view with no gap and no overlap, so a tile drawn once is reused while it scrolls.
 */
object WaveformTiles {
    const val TILE_PX: Int = 256

    /** The indices of the tiles that intersect the pixels `[leftPx, leftPx + widthPx)`; empty when [widthPx] is not positive. */
    fun tilesFor(leftPx: Long, widthPx: Int): IntRange {
        if (widthPx <= 0) return IntRange.EMPTY
        return indexOf(leftPx)..indexOf(leftPx + widthPx - 1)
    }

    /** The first absolute pixel of tile [index]. */
    fun startPx(index: Int): Long = index.toLong() * TILE_PX

    /** The tile that holds the absolute pixel [px] (floor division, so pixels before the track start work). */
    fun indexOf(px: Long): Int = Math.floorDiv(px, TILE_PX.toLong()).toInt()
}
