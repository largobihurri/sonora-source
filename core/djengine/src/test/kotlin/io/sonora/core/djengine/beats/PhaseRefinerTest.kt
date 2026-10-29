package io.sonora.core.djengine.beats

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.fixtures.SyntheticAudio
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.OnsetEnvelope
import io.sonora.core.djmodel.TempoSegment
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class PhaseRefinerTest {

    private val rate = OnsetEnvelopeSink.DEFAULT_SAMPLE_RATE_HZ

    private fun envelopeOf(audio: FloatArray): OnsetEnvelope {
        val sink = OnsetEnvelopeSink().create()
        sink.accept(audio, 0, audio.size)
        return sink.finish()
    }

    private fun constantGrid(startMs: Double, bpm: Double = 120.0, beatInBar: Int = 1) =
        DjBeatGrid(listOf(TempoSegment(startMs, bpm, beatInBar)))

    /** An envelope that is [floor] everywhere except for a unit triangle (3 frames wide) at each of [timesMs]. */
    private fun spikes(timesMs: DoubleArray, durationMs: Double, fps: Double = 500.0, floor: Float = 0f): OnsetEnvelope {
        val values = FloatArray((durationMs * fps / 1000).toInt()) { floor }
        for (t in timesMs) {
            val centre = (t * fps / 1000).toInt()
            for (d in -1..1) values.getOrNull(centre + d)?.let { values[centre + d] = maxOf(it, 1f - 0.5f * abs(d)) }
        }
        return OnsetEnvelope(values, fps)
    }

    @Test
    fun `a grid 15 ms late on a click track is pulled back onto the clicks`() {
        val envelope = envelopeOf(SyntheticAudio.clickTrack(120.0, 30_000.0, rate, firstBeatMs = 500.0))
        val refined = PhaseRefiner.refine(constantGrid(515.0), envelope)

        assertThat(refined.segments).hasSize(1)
        assertThat(abs(refined.segments[0].startMs - 500.0)).isLessThan(1.0)
        assertThat(refined.segments[0].bpm).isEqualTo(120.0)
        assertThat(refined.segments[0].beatInBarAtStart).isEqualTo(1)
    }

    @Test
    fun `a grid 20 ms early on a click track is pushed onto the clicks`() {
        val envelope = envelopeOf(SyntheticAudio.clickTrack(120.0, 30_000.0, rate, firstBeatMs = 1_000.0))
        val refined = PhaseRefiner.refine(constantGrid(980.0), envelope)

        assertThat(abs(refined.segments[0].startMs - 1_000.0)).isLessThan(1.0)
    }

    @Test
    fun `a grid already on the onsets is unchanged within half a millisecond`() {
        val envelope = envelopeOf(SyntheticAudio.clickTrack(120.0, 30_000.0, rate, firstBeatMs = 500.0))
        val refined = PhaseRefiner.refine(constantGrid(500.0), envelope)

        assertThat(abs(refined.segments[0].startMs - 500.0)).isLessThan(0.5)
        assertThat(refined.segments[0].bpm).isEqualTo(120.0)
    }

    @Test
    fun `other offsets, tempos and a non-integer sample position are corrected too`() {
        for ((bpm, offset) in listOf(100.0 to 33.3, 140.0 to -37.7, 128.0 to 7.1, 174.0 to -12.9)) {
            val first = 600.0
            val envelope = envelopeOf(SyntheticAudio.clickTrack(bpm, 30_000.0, rate, firstBeatMs = first))
            val refined = PhaseRefiner.refine(constantGrid(first + offset, bpm), envelope)

            assertThat(abs(refined.segments[0].startMs - first)).isLessThan(1.0)
        }
    }

    @Test
    fun `accented clicks do not pull the phase`() {
        val envelope = envelopeOf(SyntheticAudio.clickTrack(120.0, 30_000.0, rate, firstBeatMs = 500.0, accentEvery = 4))
        val refined = PhaseRefiner.refine(constantGrid(512.0), envelope)

        assertThat(abs(refined.segments[0].startMs - 500.0)).isLessThan(1.0)
    }

    @Test
    fun `an offset beyond the search window is not chased`() {
        val envelope = envelopeOf(SyntheticAudio.clickTrack(120.0, 30_000.0, rate, firstBeatMs = 500.0))
        // 60 ms off with a 40 ms window: no beat of the grid is within reach of a click, so nothing to learn.
        val refined = PhaseRefiner.refine(constantGrid(560.0), envelope, maxShiftMs = 40.0)

        assertThat(abs(refined.segments[0].startMs - 560.0)).isAtMost(40.0)
    }

    @Test
    fun `a wider window reaches a bigger offset`() {
        val envelope = envelopeOf(SyntheticAudio.clickTrack(120.0, 30_000.0, rate, firstBeatMs = 500.0))
        val refined = PhaseRefiner.refine(constantGrid(560.0), envelope, maxShiftMs = 100.0)

        assertThat(abs(refined.segments[0].startMs - 500.0)).isLessThan(1.0)
    }

    @Test
    fun `never shifts more than half a beat even when the window allows more`() {
        // 300 BPM: a beat is 200 ms. The spikes sit 130 ms after the grid, further than half a beat (100 ms), so the
        // nearest spike for a shift of 130 ms is also reachable as -70 ms from the next beat: either way at most 100.
        val grid = constantGrid(1_000.0, bpm = 300.0)
        val envelope = spikes(DoubleArray(100) { 1_130.0 + 200.0 * it }, 20_000.0)
        val refined = PhaseRefiner.refine(grid, envelope, maxShiftMs = 5_000.0)

        assertThat(abs(refined.segments[0].startMs - 1_000.0)).isAtMost(100.0 + 1e-9)
    }

    @Test
    fun `adversarial envelopes at exactly half a beat stay within half a beat`() {
        val grid = constantGrid(1_000.0, bpm = 120.0)
        val envelope = spikes(DoubleArray(40) { 1_250.0 + 500.0 * it }, 20_000.0)
        val refined = PhaseRefiner.refine(grid, envelope, maxShiftMs = 10_000.0)

        assertThat(abs(refined.segments[0].startMs - 1_000.0)).isAtMost(250.0 + 1e-9)
    }

    @Test
    fun `random envelopes never move a grid more than half a beat`() {
        val random = Random(42)
        repeat(20) {
            val bpm = 90.0 + random.nextInt(90)
            val grid = constantGrid(300.0 + random.nextInt(400), bpm)
            val values = FloatArray(10_000) { if (random.nextInt(25) == 0) random.nextFloat() else 0f }
            val refined = PhaseRefiner.refine(grid, OnsetEnvelope(values, 500.0), maxShiftMs = 10_000.0, minContrast = 1.0)

            val shift = refined.segments[0].startMs - grid.segments[0].startMs
            assertThat(abs(shift)).isAtMost(grid.segments[0].beatLengthMs / 2 + 1e-9)
        }
    }

    @Test
    fun `silence leaves the grid unchanged`() {
        val grid = constantGrid(515.0)
        val refined = PhaseRefiner.refine(grid, envelopeOf(FloatArray(rate * 20)))

        assertThat(refined).isEqualTo(grid)
    }

    @Test
    fun `a flat or noisy envelope with no clear peak leaves the grid unchanged`() {
        val grid = constantGrid(515.0)
        val flat = OnsetEnvelope(FloatArray(10_000) { 1f }, 500.0)
        val random = Random(7)
        val noise = OnsetEnvelope(FloatArray(10_000) { random.nextFloat() }, 500.0)

        assertThat(PhaseRefiner.refine(grid, flat)).isEqualTo(grid)
        assertThat(PhaseRefiner.refine(grid, noise)).isEqualTo(grid)
    }

    @Test
    fun `an empty or tiny envelope leaves the grid unchanged`() {
        val grid = constantGrid(515.0)

        assertThat(PhaseRefiner.refine(grid, OnsetEnvelope(FloatArray(0), 500.0))).isEqualTo(grid)
        assertThat(PhaseRefiner.refine(grid, OnsetEnvelope(FloatArray(3) { 1f }, 500.0))).isEqualTo(grid)
    }

    @Test
    fun `a multi-segment grid is refined segment by segment and keeps its boundaries on beats`() {
        // Truth: 120 BPM clicks from 1000 ms for 20 beats; then, after a 12 ms hiccup, 100 BPM clicks.
        val first = 1_000.0
        val boundaryTruth = first + 20 * 500.0 + 12.0
        val times = DoubleArray(20) { first + 500.0 * it } +
            DoubleArray(25) { boundaryTruth + 600.0 * it }
        val envelope = envelopeOf(SyntheticAudio.clicks(times, 40_000.0, rate))
        // Both segments are 15 ms late in the input grid; the boundary is on a beat of the first segment.
        val grid = DjBeatGrid(
            listOf(
                TempoSegment(first + 15.0, 120.0, 1),
                TempoSegment(first + 15.0 + 20 * 500.0, 100.0, 3),
            ),
        )

        val refined = PhaseRefiner.refine(grid, envelope)

        assertThat(refined.segments).hasSize(2)
        assertThat(abs(refined.segments[0].startMs - first)).isLessThan(1.0)
        assertThat(abs(refined.segments[1].startMs - boundaryTruth)).isLessThan(1.0)
        assertThat(refined.segments[1].bpm).isEqualTo(100.0)
        assertThat(refined.segments[1].beatInBarAtStart).isEqualTo(3)
        assertThat(refined.segments[0].beatInBarAtStart).isEqualTo(1)
        // The first segment's tempo absorbs the difference so that its 20 beats end exactly on the boundary.
        val beats = refined.segments[0]
        assertThat(beats.startMs + 20 * beats.beatLengthMs).isWithin(1e-6).of(refined.segments[1].startMs)
        assertThat(beats.bpm).isWithin(0.2).of(120.0)
    }

    @Test
    fun `a one-beat segment between refined ones takes the shift of its neighbour`() {
        val first = 1_000.0
        val times = DoubleArray(20) { first + 500.0 * it } // 120 BPM; then a one-beat segment, then 120 BPM again
        val envelope = envelopeOf(SyntheticAudio.clicks(times, 12_000.0, rate))
        val grid = DjBeatGrid(
            listOf(
                TempoSegment(first + 15.0, 120.0, 1),
                TempoSegment(first + 15.0 + 8 * 500.0, 120.0, 1),
                TempoSegment(first + 15.0 + 9 * 500.0, 120.0, 1),
            ),
        )

        val refined = PhaseRefiner.refine(grid, envelope)

        assertThat(refined.segments).hasSize(3)
        for (i in 0..2) {
            assertThat(abs(refined.segments[i].startMs - (grid.segments[i].startMs - 15.0))).isLessThan(1.0)
        }
        for (s in refined.segments) assertThat(s.bpm).isWithin(0.05).of(120.0)
    }

    @Test
    fun `the first beat is moved forward a whole beat when the shift would put it before zero`() {
        // Clicks at 488 + k * 500; the grid starts at 10 ms (beat 1): its beats at 10, 510, ... are 22 ms late, so the
        // refined start would be -12 ms. The first real beat is the grid's second, beat 2 of the bar.
        val envelope = envelopeOf(SyntheticAudio.clickTrack(120.0, 20_000.0, rate, firstBeatMs = 488.0))
        val refined = PhaseRefiner.refine(constantGrid(10.0), envelope)

        assertThat(abs(refined.segments[0].startMs - 488.0)).isLessThan(1.0)
        assertThat(refined.segments[0].beatInBarAtStart).isEqualTo(2)
    }

    @Test
    fun `beats of the grid before its first segment start also count`() {
        // The grid starts at 4000 ms but the first click is at 500 ms: the beats before the start are on the clicks too.
        val envelope = envelopeOf(SyntheticAudio.clickTrack(120.0, 10_000.0, rate, firstBeatMs = 500.0))
        val refined = PhaseRefiner.refine(constantGrid(4_015.0), envelope)

        assertThat(abs(refined.segments[0].startMs - 4_000.0)).isLessThan(1.0)
    }

    @Test
    fun `bad parameters are rejected`() {
        val grid = constantGrid(500.0)
        val envelope = OnsetEnvelope(FloatArray(1_000), 500.0)

        assertThrows(IllegalArgumentException::class.java) { PhaseRefiner.refine(grid, envelope, maxShiftMs = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { PhaseRefiner.refine(grid, envelope, maxShiftMs = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { PhaseRefiner.refine(grid, envelope, minContrast = 0.0) }
    }

}
