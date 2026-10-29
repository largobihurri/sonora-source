package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeekHoldTest {

    private val ms = 1_000_000L

    @Test
    fun `with no seek pending the position passes through`() {
        val hold = SeekHold()
        assertThat(hold.active).isFalse()
        assertThat(hold.resolve(positionMs = 1_234.0, nowNanos = 0L)).isEqualTo(1_234.0)
    }

    @Test
    fun `the target is drawn until the player gets there`() {
        val hold = SeekHold()
        hold.hold(targetMs = 30_000.0, nowNanos = 0L)
        // The seek has not landed yet: the old position would make the waveform jump back.
        assertThat(hold.resolve(positionMs = 10_000.0, nowNanos = 16 * ms)).isEqualTo(30_000.0)
        assertThat(hold.active).isTrue()
        // Landed: the player position is drawn from now on.
        assertThat(hold.resolve(positionMs = 30_040.0, nowNanos = 48 * ms)).isEqualTo(30_040.0)
        assertThat(hold.active).isFalse()
        assertThat(hold.resolve(positionMs = 10_000.0, nowNanos = 64 * ms)).isEqualTo(10_000.0)
    }

    @Test
    fun `the hold gives up after its timeout`() {
        val hold = SeekHold(timeoutNanos = 600 * ms)
        hold.hold(targetMs = 30_000.0, nowNanos = 0L)
        assertThat(hold.resolve(positionMs = 10_000.0, nowNanos = 599 * ms)).isEqualTo(30_000.0)
        assertThat(hold.resolve(positionMs = 10_000.0, nowNanos = 600 * ms)).isEqualTo(10_000.0)
        assertThat(hold.active).isFalse()
    }

    @Test
    fun `a position within the tolerance on either side ends the hold`() {
        val hold = SeekHold(toleranceMs = 150.0)
        hold.hold(targetMs = 30_000.0, nowNanos = 0L)
        assertThat(hold.resolve(positionMs = 29_850.0, nowNanos = ms)).isEqualTo(29_850.0)
    }

    @Test
    fun `cancel drops a pending hold`() {
        val hold = SeekHold()
        hold.hold(targetMs = 30_000.0, nowNanos = 0L)
        hold.cancel()
        assertThat(hold.resolve(positionMs = 10_000.0, nowNanos = ms)).isEqualTo(10_000.0)
    }

    @Test
    fun `a NaN position keeps the target while the hold lasts`() {
        val hold = SeekHold()
        hold.hold(targetMs = 30_000.0, nowNanos = 0L)
        assertThat(hold.resolve(positionMs = Double.NaN, nowNanos = ms)).isEqualTo(30_000.0)
    }
}
