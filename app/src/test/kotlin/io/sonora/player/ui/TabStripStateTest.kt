package io.sonora.player.ui

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TabStripState] as the tab bar (drag, release) and the host (settle) drive it, frame by frame at 120 Hz. */
class TabStripStateTest {

    /** A 120 Hz frame clock that records where the strip is after each frame. */
    private class FrameClock(private val strip: TabStripState) : MonotonicFrameClock {
        private var nanos = 0L
        val positions = mutableListOf<Float>()

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            nanos += FRAME_NANOS
            return onFrame(nanos).also { positions += strip.position }
        }
    }

    private fun TabStripState.settleFrames(index: Int, reducedMotion: Boolean = false): List<Float> {
        val clock = FrameClock(this)
        runBlocking(clock) { settle(index, reducedMotion) }
        return clock.positions
    }

    @Test
    fun `starts on its initial tab, within the strip`() {
        assertEquals(2f, TabStripState(initialIndex = 2, count = 4).position)
        assertEquals(3f, TabStripState(initialIndex = 7, count = 4).position)
        assertEquals(2, TabStripState(initialIndex = 2, count = 4).heading)
    }

    @Test
    fun `a drag puts the strip under the finger, within the strip, heading where it moves`() {
        val strip = TabStripState(initialIndex = 1, count = 4)
        strip.drag(1.6f)
        assertEquals(1.6f, strip.position)
        assertEquals(3, strip.heading)
        assertTrue(strip.dragging)

        strip.drag(1.2f)
        assertEquals(0, strip.heading)
        strip.drag(-2f)
        assertEquals(0f, strip.position)
        strip.drag(9f)
        assertEquals(3f, strip.position)
    }

    @Test
    fun `a tap springs the strip to the tab without overshoot`() {
        val strip = TabStripState(initialIndex = 0, count = 4)
        val frames = strip.settleFrames(3)
        assertEquals(3, strip.heading)
        assertEquals(3f, strip.position)
        assertTrue(frames.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(frames.all { it <= 3f })
        // Well under a second at 120 Hz.
        assertTrue("${frames.size} frames", frames.size in 20..90)
    }

    @Test
    fun `a released scrub settles from the finger with the finger's speed`() {
        val flung = TabStripState(initialIndex = 1, count = 4)
        flung.drag(1.3f)
        flung.release(velocity = 6f)
        assertFalse(flung.dragging)
        val withSpeed = flung.settleFrames(2)

        val still = TabStripState(initialIndex = 1, count = 4)
        still.drag(1.3f)
        still.release(velocity = 0f)
        val fromRest = still.settleFrames(2)

        assertEquals(2f, flung.position)
        // It starts from the finger's position (no jump back) and moves on faster than from rest.
        assertEquals(1.3f, withSpeed.first(), 1e-4f)
        assertTrue("${withSpeed[1]}", withSpeed[1] > 1.3f && withSpeed[1] < 1.45f)
        assertTrue(withSpeed[1] - 1.3f > 2f * (fromRest[1] - 1.3f))
    }

    @Test
    fun `each release is counted, so a scrub that starts and ends between two frames still settles`() {
        val strip = TabStripState(initialIndex = 1, count = 4)
        val before = strip.releases
        // Both within one frame: the host never sees dragging true, and the selected tab does not change.
        strip.drag(1.3f)
        strip.release(velocity = -4f)
        assertFalse(strip.dragging)
        assertEquals(before + 1, strip.releases)

        // The host's settle on the unchanged tab starts from the finger with the release speed (back toward tab 1).
        val frames = strip.settleFrames(1)
        assertEquals(1.3f, frames.first(), 1e-4f)
        assertTrue("${frames[1]}", frames[1] < 1.3f)
        assertEquals(1f, strip.position)

        strip.drag(1.6f)
        strip.release(velocity = 0f)
        assertEquals(before + 2, strip.releases)
    }

    @Test
    fun `a release speed is used once`() {
        val strip = TabStripState(initialIndex = 1, count = 4)
        strip.drag(1.3f)
        strip.release(velocity = 6f)
        strip.settleFrames(2)
        // A later tap starts from rest, as on a strip that was never scrubbed.
        val tap = strip.settleFrames(0)
        val reference = TabStripState(initialIndex = 2, count = 4).settleFrames(0)
        assertEquals(reference, tap)
    }

    @Test
    fun `with reduced motion the strip moves at once`() {
        val strip = TabStripState(initialIndex = 0, count = 4)
        assertEquals(emptyList<Float>(), strip.settleFrames(2, reducedMotion = true))
        assertEquals(2f, strip.position)

        strip.drag(1.4f)
        strip.release(velocity = 5f)
        strip.settleFrames(1, reducedMotion = true)
        assertEquals(1f, strip.position)
    }

    private companion object {
        const val FRAME_NANOS = 8_333_333L
    }
}
