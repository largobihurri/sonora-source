package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QuantizeTest {

    /** 120 BPM: a beat every 500 ms, first beat at 1000 ms (so beats run back to 0 ms), which is beat 1 of its bar. */
    private val constant = DjBeatGrid(listOf(TempoSegment(1_000.0, 120.0, 1)))

    @Test
    fun `without a grid the position is unchanged`() {
        assertThat(Quantize.snap(1_234.5, null)).isEqualTo(1_234.5)
    }

    @Test
    fun `the position snaps to the nearest beat`() {
        assertThat(Quantize.snap(1_240.0, constant)).isWithin(1e-9).of(1_000.0)
        assertThat(Quantize.snap(1_260.0, constant)).isWithin(1e-9).of(1_500.0)
        assertThat(Quantize.snap(7_999.0, constant)).isWithin(1e-9).of(8_000.0)
    }

    @Test
    fun `a position on the grid stays where it is`() {
        assertThat(Quantize.snap(2_500.0, constant)).isWithin(1e-9).of(2_500.0)
    }

    @Test
    fun `halfway between two beats goes to the later one`() {
        assertThat(Quantize.snap(1_250.0, constant)).isWithin(1e-9).of(1_500.0)
    }

    @Test
    fun `before the first beat it snaps to a beat that runs back to the start of the track and never below zero`() {
        assertThat(Quantize.snap(520.0, constant)).isWithin(1e-9).of(500.0)
        assertThat(Quantize.snap(100.0, constant)).isWithin(1e-9).of(0.0)
        assertThat(Quantize.snap(0.0, constant)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `a grid whose first beat is late has a first beat that is not at zero`() {
        val late = DjBeatGrid(listOf(TempoSegment(300.0, 120.0, 1))) // beats at 300, 800, ... (nothing before 0)
        assertThat(Quantize.snap(10.0, late)).isWithin(1e-9).of(300.0)
    }

    @Test
    fun `the resolution can be a fraction of a beat or whole bars`() {
        assertThat(Quantize.snap(1_130.0, constant, resolutionBeats = 0.5)).isWithin(1e-9).of(1_250.0)
        assertThat(Quantize.snap(1_100.0, constant, resolutionBeats = 0.5)).isWithin(1e-9).of(1_000.0)
        // a bar is four beats: bar starts at 1000, 3000, 5000 ...
        assertThat(Quantize.snap(3_900.0, constant, resolutionBeats = 4.0)).isWithin(1e-9).of(3_000.0)
        assertThat(Quantize.snap(4_100.0, constant, resolutionBeats = 4.0)).isWithin(1e-9).of(5_000.0)
    }

    @Test
    fun `bars follow the downbeat of the grid, not the first beat`() {
        val offset = DjBeatGrid(listOf(TempoSegment(1_500.0, 120.0, 2))) // beat 2 at 1500 so the bar starts at 1000
        assertThat(Quantize.snap(3_100.0, offset, resolutionBeats = 4.0)).isWithin(1e-9).of(3_000.0)
    }

    @Test
    fun `a variable tempo grid snaps within each segment and never between a segment's beats`() {
        // 120 BPM (500 ms beats) from 0 to 2000, then 60 BPM (1000 ms beats) from 2000.
        val variable = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(2_000.0, 60.0, 1)))

        assertThat(Quantize.snap(1_900.0, variable)).isWithin(1e-9).of(2_000.0)
        assertThat(Quantize.snap(2_400.0, variable)).isWithin(1e-9).of(2_000.0)
        assertThat(Quantize.snap(2_600.0, variable)).isWithin(1e-9).of(3_000.0)
        assertThat(Quantize.snap(1_740.0, variable)).isWithin(1e-9).of(1_500.0)
        assertThat(Quantize.snap(1_760.0, variable)).isWithin(1e-9).of(2_000.0)
    }

    @Test
    fun `the result never goes past the end of the track`() {
        assertThat(Quantize.snap(9_900.0, constant, durationMs = 9_950.0)).isWithin(1e-9).of(9_500.0)
        assertThat(Quantize.snap(9_900.0, constant, durationMs = 0.0)).isWithin(1e-9).of(10_000.0) // 0 means unknown
    }

    @Test
    fun `a position that is not a number, or a resolution that is not positive, is returned as it is`() {
        assertThat(Quantize.snap(Double.NaN, constant).isNaN()).isTrue()
        assertThat(Quantize.snap(1_100.0, constant, resolutionBeats = 0.0)).isEqualTo(1_100.0)
        assertThat(Quantize.snap(1_100.0, constant, resolutionBeats = -1.0)).isEqualTo(1_100.0)
    }
}
