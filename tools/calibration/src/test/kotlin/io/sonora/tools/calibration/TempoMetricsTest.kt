package io.sonora.tools.calibration

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TempoMetricsTest {

    @Test
    fun `absolute and relative error`() {
        assertThat(TempoMetrics.absoluteError(126.0, 128.0)).isEqualTo(2.0)
        assertThat(TempoMetrics.absoluteError(130.0, 128.0)).isEqualTo(2.0)
        assertThat(TempoMetrics.relativeError(126.0, 120.0)).isWithin(1e-12).of(0.05)
        assertThat(TempoMetrics.relativeError(114.0, 120.0)).isWithin(1e-12).of(0.05)
        assertThat(TempoMetrics.relativeError(120.0, 120.0)).isEqualTo(0.0)
    }

    @Test
    fun `a reference tempo must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { TempoMetrics.relativeError(120.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { TempoMetrics.classifyOctave(120.0, -1.0) }
    }

    @Test
    fun `exact within the 4 percent tolerance`() {
        assertThat(TempoMetrics.classifyOctave(120.0, 120.0)).isEqualTo(OctaveClass.EXACT)
        // 4 percent of 120 is 4.8
        assertThat(TempoMetrics.classifyOctave(124.8, 120.0)).isEqualTo(OctaveClass.EXACT)
        assertThat(TempoMetrics.classifyOctave(115.2, 120.0)).isEqualTo(OctaveClass.EXACT)
        assertThat(TempoMetrics.classifyOctave(125.0, 120.0)).isEqualTo(OctaveClass.OTHER)
    }

    @Test
    fun `double half three halves and two thirds`() {
        assertThat(TempoMetrics.classifyOctave(240.0, 120.0)).isEqualTo(OctaveClass.DOUBLE)
        assertThat(TempoMetrics.classifyOctave(60.0, 120.0)).isEqualTo(OctaveClass.HALF)
        assertThat(TempoMetrics.classifyOctave(180.0, 120.0)).isEqualTo(OctaveClass.THREE_HALVES)
        assertThat(TempoMetrics.classifyOctave(80.0, 120.0)).isEqualTo(OctaveClass.TWO_THIRDS)
    }

    @Test
    fun `the tolerance applies to the target tempo of each class`() {
        // 2x of 120 = 240, 4 percent = 9.6
        assertThat(TempoMetrics.classifyOctave(249.6, 120.0)).isEqualTo(OctaveClass.DOUBLE)
        assertThat(TempoMetrics.classifyOctave(250.0, 120.0)).isEqualTo(OctaveClass.OTHER)
        // 1/2 of 120 = 60, 4 percent = 2.4
        assertThat(TempoMetrics.classifyOctave(62.4, 120.0)).isEqualTo(OctaveClass.HALF)
        assertThat(TempoMetrics.classifyOctave(62.5, 120.0)).isEqualTo(OctaveClass.OTHER)
    }

    @Test
    fun `anything else is other`() {
        assertThat(TempoMetrics.classifyOctave(100.0, 120.0)).isEqualTo(OctaveClass.OTHER)
        assertThat(TempoMetrics.classifyOctave(360.0, 120.0)).isEqualTo(OctaveClass.OTHER)
    }

    @Test
    fun `a custom tolerance is honoured`() {
        assertThat(TempoMetrics.classifyOctave(121.0, 120.0, tolerance = 0.001)).isEqualTo(OctaveClass.OTHER)
        assertThat(TempoMetrics.classifyOctave(121.0, 120.0, tolerance = 0.01)).isEqualTo(OctaveClass.EXACT)
    }

    @Test
    fun `accuracy 1 is exact and accuracy 2 accepts the metrical relations`() {
        assertThat(OctaveClass.EXACT.isAcc1).isTrue()
        assertThat(OctaveClass.DOUBLE.isAcc1).isFalse()
        assertThat(OctaveClass.EXACT.isAcc2).isTrue()
        assertThat(OctaveClass.DOUBLE.isAcc2).isTrue()
        assertThat(OctaveClass.HALF.isAcc2).isTrue()
        assertThat(OctaveClass.THREE_HALVES.isAcc2).isTrue()
        assertThat(OctaveClass.TWO_THIRDS.isAcc2).isTrue()
        assertThat(OctaveClass.OTHER.isAcc2).isFalse()
    }

    @Test
    fun `histogram counts every class including empty ones`() {
        val histogram = TempoMetrics.histogram(
            listOf(OctaveClass.EXACT, OctaveClass.EXACT, OctaveClass.HALF, OctaveClass.OTHER),
        )
        assertThat(histogram[OctaveClass.EXACT]).isEqualTo(2)
        assertThat(histogram[OctaveClass.HALF]).isEqualTo(1)
        assertThat(histogram[OctaveClass.OTHER]).isEqualTo(1)
        assertThat(histogram[OctaveClass.DOUBLE]).isEqualTo(0)
        assertThat(histogram.keys).containsExactlyElementsIn(OctaveClass.entries).inOrder()
    }
}
