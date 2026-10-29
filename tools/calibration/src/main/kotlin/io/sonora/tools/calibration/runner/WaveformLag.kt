package io.sonora.tools.calibration.runner

import io.sonora.core.djmodel.BandWaveform
import kotlin.math.sqrt

/**
 * The time offset between our waveform and a rekordbox waveform of the same track, from their cross-correlation: an
 * estimate of the decoder time base that does not depend on beat tracking (MP3 decoders differ in how much encoder
 * delay they skip). Both planes are 150 columns per second.
 */
object WaveformLag {

    /**
     * The lag in columns, within `[-maxLag, maxLag]`, that best aligns [ours] with [reference]: positive when our plane
     * is late (our column `i + lag` matches their column `i`). The normalised cross-correlation is scanned at whole
     * columns and refined by a parabola through the best one and its neighbours. `NaN` when a plane is flat or empty.
     */
    fun lagColumns(ours: ByteArray, reference: ByteArray, maxLag: Int): Double {
        require(maxLag >= 0) { "maxLag must not be negative: $maxLag" }
        if (ours.isEmpty() || reference.isEmpty()) return Double.NaN
        val scores = DoubleArray(2 * maxLag + 1) { score(ours, reference, it - maxLag) }
        var best = -1
        for (i in scores.indices) if (!scores[i].isNaN() && (best < 0 || scores[i] > scores[best])) best = i
        if (best < 0) return Double.NaN
        val lag = (best - maxLag).toDouble()
        if (best == 0 || best == scores.size - 1) return lag
        val left = scores[best - 1]
        val centre = scores[best]
        val right = scores[best + 1]
        val curvature = left - 2 * centre + right
        if (left.isNaN() || right.isNaN() || curvature >= 0) return lag
        return lag + 0.5 * (left - right) / curvature
    }

    /** Columns to milliseconds at [BandWaveform.COLUMNS_PER_SECOND]. */
    fun columnsToMs(columns: Double): Double = columns * MS_PER_SECOND / BandWaveform.COLUMNS_PER_SECOND

    /** Pearson correlation of `ours[i + lag]` with `reference[i]` over their overlap; `NaN` when either side is flat. */
    private fun score(ours: ByteArray, reference: ByteArray, lag: Int): Double {
        val from = maxOf(0, -lag)
        val to = minOf(reference.size, ours.size - lag)
        val n = to - from
        if (n < MIN_OVERLAP) return Double.NaN
        var sumX = 0.0
        var sumY = 0.0
        for (i in from until to) {
            sumX += ours[i + lag].toInt() and 0xFF
            sumY += reference[i].toInt() and 0xFF
        }
        val meanX = sumX / n
        val meanY = sumY / n
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (i in from until to) {
            val dx = (ours[i + lag].toInt() and 0xFF) - meanX
            val dy = (reference[i].toInt() and 0xFF) - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        if (varianceX < FLAT || varianceY < FLAT) return Double.NaN
        return covariance / sqrt(varianceX * varianceY)
    }

    private const val MS_PER_SECOND = 1_000.0
    private const val MIN_OVERLAP = 16
    private const val FLAT = 1e-9
}
