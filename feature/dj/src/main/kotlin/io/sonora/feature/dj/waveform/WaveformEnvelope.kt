package io.sonora.feature.dj.waveform

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The level of one waveform band per absolute pixel at one scale: for each pixel, the MAXIMUM level of the columns it
 * covers (a peak envelope, never an average or a single sample), so a loud column survives every zoom.
 *
 * Column `c` of a plane with `columnsPerSecond` columns per second spans `[c, c + 1) * 1000 / columnsPerSecond` ms and
 * belongs to the pixel that holds its centre, so a column is counted in exactly one pixel when pixels are wider than
 * columns. When pixels are narrower than columns (zoomed in beyond the data density) a pixel holds the level of the
 * column under its centre, so a column repeats over its pixels instead of leaving gaps.
 *
 * Levels are unsigned bytes (0..255), as in [io.sonora.core.djmodel.BandWaveform]. Built once per track and scale;
 * reading is allocation free.
 */
class WaveformEnvelope private constructor(private val levels: ByteArray) {

    /** Number of pixels from the track start to the end of the plane. */
    val pixelCount: Int get() = levels.size

    /** The level (0..255) at the absolute pixel [px]; 0 outside the track. */
    fun levelAt(px: Int): Int = if (px < 0 || px >= levels.size) 0 else levels[px].toInt() and 0xFF

    companion object {
        /**
         * Builds the envelope of [plane] for a scale of [pxPerMs] pixels per millisecond (see
         * [WaveformScale.pxPerMs]); [columnsPerSecond] is the density of the plane
         * ([io.sonora.core.djmodel.BandWaveform.COLUMNS_PER_SECOND] by default).
         */
        fun build(plane: ByteArray, columnsPerSecond: Int = DEFAULT_COLUMNS_PER_SECOND, pxPerMs: Double): WaveformEnvelope {
            require(columnsPerSecond > 0) { "columnsPerSecond must be positive: $columnsPerSecond" }
            require(pxPerMs > 0 && pxPerMs.isFinite()) { "pxPerMs must be positive: $pxPerMs" }
            val columns = plane.size
            if (columns == 0) return EMPTY
            val columnsPerPx = columnsPerSecond / (1000.0 * pxPerMs)
            val pixels = ceil(columns / columnsPerPx - PIXEL_EPSILON).toInt().coerceAtLeast(1)
            val out = ByteArray(pixels)
            val last = columns - 1
            for (px in 0 until pixels) {
                // Columns whose centre lies in [px, px + 1) pixels: ceil(px * cpp - 0.5) until ceil((px + 1) * cpp - 0.5).
                val first = ceil(px * columnsPerPx - 0.5).toInt()
                val end = ceil((px + 1) * columnsPerPx - 0.5).toInt()
                if (end <= first) {
                    // Narrower than a column: hold the column under the pixel's centre.
                    out[px] = plane[floor((px + 0.5) * columnsPerPx).toInt().coerceIn(0, last)]
                } else {
                    var max = 0
                    for (c in first.coerceAtLeast(0)..(end - 1).coerceAtMost(last)) {
                        val level = plane[c].toInt() and 0xFF
                        if (level > max) max = level
                    }
                    out[px] = max.toByte()
                }
            }
            return WaveformEnvelope(out)
        }

        private const val DEFAULT_COLUMNS_PER_SECOND = 150

        /** Guards the pixel count against a float residue turning a whole number of pixels into one more. */
        private const val PIXEL_EPSILON = 1e-9

        private val EMPTY = WaveformEnvelope(ByteArray(0))
    }
}
