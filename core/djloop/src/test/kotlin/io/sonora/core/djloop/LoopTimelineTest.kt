package io.sonora.core.djloop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoopTimelineTest {

    @Test
    fun `live audio maps one to one`() {
        val t = LoopTimeline()
        t.appendLive(sourceStart = 1_000, frames = 500)

        assertThat(t.sourceFrameAt(0)).isEqualTo(1_000L)
        assertThat(t.sourceFrameAt(499)).isEqualTo(1_499L)
        assertThat(t.writtenFrames).isEqualTo(500L)
        assertThat(t.wrapsAt(499)).isEqualTo(0L)
        assertThat(t.isLoopingAt(10)).isFalse()
    }

    @Test
    fun `contiguous live chunks merge and stay linear`() {
        val t = LoopTimeline(capacity = 4)
        for (i in 0 until 1000) t.appendLive(2_000L + i * 100, 100)

        assertThat(t.writtenFrames).isEqualTo(100_000L)
        assertThat(t.sourceFrameAt(99_999)).isEqualTo(101_999L)
        assertThat(t.sourceFrameAt(50_000)).isEqualTo(52_000L)
        assertThat(t.wrapsAt(99_999)).isEqualTo(0L)
    }

    @Test
    fun `a loop maps to in plus the output time modulo the length`() {
        val t = LoopTimeline()
        t.appendLive(0, 1_000)
        t.appendLoop(inFrame = 400, loopLength = 600, startPhase = 0, frames = 2_000)

        // Output 1000 is the first frame of the loop: source = in.
        assertThat(t.sourceFrameAt(999)).isEqualTo(999L)
        assertThat(t.sourceFrameAt(1_000)).isEqualTo(400L)
        assertThat(t.sourceFrameAt(1_599)).isEqualTo(999L)
        assertThat(t.sourceFrameAt(1_600)).isEqualTo(400L)
        assertThat(t.sourceFrameAt(2_999)).isEqualTo(400L + 199)
        assertThat(t.isLoopingAt(1_500)).isTrue()
        assertThat(t.isLoopingAt(999)).isFalse()
    }

    @Test
    fun `the jump into the loop and every repeat count as a wrap`() {
        val t = LoopTimeline()
        t.appendLive(0, 1_000)
        t.appendLoop(400, 600, 0, 2_000)

        assertThat(t.wrapsAt(999)).isEqualTo(0L)
        assertThat(t.wrapsAt(1_000)).isEqualTo(1L)
        assertThat(t.wrapsAt(1_599)).isEqualTo(1L)
        assertThat(t.wrapsAt(1_600)).isEqualTo(2L)
        assertThat(t.wrapsAt(2_999)).isEqualTo(4L)
    }

    @Test
    fun `loop chunks that continue the phase merge`() {
        val t = LoopTimeline(capacity = 4)
        t.appendLive(0, 100)
        var phase = 0L
        for (i in 0 until 500) {
            t.appendLoop(400, 600, phase, 70)
            phase = (phase + 70) % 600
        }

        val total = 500 * 70L
        assertThat(t.writtenFrames).isEqualTo(100 + total)
        assertThat(t.sourceFrameAt(100 + 12_345)).isEqualTo(400 + 12_345 % 600)
        assertThat(t.wrapsAt(100 + total - 1)).isEqualTo(1 + (total - 1) / 600)
    }

    @Test
    fun `after an exit the source continues linearly from where the loop was left`() {
        val t = LoopTimeline()
        t.appendLive(0, 1_000)
        // Loop 400..1000, left 250 frames into the third iteration: source 650.
        t.appendLoop(400, 600, 0, 600 + 600 + 250)
        t.appendLive(sourceStart = 650, frames = 1_000)

        val exit = 1_000L + 1_450
        assertThat(t.sourceFrameAt(exit - 1)).isEqualTo(649L)
        assertThat(t.sourceFrameAt(exit)).isEqualTo(650L)
        assertThat(t.sourceFrameAt(exit + 999)).isEqualTo(1_649L)
        // The exit is not a jump: only the entry and two repeats were.
        assertThat(t.wrapsAt(exit + 999)).isEqualTo(3L)
        assertThat(t.isLoopingAt(exit)).isFalse()
    }

    @Test
    fun `an exit exactly at the end of an iteration continues at the out point`() {
        val t = LoopTimeline()
        t.appendLive(0, 1_000)
        t.appendLoop(400, 600, 0, 1_200)
        t.appendLive(sourceStart = 1_000, frames = 300)

        assertThat(t.sourceFrameAt(2_199)).isEqualTo(999L)
        assertThat(t.sourceFrameAt(2_200)).isEqualTo(1_000L)
        assertThat(t.wrapsAt(2_400)).isEqualTo(2L)
    }

    @Test
    fun `a source that jumps (a seek or a re-anchor) counts as a wrap`() {
        val t = LoopTimeline()
        t.appendLive(0, 100)
        t.appendLive(5_000, 100)

        assertThat(t.sourceFrameAt(99)).isEqualTo(99L)
        assertThat(t.sourceFrameAt(100)).isEqualTo(5_000L)
        assertThat(t.wrapsAt(150)).isEqualTo(1L)
    }

    @Test
    fun `a halved loop that jumps back starts a new stretch with a wrap`() {
        val t = LoopTimeline()
        t.appendLive(0, 1_000)
        t.appendLoop(400, 600, 0, 700)
        // Halved to 300 while at phase 700 mod 600 = 100: the repeat restarts from the in point.
        t.appendLoop(400, 300, 0, 600)

        assertThat(t.sourceFrameAt(1_700)).isEqualTo(400L)
        assertThat(t.sourceFrameAt(1_999)).isEqualTo(699L)
        assertThat(t.sourceFrameAt(2_000)).isEqualTo(400L)
        assertThat(t.wrapsAt(1_700)).isEqualTo(3L)
        assertThat(t.wrapsAt(2_000)).isEqualTo(4L)
    }

    @Test
    fun `dropping old stretches keeps the counts of the recent ones`() {
        val t = LoopTimeline(capacity = 3)
        var out = 0L
        for (i in 0 until 10) {
            t.appendLive(i * 10_000L, 100)
            out += 100
        }

        assertThat(t.wrapsAt(out - 1)).isEqualTo(9L)
        assertThat(t.sourceFrameAt(out - 1)).isEqualTo(9 * 10_000L + 99)
        assertThat(t.sourceFrameAt(out - 150)).isEqualTo(8 * 10_000L + 50)
    }

    @Test
    fun `asking beyond what was written extrapolates the last stretch`() {
        val t = LoopTimeline()
        t.appendLive(100, 50)

        assertThat(t.sourceFrameAt(80)).isEqualTo(180L)
    }

    @Test
    fun `reset forgets everything`() {
        val t = LoopTimeline()
        t.appendLive(100, 50)
        t.appendLoop(0, 10, 0, 100)

        t.reset()
        t.appendLive(7_000, 10)

        assertThat(t.writtenFrames).isEqualTo(10L)
        assertThat(t.sourceFrameAt(0)).isEqualTo(7_000L)
        assertThat(t.wrapsAt(9)).isEqualTo(0L)
    }
}
