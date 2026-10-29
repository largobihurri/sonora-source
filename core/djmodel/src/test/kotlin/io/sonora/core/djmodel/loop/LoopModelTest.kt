package io.sonora.core.djmodel.loop

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LoopModelTest {

    @Test
    fun `milliseconds become frames by rounding ms times rate over a thousand`() {
        assertThat(LoopTime.msToFrames(1000.0, 44_100)).isEqualTo(44_100L)
        assertThat(LoopTime.msToFrames(15.625, 48_000)).isEqualTo(750L)
        assertThat(LoopTime.msToFrames(1.0, 44_100)).isEqualTo(44L)
        assertThat(LoopTime.msToFrames(0.5, 44_100)).isEqualTo(22L)
        assertThat(LoopTime.msToFrames(0.0, 48_000)).isEqualTo(0L)
    }

    @Test
    fun `a tie rounds to the later frame`() {
        // 2.5 frames at 1 kHz.
        assertThat(LoopTime.msToFrames(2.5, 1000)).isEqualTo(3L)
    }

    @Test
    fun `frames become milliseconds`() {
        assertThat(LoopTime.framesToMs(44_100, 44_100)).isEqualTo(1000.0)
        assertThat(LoopTime.framesToMs(750, 48_000)).isEqualTo(15.625)
    }

    @Test
    fun `a loop converts to frames at the sample rate`() {
        val frames = Loop(1300.0, 3300.0).toFrames(48_000)

        assertThat(frames).isEqualTo(LoopFrames(62_400, 158_400))
        assertThat(frames.length).isEqualTo(96_000L)
    }

    @Test
    fun `a loop has a length and must end after it starts`() {
        assertThat(Loop(100.0, 350.0).lengthMs).isEqualTo(250.0)
        assertThrows(IllegalArgumentException::class.java) { Loop(100.0, 100.0) }
        assertThrows(IllegalArgumentException::class.java) { Loop(-1.0, 100.0) }
        assertThrows(IllegalArgumentException::class.java) { Loop(0.0, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { LoopFrames(10, 10) }
        assertThrows(IllegalArgumentException::class.java) { LoopFrames(-1, 10) }
    }

    @Test
    fun `the beat sizes run from one thirty second to thirty two beats`() {
        assertThat(LoopSizes.BEATS).hasSize(11)
        assertThat(LoopSizes.MIN_BEATS).isEqualTo(1.0 / 32)
        assertThat(LoopSizes.MAX_BEATS).isEqualTo(32.0)
        for (i in 1 until LoopSizes.BEATS.size) {
            assertThat(LoopSizes.BEATS[i]).isEqualTo(LoopSizes.BEATS[i - 1] * 2)
        }
    }

    @Test
    fun `halving and doubling a size stop at the ends`() {
        assertThat(LoopSizes.halved(4.0)).isEqualTo(2.0)
        assertThat(LoopSizes.halved(1.0 / 32)).isEqualTo(1.0 / 32)
        assertThat(LoopSizes.doubled(4.0)).isEqualTo(8.0)
        assertThat(LoopSizes.doubled(32.0)).isEqualTo(32.0)
    }
}
