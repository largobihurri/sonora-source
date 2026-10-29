package io.sonora.core.domain.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BeatGridTest {

    @Test
    fun `beat length follows the tempo`() {
        assertThat(BeatGrid(120.0, 0.0, 1, 0).beatLengthMs).isEqualTo(500.0)
        assertThat(BeatGrid(128.0, 0.0, 1, 0).beatLengthMs).isWithin(1e-9).of(468.75)
    }

    @Test
    fun `beats advance by one beat length and cycle through the bar from the first beat's position`() {
        val grid = BeatGrid(bpm = 120.0, firstBeatMs = 250.0, firstBeatInBar = 3, beatCount = 6)

        assertThat(grid.beats()).containsExactly(
            Beat(250.0, 3), Beat(750.0, 4), Beat(1250.0, 1),
            Beat(1750.0, 2), Beat(2250.0, 3), Beat(2750.0, 4),
        ).inOrder()
        assertThat(grid.beatAt(2)).isEqualTo(Beat(1250.0, 1))
        assertThat(grid.beatInBarAt(-1)).isEqualTo(2)
        assertThat(grid.beatInBarAt(100)).isEqualTo(3)
    }

    @Test
    fun `beatAt rejects indices outside the grid`() {
        val grid = BeatGrid(120.0, 0.0, 1, 4)
        assertThrows(IndexOutOfBoundsException::class.java) { grid.beatAt(-1) }
        assertThrows(IndexOutOfBoundsException::class.java) { grid.beatAt(4) }
    }

    @Test
    fun `spanning moves an anchor beat to the earliest beat at or after the start`() {
        // Anchor: beat 1 of a bar at 1300 ms; beats every 500 ms -> 800 (4), 300 (3).
        val grid = BeatGrid.spanning(bpm = 120.0, anchorMs = 1300.0, anchorBeatInBar = 1, durationMs = 3000.0)

        assertThat(grid.firstBeatMs).isWithin(1e-9).of(300.0)
        assertThat(grid.firstBeatInBar).isEqualTo(3)
        assertThat(grid.beatCount).isEqualTo(6) // 300, 800, ..., 2800
        assertThat(grid.beatAt(2).beatInBar).isEqualTo(1)
        assertThat(grid.beatAt(2).timeMs).isWithin(1e-9).of(1300.0)
    }

    @Test
    fun `spanning accepts an anchor before the start`() {
        val grid = BeatGrid.spanning(bpm = 120.0, anchorMs = -900.0, anchorBeatInBar = 2, durationMs = 1000.0)

        // -900 (2), -400 (3), 100 (4), 600 (1)
        assertThat(grid.firstBeatMs).isWithin(1e-9).of(100.0)
        assertThat(grid.firstBeatInBar).isEqualTo(4)
        assertThat(grid.beatCount).isEqualTo(2)
    }

    @Test
    fun `spanning counts only beats strictly before the end`() {
        assertThat(BeatGrid.spanning(120.0, 0.0, 1, 1000.0).beatCount).isEqualTo(2)
        assertThat(BeatGrid.spanning(120.0, 0.0, 1, 1000.5).beatCount).isEqualTo(3)
        assertThat(BeatGrid.spanning(120.0, 0.0, 1, 0.0).beatCount).isEqualTo(0)
        assertThat(BeatGrid.spanning(120.0, 400.0, 1, 300.0).beatCount).isEqualTo(0)
    }

    @Test
    fun `an anchor exactly one beat in lands on zero`() {
        val grid = BeatGrid.spanning(120.0, 500.0, 1, 2000.0)
        assertThat(grid.firstBeatMs).isEqualTo(0.0)
        assertThat(grid.firstBeatInBar).isEqualTo(4)
        assertThat(grid.beatCount).isEqualTo(4)
    }

    @Test
    fun `invalid grids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BeatGrid(0.0, 0.0, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { BeatGrid(Double.NaN, 0.0, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { BeatGrid(120.0, -1.0, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { BeatGrid(120.0, Double.POSITIVE_INFINITY, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { BeatGrid(120.0, 0.0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { BeatGrid(120.0, 0.0, 5, 0) }
        assertThrows(IllegalArgumentException::class.java) { BeatGrid(120.0, 0.0, 1, -1) }
        assertThrows(IllegalArgumentException::class.java) { BeatGrid.spanning(120.0, 0.0, 1, Double.NaN) }
    }

    @Test
    fun `track analysis reports the grid tempo`() {
        val analysis = TrackAnalysis(
            BeatGrid(128.0, 12.0, 1, 10),
            MusicalKey.A_MINOR,
            BlueWaveform(ByteArray(0), ByteArray(BlueWaveform.PREVIEW_COLUMNS), ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS)),
            5_000,
            "test",
        )
        assertThat(analysis.bpm).isEqualTo(128.0)
    }
}
