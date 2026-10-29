package io.sonora.core.djengine.beats

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToLong
import org.junit.Test

/** Tests of [GridFitter.fit]: the constant fit first, then segmentation of tracks whose tempo changes. */
class VariableTempoTest {

    private val durationMs = 120_000.0

    /** True beat times, with the bar position (1..4) of each (beat 0 is the downbeat). */
    private class Truth(val beats: List<Double>) {
        val downbeats: List<Double> get() = beats.filterIndexed { i, _ -> i % 4 == 0 }
    }

    /** Beats whose tempo moves linearly from [bpmStart] to [bpmEnd] across [lengthMs], starting at [firstMs]. */
    private fun ramp(bpmStart: Double, bpmEnd: Double, firstMs: Double, lengthMs: Double): Truth {
        val beats = ArrayList<Double>()
        var t = firstMs
        while (t < firstMs + lengthMs) {
            beats += t
            val bpm = bpmStart + (bpmEnd - bpmStart) * (t - firstMs) / lengthMs
            t += 60_000.0 / bpm
        }
        return Truth(beats)
    }

    /** Beats of [tempos] in turn: each `(bpm, beats)` pair holds that tempo for that many beats. */
    private fun steps(firstMs: Double, vararg tempos: Pair<Double, Int>): Truth {
        val beats = ArrayList<Double>()
        var t = firstMs
        for ((bpm, count) in tempos) {
            repeat(count) {
                beats += t
                t += 60_000.0 / bpm
            }
        }
        return Truth(beats)
    }

    private fun jitter(values: List<Double>, ms: Double, seed: Long): List<Double> {
        val random = Random(seed)
        return values.map { it + (random.nextDouble() * 2 - 1) * ms }
    }

    private fun candidates(truth: Truth, jitterMs: Double = 0.0, seed: Long = 1): BeatCandidates {
        val beats = jitter(truth.beats, jitterMs, seed)
        val downbeats = beats.filterIndexed { i, _ -> i % 4 == 0 }
        return BeatCandidates(beats.toDoubleArray(), downbeats.toDoubleArray())
    }

    private fun fit(candidates: BeatCandidates, duration: Double = durationMs, options: GridFitOptions = GridFitOptions()) =
        GridFitter.fit(candidates, duration, TempoPrior.DEFAULT, options)

    /** The largest distance between a true beat and the nearest beat of [grid]; also checks each beat's bar position. */
    private fun worstError(grid: DjBeatGrid, truth: Truth, duration: Double = durationMs): Double {
        val beats = grid.beats(duration)
        var worst = 0.0
        for (i in truth.beats.indices) {
            val nearest = beats.minByOrNull { abs(it.timeMs - truth.beats[i]) }!!
            worst = maxOf(worst, abs(nearest.timeMs - truth.beats[i]))
        }
        return worst
    }

    private fun assertBarsMatch(grid: DjBeatGrid, truth: Truth, duration: Double = durationMs) {
        val beats = grid.beats(duration)
        for (i in truth.beats.indices) {
            val nearest = beats.minByOrNull { abs(it.timeMs - truth.beats[i]) }!!
            assertThat(nearest.beatInBar).isEqualTo(i % 4 + 1)
        }
    }

    private fun assertInvariants(grid: DjBeatGrid) {
        val segments = grid.segments
        for (i in 1 until segments.size) {
            val previous = segments[i - 1]
            val next = segments[i]
            assertThat(next.startMs).isGreaterThan(previous.startMs)
            val beats = (next.startMs - previous.startMs) / previous.beatLengthMs
            assertThat(abs(beats - beats.roundToLong()) * previous.beatLengthMs).isAtMost(DjBeatGrid.BOUNDARY_TOLERANCE_MS)
        }
        for (segment in segments) {
            assertThat(segment.beatInBarAtStart).isIn(1..4)
            assertThat(segment.bpm).isGreaterThan(0.0)
        }
    }

    @Test
    fun `a 120 to 124 BPM ramp gives segments whose beats are within 5 ms of the true beats`() {
        val truth = ramp(120.0, 124.0, 400.0, durationMs - 1_000)

        val grid = fit(candidates(truth))!!

        assertThat(grid.segments.size).isGreaterThan(1)
        assertThat(worstError(grid, truth)).isLessThan(5.0)
        assertThat(grid.bpmAt(1_000.0)).isWithin(0.6).of(120.0)
        assertThat(grid.bpmAt(durationMs - 2_000)).isWithin(0.6).of(124.0)
    }

    @Test
    fun `the beats of a ramp match the truth over the whole track and number the same`() {
        val truth = ramp(120.0, 124.0, 400.0, durationMs - 1_000)

        val grid = fit(candidates(truth))!!
        val lastTruth = truth.beats.last()
        val inRange = grid.beats(durationMs).filter { it.timeMs >= truth.beats.first() - 1.0 && it.timeMs <= lastTruth + 1.0 }

        assertThat(inRange.size).isEqualTo(truth.beats.size)
        for (i in truth.beats.indices) assertThat(abs(inRange[i].timeMs - truth.beats[i])).isLessThan(5.0)
        assertBarsMatch(grid, truth)
    }

    @Test
    fun `a downward ramp is followed too`() {
        val truth = ramp(140.0, 134.0, 250.0, durationMs - 1_000)

        val grid = fit(candidates(truth))!!

        assertThat(grid.segments.size).isGreaterThan(1)
        assertThat(worstError(grid, truth)).isLessThan(5.0)
    }

    @Test
    fun `a constant track still gives exactly one segment`() {
        val truth = steps(300.0, 128.0 to 250)

        val grid = fit(candidates(truth))!!

        assertThat(grid.segments).hasSize(1)
        assertThat(grid.segments.single().bpm).isEqualTo(128.0)
    }

    @Test
    fun `a constant track gives the same grid as the constant fit`() {
        val truth = steps(300.0, 127.43 to 240)
        val c = candidates(truth, jitterMs = 6.0, seed = 3)

        assertThat(fit(c)).isEqualTo(GridFitter.fitConstant(c, durationMs))
    }

    @Test
    fun `jittered constant input is not over-segmented`() {
        for (seed in 1L..4L) {
            val truth = steps(250.0, 126.0 to 250)

            val grid = fit(candidates(truth, jitterMs = 12.0, seed = seed))!!

            assertThat(grid.segments).hasSize(1)
            assertThat(grid.segments.single().bpm).isEqualTo(126.0)
        }
    }

    @Test
    fun `a constant track with spurious and missing detections is one segment`() {
        val truth = steps(330.0, 124.0 to 240)
        val random = Random(17)
        val kept = jitter(truth.beats, 6.0, 5).filterIndexed { i, _ -> i % 9 != 4 && i % 17 != 3 }
        val noise = List(kept.size / 6) { random.nextDouble() * (durationMs - 1000) + 500 }
        val all = (kept + noise).sorted().fold(ArrayList<Double>()) { acc, t ->
            if (acc.isEmpty() || t - acc.last() > 1.0) acc += t
            acc
        }

        val grid = fit(BeatCandidates(all.toDoubleArray(), DoubleArray(0)))!!

        assertThat(grid.segments).hasSize(1)
        assertThat(grid.segments.single().bpm).isEqualTo(124.0)
    }

    @Test
    fun `a step from 128 to 100 BPM at bar 32 gives two segments with the boundary on the right beat`() {
        val truth = steps(300.0, 128.0 to 128, 100.0 to 100)

        val grid = fit(candidates(truth))!!

        assertThat(grid.segments).hasSize(2)
        assertThat(grid.segments[0].bpm).isEqualTo(128.0)
        assertThat(grid.segments[1].bpm).isEqualTo(100.0)
        // Beat 128 is the first beat of bar 33, where the tempo changes.
        assertThat(grid.segments[1].startMs).isWithin(3.0).of(truth.beats[128])
        assertThat(worstError(grid, truth)).isLessThan(5.0)
    }

    @Test
    fun `the bar phase is continuous across the boundary`() {
        // The change sits mid-bar (beat 130 is the third beat of its bar): the bar count must run on across it.
        val truth = steps(300.0, 128.0 to 130, 100.0 to 90)

        val grid = fit(candidates(truth))!!

        assertThat(grid.segments).hasSize(2)
        assertThat(grid.segments[1].startMs).isWithin(3.0).of(truth.beats[130])
        assertThat(grid.segments[1].beatInBarAtStart).isEqualTo(130 % 4 + 1)
        assertBarsMatch(grid, truth)
    }

    @Test
    fun `a step up from 100 to 128 BPM is found as well`() {
        val truth = steps(500.0, 100.0 to 100, 128.0 to 120)

        val grid = fit(candidates(truth))!!

        assertThat(grid.segments).hasSize(2)
        assertThat(grid.segments[0].bpm).isEqualTo(100.0)
        assertThat(grid.segments[1].bpm).isEqualTo(128.0)
        assertThat(grid.segments[1].startMs).isWithin(3.0).of(truth.beats[100])
        assertThat(worstError(grid, truth)).isLessThan(5.0)
        assertBarsMatch(grid, truth)
    }

    @Test
    fun `jittered steps are not over-segmented`() {
        for (seed in 1L..3L) {
            val truth = steps(300.0, 128.0 to 128, 100.0 to 100)

            val grid = fit(candidates(truth, jitterMs = 8.0, seed = seed))!!

            assertThat(grid.segments).hasSize(2)
            assertThat(grid.segments[0].bpm).isEqualTo(128.0)
            assertThat(grid.segments[1].bpm).isEqualTo(100.0)
            assertThat(worstError(grid, truth)).isLessThan(12.0)
        }
    }

    @Test
    fun `two changes give three segments`() {
        val truth = steps(200.0, 120.0 to 96, 140.0 to 112, 110.0 to 96)

        val grid = fit(candidates(truth), duration = 150_000.0)!!

        assertThat(grid.segments.map { it.bpm }).containsExactly(120.0, 140.0, 110.0).inOrder()
        assertThat(worstError(grid, truth, 150_000.0)).isLessThan(5.0)
        assertBarsMatch(grid, truth, 150_000.0)
    }

    @Test
    fun `a jittered ramp has a bounded number of segments`() {
        val truth = ramp(120.0, 124.0, 400.0, durationMs - 1_000)

        val grid = fit(candidates(truth, jitterMs = 5.0, seed = 9))!!

        assertThat(grid.segments.size).isIn(2..24)
        assertThat(worstError(grid, truth)).isLessThan(12.0)
    }

    @Test
    fun `doubled detections do not stop a step from being found`() {
        val truth = steps(300.0, 128.0 to 128, 100.0 to 100)
        val random = Random(4)
        val doubled = (truth.beats.mapIndexed { i, t ->
            val beatMs = if (i < 128) 60_000.0 / 128 else 60_000.0 / 100
            t + beatMs / 2 + (random.nextDouble() * 2 - 1) * 6
        } + truth.beats).sorted()

        val grid = fit(BeatCandidates(doubled.toDoubleArray(), truth.downbeats.toDoubleArray()))!!

        assertThat(grid.segments).hasSize(2)
        assertThat(grid.segments[0].bpm).isEqualTo(128.0)
        assertThat(grid.segments[1].bpm).isEqualTo(100.0)
    }

    @Test
    fun `every result satisfies the grid invariants`() {
        val cases = listOf(
            ramp(120.0, 124.0, 400.0, durationMs - 1_000),
            steps(300.0, 128.0 to 128, 100.0 to 100),
            steps(200.0, 120.0 to 96, 140.0 to 112, 110.0 to 96),
            steps(300.0, 128.0 to 250),
        )
        for (truth in cases) {
            val grid = fit(candidates(truth, jitterMs = 4.0, seed = 2), duration = 150_000.0)!!
            assertInvariants(grid)
        }
    }

    @Test
    fun `too few or irregular detections give no grid`() {
        assertThat(fit(BeatCandidates(DoubleArray(0), DoubleArray(0)))).isNull()
        val random = Random(19)
        var t = 200.0
        val beats = DoubleArray(150) { t += 150 + random.nextDouble() * 1200; t }
        assertThat(fit(BeatCandidates(beats, DoubleArray(0)), duration = t + 1000)).isNull()
    }

    @Test
    fun `fitConstant keeps giving one segment for a ramp`() {
        val truth = ramp(120.0, 124.0, 400.0, durationMs - 1_000)

        val grid = GridFitter.fitConstant(candidates(truth), durationMs)

        assertThat(grid?.segments?.size ?: 1).isEqualTo(1)
    }
}
