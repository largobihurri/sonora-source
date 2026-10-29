package io.sonora.tools.calibration

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class StatsTest {

    @Test
    fun `empty input has no mean median or percentile`() {
        assertThat(Stats.mean(DoubleArray(0))).isNaN()
        assertThat(Stats.median(DoubleArray(0))).isNaN()
        assertThat(Stats.percentile(DoubleArray(0), 95.0)).isNaN()
    }

    @Test
    fun `a single value is every statistic`() {
        val one = doubleArrayOf(7.5)
        assertThat(Stats.mean(one)).isEqualTo(7.5)
        assertThat(Stats.median(one)).isEqualTo(7.5)
        assertThat(Stats.percentile(one, 0.0)).isEqualTo(7.5)
        assertThat(Stats.percentile(one, 95.0)).isEqualTo(7.5)
        assertThat(Stats.percentile(one, 100.0)).isEqualTo(7.5)
    }

    @Test
    fun `median of an odd and an even count`() {
        assertThat(Stats.median(doubleArrayOf(5.0, 1.0, 3.0))).isEqualTo(3.0)
        assertThat(Stats.median(doubleArrayOf(4.0, 1.0, 3.0, 2.0))).isEqualTo(2.5)
    }

    @Test
    fun `percentiles interpolate linearly between ranks`() {
        val values = doubleArrayOf(5.0, 1.0, 4.0, 2.0, 3.0)
        assertThat(Stats.percentile(values, 0.0)).isEqualTo(1.0)
        assertThat(Stats.percentile(values, 100.0)).isEqualTo(5.0)
        assertThat(Stats.percentile(values, 50.0)).isEqualTo(3.0)
        // rank = 0.95 * 4 = 3.8 -> 4 + 0.8 * (5 - 4)
        assertThat(Stats.percentile(values, 95.0)).isWithin(1e-12).of(4.8)
        assertThat(Stats.percentile(values, 25.0)).isEqualTo(2.0)
    }

    @Test
    fun `the input array is not modified`() {
        val values = doubleArrayOf(3.0, 1.0, 2.0)
        Stats.median(values)
        assertThat(values).isEqualTo(doubleArrayOf(3.0, 1.0, 2.0))
    }

    @Test
    fun `percentile outside 0 to 100 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Stats.percentile(doubleArrayOf(1.0), -0.1) }
        assertThrows(IllegalArgumentException::class.java) { Stats.percentile(doubleArrayOf(1.0), 100.1) }
    }

    @Test
    fun `summary of known values`() {
        val summary = Stats.summarize(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0))
        assertThat(summary.count).isEqualTo(5)
        assertThat(summary.mean).isEqualTo(3.0)
        assertThat(summary.median).isEqualTo(3.0)
        assertThat(summary.p95).isWithin(1e-12).of(4.8)
        assertThat(summary.min).isEqualTo(1.0)
        assertThat(summary.max).isEqualTo(5.0)
    }

    @Test
    fun `summary of nothing is NaN with a zero count`() {
        val summary = Stats.summarize(DoubleArray(0))
        assertThat(summary.count).isEqualTo(0)
        assertThat(summary.mean).isNaN()
        assertThat(summary.median).isNaN()
        assertThat(summary.p95).isNaN()
        assertThat(summary.min).isNaN()
        assertThat(summary.max).isNaN()
    }
}
