package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import org.junit.Test

class BeatIndexTest {

    private val outTimes = DoubleArray(512)
    private val outBars = ByteArray(512)

    private fun constant(bpm: Double = 120.0, startMs: Double = 0.0, beatInBar: Int = 1, barInPhrase: Int = 1) =
        DjBeatGrid(listOf(TempoSegment(startMs, bpm, beatInBar, barInPhrase)))

    private fun visible(index: BeatIndex, start: Double, end: Double): List<Pair<Double, Int>> {
        val n = index.visible(start, end, outTimes, outBars)
        return (0 until n).map { outTimes[it] to outBars[it].toInt() }
    }

    @Test
    fun `a constant grid lists a beat every 500 ms at 120 bpm with the bar counted 1 to 4`() {
        val index = BeatIndex(constant(), durationMs = 4_000.0)
        assertThat(index.count).isEqualTo(8)
        assertThat(visible(index, 0.0, 4_000.0)).containsExactly(
            0.0 to 1, 500.0 to 2, 1_000.0 to 3, 1_500.0 to 4,
            2_000.0 to 1, 2_500.0 to 2, 3_000.0 to 3, 3_500.0 to 4,
        ).inOrder()
    }

    @Test
    fun `the downbeat flag is beat in bar 1`() {
        val index = BeatIndex(constant(), durationMs = 4_000.0)
        val downbeats = (0 until index.count).filter { index.isDownbeat(it) }.map { index.timeAt(it) }
        assertThat(downbeats).containsExactly(0.0, 2_000.0).inOrder()
        assertThat(index.barPositionAt(1)).isEqualTo(2)
    }

    @Test
    fun `a grid whose first beat is not a downbeat keeps its bar positions`() {
        val index = BeatIndex(constant(startMs = 250.0, beatInBar = 3), durationMs = 2_500.0)
        assertThat(visible(index, 0.0, 2_500.0)).containsExactly(
            250.0 to 3, 750.0 to 4, 1_250.0 to 1, 1_750.0 to 2, 2_250.0 to 3,
        ).inOrder()
    }

    @Test
    fun `beats before the first segment are listed back to the track start`() {
        // Anchored at 1300 ms: 300 and 800 ms come first, as DjBeatGrid reports them.
        val index = BeatIndex(constant(startMs = 1_300.0), durationMs = 3_000.0)
        assertThat(visible(index, 0.0, 3_000.0)).containsExactly(
            300.0 to 3, 800.0 to 4, 1_300.0 to 1, 1_800.0 to 2, 2_300.0 to 3, 2_800.0 to 4,
        ).inOrder()
    }

    @Test
    fun `a variable tempo grid follows each segment and its own bar position`() {
        // 120 bpm for four beats (2000 ms), then 150 bpm (400 ms a beat) restarting the bar at 1.
        val grid = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(2_000.0, 150.0, 1)))
        val index = BeatIndex(grid, durationMs = 4_000.0)
        val expected = grid.beats(4_000.0)
        assertThat(index.count).isEqualTo(expected.size)
        for (i in expected.indices) {
            assertThat(index.timeAt(i)).isEqualTo(expected[i].timeMs)
            assertThat(index.barPositionAt(i)).isEqualTo(expected[i].beatInBar)
        }
        // Spot check: the spacing narrows to 400 ms after the change.
        assertThat(visible(index, 1_500.0, 3_300.0)).containsExactly(
            1_500.0 to 4, 2_000.0 to 1, 2_400.0 to 2, 2_800.0 to 3, 3_200.0 to 4,
        ).inOrder()
    }

    @Test
    fun `a multi segment grid with a bar length change expresses the new bar position`() {
        val grid = DjBeatGrid(
            listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(1_500.0, 120.0, 1), TempoSegment(3_000.0, 90.0, 3)),
        )
        val index = BeatIndex(grid, durationMs = 5_000.0)
        val all = visible(index, 0.0, 5_000.0)
        assertThat(all).containsExactlyElementsIn(grid.beats(5_000.0).map { it.timeMs to it.beatInBar }).inOrder()
        // The bar restarted at 1 at 1500 ms (the previous bar had only three beats).
        assertThat(all).contains(1_500.0 to 1)
    }

    @Test
    fun `the window is half open`() {
        val index = BeatIndex(constant(), durationMs = 4_000.0)
        assertThat(visible(index, 500.0, 1_500.0).map { it.first }).containsExactly(500.0, 1_000.0).inOrder()
        assertThat(visible(index, 501.0, 1_000.0)).isEmpty()
    }

    @Test
    fun `a window at the track start ignores the time before it`() {
        val index = BeatIndex(constant(), durationMs = 4_000.0)
        assertThat(visible(index, -3_000.0, 1_200.0).map { it.first }).containsExactly(0.0, 500.0, 1_000.0).inOrder()
        assertThat(visible(index, -3_000.0, -1.0)).isEmpty()
    }

    @Test
    fun `a window at the track end ignores the time after it`() {
        val index = BeatIndex(constant(), durationMs = 4_000.0)
        assertThat(visible(index, 3_400.0, 9_000.0).map { it.first }).containsExactly(3_500.0)
        assertThat(visible(index, 4_000.0, 9_000.0)).isEmpty()
    }

    @Test
    fun `an inverted or empty window finds nothing`() {
        val index = BeatIndex(constant(), durationMs = 4_000.0)
        assertThat(visible(index, 2_000.0, 2_000.0)).isEmpty()
        assertThat(visible(index, 3_000.0, 1_000.0)).isEmpty()
    }

    @Test
    fun `a window larger than the output is cut at its capacity and reports what it wrote`() {
        val index = BeatIndex(constant(), durationMs = 4_000.0)
        val times = DoubleArray(3)
        val bars = ByteArray(5)
        val n = index.visible(0.0, 4_000.0, times, bars)
        assertThat(n).isEqualTo(3)
        assertThat(times.toList()).containsExactly(0.0, 500.0, 1_000.0).inOrder()
    }

    @Test
    fun `a zero duration track has no beats`() {
        val index = BeatIndex(constant(), durationMs = 0.0)
        assertThat(index.count).isEqualTo(0)
        assertThat(visible(index, 0.0, 1_000.0)).isEmpty()
    }

    @Test
    fun `a long track keeps every beat in order`() {
        val index = BeatIndex(constant(bpm = 128.0), durationMs = 600_000.0)
        assertThat(index.count).isEqualTo(1_280)
        for (i in 1 until index.count) assertThat(index.timeAt(i)).isGreaterThan(index.timeAt(i - 1))
        assertThat(index.barPositionAt(1_279)).isEqualTo(4)
    }

    @Test
    fun `every fourth bar counted from the first downbeat starts a phrase`() {
        // 120 bpm: a bar every 2 s, a phrase every 8 s.
        val index = BeatIndex(constant(), durationMs = 20_000.0)
        val phrases = (0 until index.count).filter { index.isPhraseStart(it) }.map { index.timeAt(it) }
        assertThat(phrases).containsExactly(0.0, 8_000.0, 16_000.0).inOrder()
    }

    @Test
    fun `beats before the first downbeat start no phrase and the count starts at that downbeat`() {
        // The first beat is beat 3 of its bar: the first downbeat is at 1000 ms.
        val index = BeatIndex(constant(startMs = 0.0, beatInBar = 3), durationMs = 18_000.0)
        val phrases = (0 until index.count).filter { index.isPhraseStart(it) }.map { index.timeAt(it) }
        assertThat(phrases).containsExactly(1_000.0, 9_000.0, 17_000.0).inOrder()
    }

    @Test
    fun `the first downbeat can be a later bar of its phrase`() {
        // Bars at 0 (3rd of its phrase), 2 s (4th), 4 s (1st: a phrase start), then every 8 s.
        val index = BeatIndex(constant(barInPhrase = 3), durationMs = 22_000.0)
        val phrases = (0 until index.count).filter { index.isPhraseStart(it) }.map { index.timeAt(it) }
        assertThat(phrases).containsExactly(4_000.0, 12_000.0, 20_000.0).inOrder()
    }

    @Test
    fun `an offset phrase counts from the first downbeat after the beats that precede it`() {
        val index = BeatIndex(constant(startMs = 0.0, beatInBar = 3, barInPhrase = 2), durationMs = 24_000.0)
        val phrases = (0 until index.count).filter { index.isPhraseStart(it) }.map { index.timeAt(it) }
        // Downbeats at 1 s (2nd bar of its phrase), 3 s, 5 s, 7 s (the 1st of the next phrase), then every 8 s.
        assertThat(phrases).containsExactly(7_000.0, 15_000.0, 23_000.0).inOrder()
    }

    @Test
    fun `the phrase count runs on across tempo segments`() {
        // 4 bars at 120 bpm (0..8 s), then 60 bpm from 8 s: a bar every 4 s.
        val grid = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(6_000.0, 60.0, 1)))
        val index = BeatIndex(grid, durationMs = 30_000.0)
        val phrases = (0 until index.count).filter { index.isPhraseStart(it) }.map { index.timeAt(it) }
        // Downbeats at 0, 2, 4, 6 (new segment), 10, 14, 18, 22, 26 s: phrases at the 1st, 5th and 9th.
        assertThat(phrases).containsExactly(0.0, 10_000.0, 26_000.0).inOrder()
    }

    @Test
    fun `visible copies the phrase flags beside the beats`() {
        val index = BeatIndex(constant(), durationMs = 20_000.0)
        val phrases = BooleanArray(512)
        val n = index.visible(7_000.0, 9_000.0, outTimes, outBars, phrases)
        assertThat((0 until n).map { outTimes[it] to phrases[it] }).containsExactly(
            7_000.0 to false, 7_500.0 to false, 8_000.0 to true, 8_500.0 to false,
        ).inOrder()
    }
}
