package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.ClockReadFailure
import io.sonora.core.domain.playback.PositionSmoother
import org.junit.Test

/** One second of the DJ screen's clock, as the `SonoraDjClock` log line tells it. */
class DjClockStatsTest {

    private val frame = 16_666_667L

    @Test
    fun `a window is due after one second of frames and clear starts the next one`() {
        val stats = DjClockStats()
        stats.restart()
        stats.onFrame(frame, 0.0)
        assertThat(stats.due(frame + 999_000_000L)).isFalse()
        assertThat(stats.due(frame + 1_000_000_000L)).isTrue()

        stats.clear(frame + 1_000_000_000L)
        assertThat(stats.frames).isEqualTo(0)
        assertThat(stats.due(frame + 1_500_000_000L)).isFalse()
        assertThat(stats.due(frame + 2_000_000_000L)).isTrue()
    }

    @Test
    fun `the drawn steps give their largest, smallest and 90th percentile, with back steps and still frames`() {
        val stats = DjClockStats()
        stats.restart()
        val drawn = doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0, 125.0, 125.0, 120.0)
        drawn.forEachIndexed { i, ms -> stats.onFrame(i * frame, ms) }

        // Steps: ten of 10 ms, one of 25, one of 0, one of -5.
        assertThat(stats.maxDeltaMs()).isEqualTo(25.0)
        assertThat(stats.minDeltaMs()).isEqualTo(-5.0)
        assertThat(stats.p90DeltaMs()).isEqualTo(10.0)
        assertThat(stats.backSteps).isEqualTo(1)
        assertThat(stats.stillFrames).isEqualTo(1)
        assertThat(stats.longestFrameGapMs).isWithin(0.001).of(16.667)
    }

    @Test
    fun `fresh readings and the longest stale stretch are measured by frame time`() {
        val stats = DjClockStats()
        stats.restart()
        // A reading that changes on frames 0, 8 and 15 only.
        val readings = longArrayOf(100, 100, 100, 100, 100, 100, 100, 100, 233, 233, 233, 233, 233, 233, 233, 350)
        readings.forEachIndexed { i, ms -> stats.onReadOk(ms, 1f, i * frame) }

        assertThat(stats.freshReadings).isEqualTo(3)
        assertThat(stats.longestStaleMs).isWithin(0.01).of(8 * 16.666667)
    }

    @Test
    fun `the summary names every counter`() {
        val stats = DjClockStats()
        stats.restart()
        stats.onReadFailed(ClockReadFailure.NO_PLAYER)
        stats.onItemMismatch(uiItem = 5L, clockItem = -1L)
        stats.onSnap(PositionSmoother.SNAP_JUMP_BACK)
        stats.onTileMiss()
        stats.onFrame(frame, 1.0)
        stats.onFrame(2 * frame, 18.0)

        val line = stats.summary()
        for (key in listOf(
            "frames=2", "ok=0", "noPlayer=1", "thread=0", "noItem=0", "mismatch=1(ui=5,clock=-1)", "noPos=0", "fresh=0",
            "staleMax=", "back=1", "settle=0", "max=17.0", "p90=17.0", "backSteps=0", "still=0", "gapMax=", "tileMiss=1",
        )) {
            assertThat(line).contains(key)
        }
    }
}
