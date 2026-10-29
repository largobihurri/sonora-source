package io.sonora.feature.dj.waveform

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Which tiles of the scrolling waveform are on screen and which to keep ready, for a track of [tileCount] tiles.
 *
 * [wanted] is called once per drawn frame, so it allocates nothing: it rewrites the four index fields in place. The
 * visible tiles are those under the view centred on the playhead; the wanted ones add [prefetch] tiles beyond the edge
 * the waveform moves toward (the playing direction, or the drag), or one on each side when it stands still, so a tile
 * is rendered before it scrolls in. Tiles before the track start or after its end are never visible nor wanted (the
 * view simply shows nothing there). Ranges are inclusive; an empty one has `first > last`.
 */
class TilePlan(val tileCount: Int, val prefetch: Int = PREFETCH_TILES) {

    var firstVisible: Int = 0
        private set
    var lastVisible: Int = -1
        private set
    var firstWanted: Int = 0
        private set
    var lastWanted: Int = -1
        private set

    /** Number of tiles on screen. */
    val visibleCount: Int get() = (lastVisible - firstVisible + 1).coerceAtLeast(0)

    /** Number of tiles to keep: the visible ones and the prefetch. */
    val wantedCount: Int get() = (lastWanted - firstWanted + 1).coerceAtLeast(0)

    /** Whether tile [index] is visible or prefetched. */
    fun isWanted(index: Int): Boolean = index in firstWanted..lastWanted

    /**
     * Plans for the playhead at the absolute pixel [centrePx] on a view [widthPx] wide; [direction] is the sign of the
     * motion (positive forward, negative backward, 0 still).
     */
    fun wanted(centrePx: Double, widthPx: Int, direction: Int) {
        if (widthPx <= 0 || tileCount <= 0 || !centrePx.isFinite()) {
            firstVisible = 0
            lastVisible = -1
            firstWanted = 0
            lastWanted = -1
            return
        }
        val leftPx = floor(centrePx - widthPx / 2.0).toLong()
        val first = WaveformTiles.indexOf(leftPx)
        val last = WaveformTiles.indexOf(leftPx + widthPx - 1)
        val behind = when {
            direction > 0 -> 0
            direction < 0 -> prefetch
            else -> minOf(1, prefetch)
        }
        val ahead = when {
            direction > 0 -> prefetch
            direction < 0 -> 0
            else -> minOf(1, prefetch)
        }
        val lastTile = tileCount - 1
        firstVisible = first.coerceAtLeast(0)
        lastVisible = last.coerceAtMost(lastTile)
        firstWanted = (first - behind).coerceAtLeast(0)
        lastWanted = (last + ahead).coerceAtMost(lastTile)
    }

    companion object {
        /** Tiles rendered ahead of the view in the playing direction. */
        const val PREFETCH_TILES: Int = 2

        /** The smallest tile cache, per zoom. */
        const val MIN_CAPACITY: Int = 12

        /**
         * The number of tiles of a track [durationMs] long at [pxPerMs] (see [WaveformScale.pxPerMs]): enough to cover
         * every pixel of its [WaveformEnvelope]; 0 for an empty or unknown duration.
         */
        fun tileCountFor(durationMs: Double, pxPerMs: Double): Int {
            val pixels = durationMs * pxPerMs
            if (!(pixels > 0) || pixels.isInfinite()) return 0
            val wholePixels = ceil(pixels - PIXEL_EPSILON).toLong()
            return ((wholePixels + WaveformTiles.TILE_PX - 1) / WaveformTiles.TILE_PX).toInt()
        }

        /**
         * The tile cache size for a view [widthPx] wide: the most tiles a view can touch, the prefetch and a spare on
         * each side, and never under [MIN_CAPACITY], so the wanted tiles always fit.
         */
        fun capacityFor(widthPx: Int, prefetch: Int = PREFETCH_TILES): Int {
            val tile = WaveformTiles.TILE_PX
            val mostVisible = (widthPx.coerceAtLeast(1) + tile - 2) / tile + 1
            return maxOf(MIN_CAPACITY, mostVisible + prefetch + 2)
        }

        private const val PIXEL_EPSILON = 1e-9
    }
}
