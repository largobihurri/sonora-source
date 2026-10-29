package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class AlphaBetaTrackerTest {

    private val frame60 = 16_666_667L
    private val frame120 = 8_333_333L
    private val constant = 50_000_000L

    @Test
    fun `a tracker at rest holds a constant and ignores no time`() {
        val tracker = AlphaBetaTracker(constant).also { it.reset(100f) }
        repeat(100) { tracker.update(100f, frame120) }
        assertThat(tracker.position).isWithin(1e-4f).of(100f)
        assertThat(tracker.velocity).isWithin(1e-4f).of(0f)
        tracker.update(500f, 0L)
        tracker.update(500f, -1L)
        assertThat(tracker.position).isWithin(1e-4f).of(100f)
    }

    @Test
    fun `a tracker follows a steady motion with no lag and learns its speed`() {
        for (frameNanos in listOf(frame60, frame120)) {
            val tracker = AlphaBetaTracker(constant).also { it.reset(0f) }
            var x = 0f
            repeat((1_000_000_000L / frameNanos).toInt()) { // 300 px/s for a second, ten time constants
                x += 300f * frameNanos / 1e9f
                tracker.update(x, frameNanos)
            }
            // A one-pole filter would trail by speed * constant = 15 px. The tracker holds each measurement over its
            // frame, so it is off by at most the motion of half a frame (2.5 px at 60 Hz), not by its time constant.
            assertThat(abs(x - tracker.position)).isAtMost(300f * frameNanos / 2e9f)
            assertThat(tracker.velocity).isWithin(5f).of(300f) // read at the end of a frame, a little under its mean
        }
    }

    @Test
    fun `two half steps land where one full step does, so the frame rate changes nothing`() {
        val full = AlphaBetaTracker(constant).also { it.reset(0f) }
        val halves = AlphaBetaTracker(constant).also { it.reset(0f) }
        full.update(40f, frame60)
        halves.update(40f, frame60 / 2)
        halves.update(40f, frame60 - frame60 / 2)
        assertThat(full.position).isGreaterThan(0f)
        assertThat(halves.position).isWithin(1e-3f).of(full.position)
        assertThat(halves.velocity).isWithin(1e-2f).of(full.velocity)
    }

    @Test
    fun `a tracker settles on a new position with no speed left`() {
        val tracker = AlphaBetaTracker(constant).also { it.reset(0f) }
        repeat(120) { tracker.update(10f, frame120) } // a second, twenty time constants
        assertThat(tracker.position).isWithin(1e-3f).of(10f)
        assertThat(tracker.velocity).isWithin(1e-2f).of(0f)
    }
}
