package io.sonora.tools.calibration

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaveformMetricsTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** A deterministic, non-periodic plane. */
    private fun noise(size: Int, seed: Int): ByteArray {
        var state = seed
        return ByteArray(size) {
            state = state * 1103515245 + 12345
            ((state ushr 16) and 0xFF).toByte()
        }
    }

    @Test
    fun `identical planes correlate at one`() {
        val plane = bytes(0, 10, 200, 40, 255, 3)
        assertThat(WaveformMetrics.correlation(plane, plane)).isWithin(1e-12).of(1.0)
    }

    @Test
    fun `an inverted plane correlates at minus one`() {
        val plane = bytes(0, 10, 200, 40, 255, 3)
        val inverted = ByteArray(plane.size) { (255 - (plane[it].toInt() and 0xFF)).toByte() }
        assertThat(WaveformMetrics.correlation(plane, inverted)).isWithin(1e-12).of(-1.0)
    }

    @Test
    fun `a scaled and offset plane still correlates at one`() {
        val a = bytes(10, 20, 30, 40)
        val b = bytes(110, 130, 150, 170)
        assertThat(WaveformMetrics.correlation(a, b)).isWithin(1e-12).of(1.0)
    }

    @Test
    fun `bytes are read as unsigned levels`() {
        // 200 and 100 are -56 and 100 as signed bytes; unsigned they are an increasing ramp with 150.
        val a = bytes(100, 150, 200)
        val b = bytes(1, 2, 3)
        assertThat(WaveformMetrics.correlation(a, b)).isWithin(1e-12).of(1.0)
    }

    @Test
    fun `a plane shifted by one column correlates at minus one third`() {
        // Hand computed: mean 2.5, deviations (-2.5, 7.5, -2.5, -2.5) and (-2.5, -2.5, 7.5, -2.5);
        // covariance sum = 6.25 - 18.75 - 18.75 + 6.25 = -25; variance sum = 75; r = -25 / 75.
        val a = bytes(0, 10, 0, 0)
        val b = bytes(0, 0, 10, 0)
        assertThat(WaveformMetrics.correlation(a, b)).isWithin(1e-12).of(-1.0 / 3.0)
    }

    @Test
    fun `a shifted noise plane no longer correlates strongly`() {
        val long = noise(400, seed = 7)
        val a = long.copyOfRange(0, 300)
        val b = long.copyOfRange(25, 325)
        assertThat(WaveformMetrics.correlation(a, a)).isWithin(1e-12).of(1.0)
        assertThat(Math.abs(WaveformMetrics.correlation(a, b))).isLessThan(0.3)
    }

    @Test
    fun `a flat plane has no correlation`() {
        assertThat(WaveformMetrics.correlation(bytes(5, 5, 5), bytes(1, 2, 3))).isEqualTo(0.0)
        assertThat(WaveformMetrics.correlation(bytes(5, 5, 5), bytes(5, 5, 5))).isEqualTo(0.0)
        assertThat(WaveformMetrics.correlation(ByteArray(0), ByteArray(0))).isEqualTo(0.0)
    }

    @Test
    fun `resampling to a shorter length averages the covered columns`() {
        val resampled = WaveformMetrics.resample(bytes(10, 20, 30, 50), 2)
        assertThat(resampled.toList()).containsExactly(15.0, 40.0).inOrder()
    }

    @Test
    fun `resampling weights fractional columns`() {
        // 3 columns -> 2: output 0 covers [0, 1.5) = 10 * 1 + 20 * 0.5 over 1.5; output 1 covers [1.5, 3).
        val resampled = WaveformMetrics.resample(bytes(10, 20, 30), 2)
        assertThat(resampled[0]).isWithin(1e-12).of((10.0 + 10.0) / 1.5)
        assertThat(resampled[1]).isWithin(1e-12).of((10.0 + 30.0) / 1.5)
    }

    @Test
    fun `resampling to the same length is the identity and to a longer one repeats columns`() {
        assertThat(WaveformMetrics.resample(bytes(1, 2, 3), 3).toList()).containsExactly(1.0, 2.0, 3.0).inOrder()
        assertThat(WaveformMetrics.resample(bytes(1, 3), 4).toList()).containsExactly(1.0, 1.0, 3.0, 3.0).inOrder()
    }

    @Test
    fun `planes of different lengths are compared after resampling to the shorter`() {
        val short = bytes(10, 40, 10, 40)
        val long = ByteArray(8) { if ((it / 2) % 2 == 0) 10 else 40 } // each short column twice
        assertThat(WaveformMetrics.correlation(short, long)).isWithin(1e-12).of(1.0)
        assertThat(WaveformMetrics.correlation(long, short)).isWithin(1e-12).of(1.0)
    }

    @Test
    fun `the identity band order is recovered`() {
        val low = noise(200, 1)
        val mid = noise(200, 2)
        val high = noise(200, 3)

        val result = WaveformMetrics.bandOrder(ours = listOf(low, mid, high), reference = listOf(low, mid, high))

        assertThat(result.best.assignment).containsExactly(0, 1, 2).inOrder()
        assertThat(result.best.meanCorrelation).isWithin(1e-12).of(1.0)
        assertThat(result.ranked).hasSize(6)
    }

    @Test
    fun `every one of the six permutations is recovered`() {
        val low = noise(300, 11)
        val mid = noise(300, 22)
        val high = noise(300, 33)
        val ours = listOf(low, mid, high)
        val permutations = listOf(
            listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0),
        )

        for (stored in permutations) {
            // The reference file stores plane k = ours[stored[k]], so ours band i sits at stored position j where
            // stored[j] == i.
            val reference = stored.map { ours[it] }
            val expected = (0..2).map { i -> stored.indexOf(i) }

            val result = WaveformMetrics.bandOrder(ours, reference)

            assertThat(result.best.assignment).isEqualTo(expected)
            assertThat(result.best.meanCorrelation).isWithin(1e-12).of(1.0)
            assertThat(result.best.correlations).hasSize(3)
        }
    }

    @Test
    fun `ranked candidates run from best to worst and cover all six permutations`() {
        val low = noise(200, 4)
        val mid = noise(200, 5)
        val high = noise(200, 6)

        val result = WaveformMetrics.bandOrder(listOf(low, mid, high), listOf(mid, low, high))

        assertThat(result.best.assignment).containsExactly(1, 0, 2).inOrder()
        assertThat(result.ranked.map { it.assignment }.toSet()).hasSize(6)
        assertThat(result.ranked.map { it.meanCorrelation }).isInOrder(Comparator.reverseOrder<Double>())
        assertThat(result.identity.assignment).containsExactly(0, 1, 2).inOrder()
        assertThat(result.identity.meanCorrelation).isLessThan(result.best.meanCorrelation)
    }

    @Test
    fun `band order works on planes of different lengths`() {
        val low = noise(300, 8)
        val mid = noise(300, 9)
        val high = noise(300, 10)
        val half = { plane: ByteArray -> WaveformMetrics.resample(plane, 150).let { r -> ByteArray(150) { r[it].toInt().toByte() } } }

        val result = WaveformMetrics.bandOrder(listOf(low, mid, high), listOf(half(high), half(low), half(mid)))

        // reference position 0 holds high (ours 2), 1 holds low (ours 0), 2 holds mid (ours 1).
        assertThat(result.best.assignment).containsExactly(1, 2, 0).inOrder()
        assertThat(result.best.meanCorrelation).isGreaterThan(0.99)
    }
}
