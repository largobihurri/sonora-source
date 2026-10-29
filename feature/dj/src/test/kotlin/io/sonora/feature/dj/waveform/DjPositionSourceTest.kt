package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.ClockReadFailure
import io.sonora.core.domain.playback.ClockSample
import io.sonora.core.domain.playback.PlaybackClock
import io.sonora.core.domain.playback.PositionSmoother
import kotlin.math.abs
import kotlin.math.roundToLong
import org.junit.Test

class DjPositionSourceTest {

    private class FakeClock : PlaybackClock {
        var readable = true
        var item = 7L
        var position = 0L
        var speed = 1f
        var playing = true
        var reads = 0
        var failure = ClockReadFailure.NO_PLAYER

        override val lastFailure: Int get() = if (readable) ClockReadFailure.NONE else failure

        override fun read(into: ClockSample): Boolean {
            reads++
            if (!readable) return false
            into.queueItemId = item
            into.positionMs = position
            into.speed = speed
            into.playing = playing
            return true
        }
    }

    private val frame = 16_666_667L
    private val leadMs = PositionSmoother.DEFAULT_PRESENT_LEAD_NANOS / 1e6

    @Test
    fun `a playing frame follows the smoothed clock`() {
        val clock = FakeClock()
        val source = DjPositionSource(clock)
        val reference = PositionSmoother()
        val sample = ClockSample()
        for (i in 0 until 10) {
            clock.position = 1_000L + i * 17L
            clock.read(sample)
            val expected = reference.advance(i * frame, sample)
            assertThat(source.frame(i * frame, itemId = 7L, fallbackMs = 0L)).isWithin(1e-9).of(expected)
        }
    }

    @Test
    fun `an unreadable clock falls back to the slow position`() {
        val clock = FakeClock().apply { readable = false }
        val source = DjPositionSource(clock)
        assertThat(source.frame(frame, itemId = 7L, fallbackMs = 4_250L)).isEqualTo(4_250.0)
        assertThat(source.still(itemId = 7L, fallbackMs = 4_500L)).isEqualTo(4_500.0)
    }

    @Test
    fun `a clock on another queue item falls back to the slow position`() {
        val clock = FakeClock().apply { item = 8L; position = 90_000L }
        val source = DjPositionSource(clock)
        assertThat(source.frame(frame, itemId = 7L, fallbackMs = 1_000L)).isEqualTo(1_000.0)
        assertThat(source.still(itemId = 7L, fallbackMs = 1_250L)).isEqualTo(1_250.0)
    }

    @Test
    fun `a clock without a position falls back to the slow position`() {
        val clock = FakeClock().apply { position = -1L }
        val source = DjPositionSource(clock)
        assertThat(source.still(itemId = 7L, fallbackMs = 3_000L)).isEqualTo(3_000.0)
    }

    @Test
    fun `the smoothing starts afresh after a fallback`() {
        val clock = FakeClock()
        val source = DjPositionSource(clock)
        clock.position = 1_000L
        source.frame(0L, itemId = 7L, fallbackMs = 0L)
        clock.position = 1_016L
        source.frame(frame, itemId = 7L, fallbackMs = 0L)
        clock.readable = false
        source.frame(2 * frame, itemId = 7L, fallbackMs = 1_000L)
        clock.readable = true
        // 8 ms behind the prediction (within the 60 ms snap): a continuing smoother would slew, a fresh one takes it.
        clock.position = 1_040L
        assertThat(source.frame(3 * frame, itemId = 7L, fallbackMs = 1_000L)).isWithin(1e-9).of(1_040.0 + leadMs)
    }

    @Test
    fun `a still read is the exact clock position`() {
        val clock = FakeClock().apply { playing = false; position = 61_234L }
        val source = DjPositionSource(clock)
        assertThat(source.still(itemId = 7L, fallbackMs = 61_000L)).isEqualTo(61_234.0)
        assertThat(clock.reads).isEqualTo(1)
    }

    @Test
    fun `reset makes the next playing frame take the reading as it is`() {
        val clock = FakeClock()
        val source = DjPositionSource(clock)
        clock.position = 1_000L
        source.frame(0L, itemId = 7L, fallbackMs = 0L)
        source.reset()
        clock.position = 1_030L
        assertThat(source.frame(frame, itemId = 7L, fallbackMs = 0L)).isWithin(1e-9).of(1_030.0 + leadMs)
    }

    @Test
    fun `a foreign queue entry follows a clock that reads no item id`() {
        // Entries queued without an item id (another controller's) get negative ids in the UI's queue (-1, -2, ...),
        // while the clock reads every such entry as NO_ITEM: the second one used to fall back to the slow position.
        val clock = FakeClock().apply { item = ClockSample.NO_ITEM; position = 5_000L }
        val source = DjPositionSource(clock)
        assertThat(source.frame(frame, itemId = -2L, fallbackMs = 0L)).isWithin(1e-9).of(5_000.0 + leadMs)
        assertThat(source.still(itemId = -3L, fallbackMs = 0L)).isEqualTo(5_000.0)
    }

    @Test
    fun `a clock with an item id never matches a foreign entry`() {
        val clock = FakeClock().apply { item = 7L; position = 5_000L }
        val source = DjPositionSource(clock)
        assertThat(source.frame(frame, itemId = -2L, fallbackMs = 1_000L)).isEqualTo(1_000.0)
    }

    @Test
    fun `a clock refreshed every 125 ms draws evenly through the source`() {
        val clock = FakeClock()
        val source = DjPositionSource(clock)
        val startMs = 40_000.0
        var nextUpdate = 0L
        var previous = Double.NaN
        var worst = 0.0
        for (i in 1..600) {
            val now = i * frame
            while (nextUpdate <= now) {
                clock.position = (startMs + nextUpdate / 1e6).roundToLong()
                nextUpdate += 125_000_000L
            }
            val out = source.frame(now, itemId = 7L, fallbackMs = 0L)
            if (now > 2_000_000_000L) worst = maxOf(worst, abs((out - previous) - 16.667) / 16.667)
            previous = out
        }
        assertThat(worst).isAtMost(0.2)
    }

    @Test
    fun `the stats count reads, refusals by reason, mismatches with both ids and snaps`() {
        val clock = FakeClock().apply { position = 1_000L }
        val stats = DjClockStats()
        val source = DjPositionSource(clock, stats = stats)
        for (i in 0 until 3) {
            clock.position = 1_000L + i * 17L
            source.frame(i * frame, itemId = 7L, fallbackMs = 0L)
        }
        clock.readable = false
        clock.failure = ClockReadFailure.WRONG_THREAD
        source.frame(3 * frame, itemId = 7L, fallbackMs = 1_050L)
        clock.readable = true
        clock.item = 9L
        source.frame(4 * frame, itemId = 7L, fallbackMs = 1_066L)
        clock.position = -1L
        clock.item = 7L
        source.frame(5 * frame, itemId = 7L, fallbackMs = 1_083L)

        assertThat(stats.frames).isEqualTo(6)
        assertThat(stats.readsOk).isEqualTo(3)
        assertThat(stats.readFailures[ClockReadFailure.WRONG_THREAD]).isEqualTo(1)
        assertThat(stats.itemMismatches).isEqualTo(1)
        assertThat(stats.mismatchUiItem).isEqualTo(7L)
        assertThat(stats.mismatchClockItem).isEqualTo(9L)
        assertThat(stats.noPosition).isEqualTo(1)
        assertThat(stats.snaps[PositionSmoother.SNAP_START]).isEqualTo(1)
    }

    @Test
    fun `a still read is not counted as a frame`() {
        val clock = FakeClock().apply { playing = false; position = 2_000L }
        val stats = DjClockStats()
        DjPositionSource(clock, stats = stats).still(itemId = 7L, fallbackMs = 0L)
        assertThat(stats.frames).isEqualTo(0)
        assertThat(stats.readsOk).isEqualTo(0)
    }
}
