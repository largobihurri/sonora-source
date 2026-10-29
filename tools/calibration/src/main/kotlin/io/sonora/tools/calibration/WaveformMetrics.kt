package io.sonora.tools.calibration

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** One way of pairing our bands with the reference planes: our band `i` is compared with reference plane `assignment[i]`. */
class BandOrderCandidate(
    val assignment: List<Int>,
    /** Pearson correlation of each of our bands (low, mid, high) with its assigned reference plane. */
    val correlations: List<Double>,
    val meanCorrelation: Double,
)

/**
 * All six pairings, [ranked] from the highest mean correlation to the lowest (ties keep the lexicographic order of
 * the assignments, so the identity wins a tie). [best] is the first one and [identity] the pairing where the
 * reference stores the planes as low, mid, high.
 */
class BandOrderResult(val ranked: List<BandOrderCandidate>) {
    val best: BandOrderCandidate get() = ranked.first()
    val identity: BandOrderCandidate get() = ranked.first { it.assignment == listOf(0, 1, 2) }
}

/**
 * Waveform comparison between two byte planes (levels 0..255, read as unsigned): the calibration of our 3-band
 * waveform against the rekordbox colour waveforms (PWV4/5/6/7).
 */
object WaveformMetrics {

    /**
     * Resamples [plane] (unsigned levels) to [targetLength] columns by area averaging: output column `i` is the mean
     * of the source over `[i * L / n, (i + 1) * L / n)` with fractional columns weighted by their overlap. Shrinking
     * averages, growing repeats (blending where a target column straddles two source columns).
     */
    fun resample(plane: ByteArray, targetLength: Int): DoubleArray {
        require(targetLength >= 0) { "targetLength must not be negative: $targetLength" }
        if (plane.isEmpty()) {
            require(targetLength == 0) { "Cannot resample an empty plane to $targetLength columns" }
            return DoubleArray(0)
        }
        val source = plane.size
        return DoubleArray(targetLength) { i ->
            val start = i.toDouble() * source / targetLength
            val end = (i + 1).toDouble() * source / targetLength
            var sum = 0.0
            val last = minOf(ceil(end).toInt() - 1, source - 1)
            for (j in floor(start).toInt()..last) {
                val overlap = minOf(end, (j + 1).toDouble()) - maxOf(start, j.toDouble())
                if (overlap > 0) sum += (plane[j].toInt() and 0xFF) * overlap
            }
            sum / (end - start)
        }
    }

    /**
     * Pearson correlation (Pearson, 1895) of two planes after resampling both to the shorter length. Scaling and
     * offsets do not matter. Returns 0 when either plane is flat (the correlation is undefined) or empty.
     */
    fun correlation(a: ByteArray, b: ByteArray): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        return pearson(resample(a, n), resample(b, n))
    }

    /**
     * Tries the six assignments of our three bands (low, mid, high) to the reference's three planes (given in the
     * order the file stores them) and ranks them by mean per-band correlation, because the band order of rekordbox
     * PWV6/7 was disputed between sources (D1.20 settled it on 150 of 150 tracks: low, mid, high, the identity
     * assignment; `ThreeBandWaveformTag` reads that order since D1.26). An assignment `[1, 2, 0]` reads: our low band is the reference's second
     * plane, our mid band its third, our high band its first.
     */
    fun bandOrder(ours: List<ByteArray>, reference: List<ByteArray>): BandOrderResult {
        require(ours.size == BANDS && reference.size == BANDS) { "Three planes are needed on each side" }
        val matrix = Array(BANDS) { i -> DoubleArray(BANDS) { j -> correlation(ours[i], reference[j]) } }
        val candidates = PERMUTATIONS.map { assignment ->
            val correlations = assignment.mapIndexed { i, j -> matrix[i][j] }
            BandOrderCandidate(assignment, correlations, correlations.average())
        }
        return BandOrderResult(candidates.sortedByDescending { it.meanCorrelation })
    }

    private fun pearson(x: DoubleArray, y: DoubleArray): Double {
        val n = x.size
        var meanX = 0.0
        var meanY = 0.0
        for (i in 0 until n) {
            meanX += x[i]
            meanY += y[i]
        }
        meanX /= n
        meanY /= n
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (i in 0 until n) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        if (varianceX < FLAT_VARIANCE || varianceY < FLAT_VARIANCE) return 0.0
        return (covariance / sqrt(varianceX * varianceY)).coerceIn(-1.0, 1.0)
    }

    private const val BANDS = 3
    private const val FLAT_VARIANCE = 1e-12
    private val PERMUTATIONS: List<List<Int>> = listOf(
        listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0),
    )
}
