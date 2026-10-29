package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.rekordbox.anlz.AnlzBeat
import io.sonora.tools.calibration.BarBeat
import org.junit.Test

class ReferenceGridTest {

    @Test
    fun `constant grid gives its beats in bar positions and its tempo`() {
        val beats = (0 until 16).map { AnlzBeat(beatInBar = it % 4 + 1, tempo = 12_800, timeMs = 100L + Math.round(it * 468.75)) }
        val grid = ReferenceGrid.fromAnlz(beats)

        assertThat(grid.beats).hasSize(16)
        assertThat(grid.beats.first()).isEqualTo(BarBeat(100.0, 1))
        assertThat(grid.beats[5]).isEqualTo(BarBeat(100.0 + Math.round(5 * 468.75), 2))
        assertThat(grid.dominantBpm).isEqualTo(128.0)
        assertThat(grid.isVariable).isFalse()
        assertThat(grid.beatTimesMs()).hasLength(16)
    }

    @Test
    fun `the dominant tempo is the one held by the most beats`() {
        val beats = (0 until 10).map { AnlzBeat(1 + it % 4, 17_000, 1_000L * it) } +
            (10 until 40).map { AnlzBeat(1 + it % 4, 17_450, 1_000L * it) } +
            (40 until 45).map { AnlzBeat(1 + it % 4, 17_000, 1_000L * it) }
        val grid = ReferenceGrid.fromAnlz(beats)

        assertThat(grid.dominantBpm).isEqualTo(174.5)
        assertThat(grid.isVariable).isTrue()
    }

    @Test
    fun `an empty grid has no tempo`() {
        val grid = ReferenceGrid.fromAnlz(emptyList())
        assertThat(grid.beats).isEmpty()
        assertThat(grid.dominantBpm.isNaN()).isTrue()
        assertThat(grid.isVariable).isFalse()
    }

    @Test
    fun `our dominant tempo is the segment with the most beats inside the track`() {
        val grid = DjBeatGrid(
            listOf(
                TempoSegment(0.0, 120.0, 1),
                TempoSegment(10_000.0, 128.0, 1),
            ),
        )
        // 20 beats at 120 BPM, then 60 s at 128 BPM = 128 beats.
        assertThat(ReferenceGrid.dominantBpm(grid, 70_000.0)).isEqualTo(128.0)
        // Only 2.5 s of the second segment: the first one wins.
        assertThat(ReferenceGrid.dominantBpm(grid, 12_500.0)).isEqualTo(120.0)
    }
}
