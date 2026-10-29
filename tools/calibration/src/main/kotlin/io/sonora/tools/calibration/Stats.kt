package io.sonora.tools.calibration

import kotlin.math.floor

/** Count, centre and spread of a set of values; every statistic is `NaN` when [count] is zero. */
class Summary(
    val count: Int,
    val mean: Double,
    val median: Double,
    val p95: Double,
    val min: Double,
    val max: Double,
) {
    override fun toString(): String = "Summary(n=$count, mean=$mean, median=$median, p95=$p95, min=$min, max=$max)"
}

/**
 * Descriptive statistics for the calibration reports. Empty input gives `NaN` (there is nothing to report), never an
 * exception, so a format without a single matched beat still gets a row. No function modifies its argument.
 */
object Stats {

    fun mean(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        var sum = 0.0
        for (v in values) sum += v
        return sum / values.size
    }

    fun median(values: DoubleArray): Double = percentile(values, 50.0)

    /**
     * The [p]-th percentile (0..100) by linear interpolation between closest ranks: rank `p / 100 * (n - 1)` over the
     * sorted values, the default "linear" method of NumPy and R type 7 (Hyndman and Fan, "Sample quantiles in
     * statistical packages", The American Statistician 50(4), 1996). One value is every percentile; `NaN` when empty.
     */
    fun percentile(values: DoubleArray, p: Double): Double {
        require(p in 0.0..100.0) { "p must be in 0..100: $p" }
        if (values.isEmpty()) return Double.NaN
        return percentileSorted(values.sortedArray(), p)
    }

    fun summarize(values: DoubleArray): Summary {
        if (values.isEmpty()) return Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        val sorted = values.sortedArray()
        return Summary(
            count = sorted.size,
            mean = mean(sorted),
            median = percentileSorted(sorted, 50.0),
            p95 = percentileSorted(sorted, 95.0),
            min = sorted.first(),
            max = sorted.last(),
        )
    }

    private fun percentileSorted(sorted: DoubleArray, p: Double): Double {
        val rank = p / 100.0 * (sorted.size - 1)
        val lower = floor(rank).toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        return sorted[lower] + (rank - lower) * (sorted[upper] - sorted[lower])
    }
}
