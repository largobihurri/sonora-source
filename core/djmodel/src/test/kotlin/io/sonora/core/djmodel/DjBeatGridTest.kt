package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.BeatGrid
import org.junit.Assert.assertThrows
import org.junit.Test

class DjBeatGridTest {

    private fun constant(bpm: Double = 120.0, startMs: Double = 1300.0, beatInBar: Int = 1) =
        DjBeatGrid(listOf(TempoSegment(startMs, bpm, beatInBar)))

    /** 120 BPM from 0, then 100 BPM from 2000 (beat 4 of the first), then 150 BPM from 3800 (beat 4 of the second). */
    private fun threeSegments() = DjBeatGrid(
        listOf(
            TempoSegment(0.0, 120.0, 1),
            TempoSegment(2000.0, 100.0, 1),
            TempoSegment(3800.0, 150.0, 4),
        ),
    )

    @Test
    fun `a grid needs at least one segment`() {
        assertThrows(IllegalArgumentException::class.java) { DjBeatGrid(emptyList()) }
    }

    @Test
    fun `segment starts must increase`() {
        val a = TempoSegment(0.0, 120.0, 1)
        assertThrows(IllegalArgumentException::class.java) { DjBeatGrid(listOf(a, TempoSegment(0.0, 120.0, 1))) }
        assertThrows(IllegalArgumentException::class.java) {
            DjBeatGrid(listOf(TempoSegment(2000.0, 120.0, 1), TempoSegment(1000.0, 120.0, 1)))
        }
    }

    @Test
    fun `a segment must start on a beat of the previous one`() {
        // 120 BPM from 0: beats every 500 ms, so 2100 is off the grid.
        assertThrows(IllegalArgumentException::class.java) {
            DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(2100.0, 100.0, 1)))
        }
        // A start less than one beat after the previous one is also off the grid.
        assertThrows(IllegalArgumentException::class.java) {
            DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(250.0, 100.0, 1)))
        }
    }

    @Test
    fun `the boundary tolerance is one microsecond`() {
        DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(2000.0005, 100.0, 1)))
        assertThrows(IllegalArgumentException::class.java) {
            DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(2000.002, 100.0, 1)))
        }
    }

    @Test
    fun `segment values are validated`() {
        assertThrows(IllegalArgumentException::class.java) { TempoSegment(0.0, 0.0, 1) }
        assertThrows(IllegalArgumentException::class.java) { TempoSegment(0.0, -120.0, 1) }
        assertThrows(IllegalArgumentException::class.java) { TempoSegment(0.0, Double.NaN, 1) }
        assertThrows(IllegalArgumentException::class.java) { TempoSegment(-1.0, 120.0, 1) }
        assertThrows(IllegalArgumentException::class.java) { TempoSegment(Double.POSITIVE_INFINITY, 120.0, 1) }
        assertThrows(IllegalArgumentException::class.java) { TempoSegment(0.0, 120.0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TempoSegment(0.0, 120.0, 5) }
    }

    @Test
    fun `isConstant is true only for a single segment`() {
        assertThat(constant().isConstant).isTrue()
        assertThat(threeSegments().isConstant).isFalse()
    }

    @Test
    fun `bpmAt follows the segment that holds the time`() {
        val grid = threeSegments()

        assertThat(grid.bpmAt(-50.0)).isEqualTo(120.0)
        assertThat(grid.bpmAt(0.0)).isEqualTo(120.0)
        assertThat(grid.bpmAt(1999.9)).isEqualTo(120.0)
        assertThat(grid.bpmAt(2000.0)).isEqualTo(100.0)
        assertThat(grid.bpmAt(3799.0)).isEqualTo(100.0)
        assertThat(grid.bpmAt(3800.0)).isEqualTo(150.0)
        assertThat(grid.bpmAt(1.0e9)).isEqualTo(150.0)
    }

    @Test
    fun `beats of a constant grid reach back to the start of the track`() {
        // Anchor: beat 1 of a bar at 1300 ms; beats every 500 ms -> 800 (beat 4), 300 (beat 3).
        val beats = constant().beats(3000.0)

        assertThat(beats.map { it.timeMs }).containsExactly(300.0, 800.0, 1300.0, 1800.0, 2300.0, 2800.0).inOrder()
        assertThat(beats.map { it.beatInBar }).containsExactly(3, 4, 1, 2, 3, 4).inOrder()
        assertThat(beats.map { it.bpm }.toSet()).containsExactly(120.0)
    }

    @Test
    fun `beats end before the duration`() {
        assertThat(constant(startMs = 0.0).beats(1500.0).map { it.timeMs }).containsExactly(0.0, 500.0, 1000.0).inOrder()
        assertThat(constant(startMs = 0.0).beats(0.0)).isEmpty()
        assertThat(constant(startMs = 0.0).beats(1500.5).map { it.timeMs }).containsExactly(0.0, 500.0, 1000.0, 1500.0).inOrder()
    }

    @Test
    fun `beats of a constant grid match the constant beat grid`() {
        val grid = constant(bpm = 127.3, startMs = 421.7, beatInBar = 2)
        val expected = BeatGrid.spanning(127.3, 421.7, 2, 90_000.0)

        assertThat(grid.toConstantBeatGrid(90_000.0)).isEqualTo(expected)
        val beats = grid.beats(90_000.0)
        assertThat(beats).hasSize(expected.beatCount)
        expected.beats().forEachIndexed { i, beat ->
            assertThat(beats[i].timeMs).isWithin(1e-6).of(beat.timeMs)
            assertThat(beats[i].beatInBar).isEqualTo(beat.beatInBar)
        }
    }

    @Test
    fun `beats across segment boundaries keep each segment's tempo and bar position`() {
        val beats = threeSegments().beats(5000.0)

        assertThat(beats.map { it.timeMs }).containsExactly(
            0.0, 500.0, 1000.0, 1500.0, // 120 BPM; 2000 belongs to the next segment
            2000.0, 2600.0, 3200.0, // 100 BPM
            3800.0, 4200.0, 4600.0, // 150 BPM; 5000 is the end
        ).inOrder()
        assertThat(beats.map { it.beatInBar }).containsExactly(1, 2, 3, 4, 1, 2, 3, 4, 1, 2).inOrder()
        assertThat(beats.map { it.bpm }).containsExactly(
            120.0, 120.0, 120.0, 120.0, 100.0, 100.0, 100.0, 150.0, 150.0, 150.0,
        ).inOrder()
    }

    @Test
    fun `a boundary within the tolerance neither duplicates nor drops a beat`() {
        val grid = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(2000.0005, 100.0, 1)))

        val times = grid.beats(3000.0).map { it.timeMs }

        assertThat(times).containsExactly(0.0, 500.0, 1000.0, 1500.0, 2000.0005, 2600.0005).inOrder()
    }

    @Test
    fun `a grid with several segments has no constant beat grid`() {
        assertThat(threeSegments().toConstantBeatGrid(5000.0)).isNull()
    }

    @Test
    fun `a grid whose duration is shorter than its later segments only reports the segments in range`() {
        val times = threeSegments().beats(2100.0).map { it.timeMs }

        assertThat(times).containsExactly(0.0, 500.0, 1000.0, 1500.0, 2000.0).inOrder()
    }

    // --- phrases

    private fun phraseStartTimes(grid: DjBeatGrid, upTo: Double): List<Double> {
        val beats = grid.beats(upTo)
        val flags = grid.phraseStarts(beats)
        return beats.filterIndexed { i, _ -> flags[i] }.map { it.timeMs }
    }

    @Test
    fun `a segment starts in the first bar of a phrase unless it says otherwise`() {
        assertThat(TempoSegment(0.0, 120.0, 1).barInPhraseAtStart).isEqualTo(1)
        assertThat(TempoSegment(0.0, 120.0, 1, barInPhraseAtStart = 4).barInPhraseAtStart).isEqualTo(4)
        assertThrows(IllegalArgumentException::class.java) { TempoSegment(0.0, 120.0, 1, barInPhraseAtStart = 0) }
        assertThrows(IllegalArgumentException::class.java) { TempoSegment(0.0, 120.0, 1, barInPhraseAtStart = 5) }
    }

    @Test
    fun `the bar in the phrase takes part in equality`() {
        assertThat(TempoSegment(0.0, 120.0, 1)).isEqualTo(TempoSegment(0.0, 120.0, 1, barInPhraseAtStart = 1))
        assertThat(TempoSegment(0.0, 120.0, 1)).isNotEqualTo(TempoSegment(0.0, 120.0, 1, barInPhraseAtStart = 2))
        assertThat(TempoSegment(0.0, 120.0, 1).hashCode()).isEqualTo(TempoSegment(0.0, 120.0, 1, barInPhraseAtStart = 1).hashCode())
    }

    @Test
    fun `phrases start on the first downbeat of the track and every fourth one after it`() {
        // 120 BPM, downbeat at 1300: bars every 2000 ms, phrases every 8000 ms.
        assertThat(phraseStartTimes(constant(), 20_000.0)).containsExactly(1_300.0, 9_300.0, 17_300.0).inOrder()
    }

    @Test
    fun `the first downbeat before the first segment start counts, the grid runs back to the track start`() {
        assertThat(phraseStartTimes(constant(startMs = 2_300.0), 12_000.0)).containsExactly(300.0, 8_300.0).inOrder()
    }

    @Test
    fun `an offset says which bar of its phrase the first downbeat is`() {
        val grid = DjBeatGrid(listOf(TempoSegment(1_300.0, 120.0, 1, barInPhraseAtStart = 3)))

        // Bars at 1300 (3), 3300 (4), 5300 (1).
        assertThat(phraseStartTimes(grid, 14_000.0)).containsExactly(5_300.0, 13_300.0).inOrder()
    }

    @Test
    fun `the phrase count goes on across tempo segments and only the first segment's offset counts`() {
        // 120 BPM: bars at 0, 2000, 4000, 6000 (phrase), 8000 ... then 100 BPM from 8000: bars every 2400 ms.
        val grid = DjBeatGrid(
            listOf(
                TempoSegment(0.0, 120.0, 1),
                TempoSegment(8_000.0, 100.0, 1, barInPhraseAtStart = 3),
            ),
        )

        assertThat(phraseStartTimes(grid, 30_000.0)).containsExactly(0.0, 8_000.0, 17_600.0, 27_200.0).inOrder()
    }
}
