package io.sonora.core.designsystem.component

import androidx.compose.runtime.MonotonicFrameClock
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

class SegmentedTranslucentTest {

    /** A frame clock that ticks 16 ms per frame and counts the frames it served. */
    private class StepClock : MonotonicFrameClock {
        var frames = 0
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            frames++
            time += 16_000_000L
            return onFrame(time)
        }
    }

    @Test
    fun `with equal segments the thumb covers the chosen one`() {
        val widths = listOf(100f, 100f, 100f)

        assertThat(segmentThumbFrame(0, widths)).isEqualTo(SegmentThumbFrame(0f, 100f))
        assertThat(segmentThumbFrame(1, widths)).isEqualTo(SegmentThumbFrame(100f, 100f))
        assertThat(segmentThumbFrame(2, widths)).isEqualTo(SegmentThumbFrame(200f, 100f))
    }

    @Test
    fun `with unequal segments the thumb starts after the ones before and takes the chosen width`() {
        val widths = listOf(80f, 140f, 61f)

        assertThat(segmentThumbFrame(0, widths)).isEqualTo(SegmentThumbFrame(0f, 80f))
        assertThat(segmentThumbFrame(1, widths)).isEqualTo(SegmentThumbFrame(80f, 140f))
        assertThat(segmentThumbFrame(2, widths)).isEqualTo(SegmentThumbFrame(220f, 61f))
    }

    @Test
    fun `no thumb shows for a choice outside the options or a segment not yet measured`() {
        assertThat(segmentThumbFrame(-1, listOf(100f, 100f))).isEqualTo(SegmentThumbFrame.Hidden)
        assertThat(segmentThumbFrame(2, listOf(100f, 100f))).isEqualTo(SegmentThumbFrame.Hidden)
        assertThat(segmentThumbFrame(0, emptyList())).isEqualTo(SegmentThumbFrame.Hidden)
    }

    @Test
    fun `the thumb springs only between choices once placed, and jumps under reduced motion`() {
        assertThat(segmentThumbMotion(reducedMotion = false, placed = true, indexChanged = true)).isEqualTo(SegmentThumbMotion.Spring)
        assertThat(segmentThumbMotion(reducedMotion = true, placed = true, indexChanged = true)).isEqualTo(SegmentThumbMotion.Jump)
        assertThat(segmentThumbMotion(reducedMotion = false, placed = false, indexChanged = true)).isEqualTo(SegmentThumbMotion.Jump)
        assertThat(segmentThumbMotion(reducedMotion = false, placed = true, indexChanged = false)).isEqualTo(SegmentThumbMotion.Jump)
    }

    @Test
    fun `measured segment widths are kept per index`() {
        val thumb = SegmentThumbState()

        thumb.segmentMeasured(1, 120f)
        thumb.segmentMeasured(0, 90f)

        assertThat(thumb.segmentWidths).containsExactly(90f, 120f).inOrder()
    }

    @Test
    fun `under reduced motion the thumb lands on its target without a frame`() = runTest {
        val thumb = SegmentThumbState()
        val clock = StepClock()
        withContext(clock) {
            thumb.moveTo(0, SegmentThumbFrame(0f, 100f), reducedMotion = false)
            thumb.moveTo(2, SegmentThumbFrame(220f, 61f), reducedMotion = true)
        }

        assertThat(clock.frames).isEqualTo(0)
        assertThat(thumb.offset).isEqualTo(220f)
        assertThat(thumb.width).isEqualTo(61f)
    }

    @Test
    fun `a new choice springs the thumb over frames and settles on the target`() = runTest {
        val thumb = SegmentThumbState()
        val clock = StepClock()
        withContext(clock) {
            thumb.moveTo(0, SegmentThumbFrame(0f, 100f), reducedMotion = false)
            thumb.moveTo(1, SegmentThumbFrame(100f, 140f), reducedMotion = false)
        }

        assertThat(clock.frames).isGreaterThan(1)
        assertThat(thumb.offset).isEqualTo(100f)
        assertThat(thumb.width).isEqualTo(140f)
    }

    @Test
    fun `hiding the thumb is no move between choices, so it goes at once`() = runTest {
        val thumb = SegmentThumbState()
        val clock = StepClock()
        withContext(clock) {
            thumb.moveTo(1, SegmentThumbFrame(100f, 140f), reducedMotion = false)
            thumb.moveTo(-1, SegmentThumbFrame.Hidden, reducedMotion = false)
        }

        assertThat(clock.frames).isEqualTo(0)
        assertThat(thumb.width).isEqualTo(0f)
    }

    @Test
    fun `once hidden, any choice brings the thumb back in place, the one it left included`() = runTest {
        for (back in listOf(0 to SegmentThumbFrame(0f, 100f), 1 to SegmentThumbFrame(100f, 140f))) {
            val thumb = SegmentThumbState()
            val clock = StepClock()
            withContext(clock) {
                thumb.moveTo(0, SegmentThumbFrame(0f, 100f), reducedMotion = false)
                thumb.moveTo(-1, SegmentThumbFrame.Hidden, reducedMotion = false)
                clock.frames = 0
                thumb.moveTo(back.first, back.second, reducedMotion = false)
            }

            // Hidden, the thumb sits on no choice: coming back is a first placement, the same for every choice.
            assertWithMessage("back to ${back.first}").that(clock.frames).isEqualTo(0)
            assertThat(thumb.offset).isEqualTo(back.second.offset)
            assertThat(thumb.width).isEqualTo(back.second.width)
        }
    }

    @Test
    fun `a segment measured late is a first placement, and the next choice springs from it`() = runTest {
        val thumb = SegmentThumbState()
        val clock = StepClock()
        withContext(clock) {
            thumb.moveTo(0, SegmentThumbFrame.Hidden, reducedMotion = false)
            thumb.moveTo(0, SegmentThumbFrame(0f, 100f), reducedMotion = false)
            assertThat(clock.frames).isEqualTo(0)
            thumb.moveTo(1, SegmentThumbFrame(100f, 140f), reducedMotion = false)
        }

        assertThat(clock.frames).isGreaterThan(1)
        assertThat(thumb.offset).isEqualTo(100f)
    }
}
