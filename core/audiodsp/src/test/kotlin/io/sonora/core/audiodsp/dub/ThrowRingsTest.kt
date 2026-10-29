package io.sonora.core.audiodsp.dub

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** HistoryRing, PendingSendRing, PresentationClock and the delay line's overlay on their own. */
class ThrowRingsTest {

    // --- HistoryRing --------------------------------------------------------------------------------------------

    @Test
    fun `the history ring keeps the newest frames by absolute index`() {
        val ring = HistoryRing(2, 4)
        assertThat(ring.newestFrame).isEqualTo(-1L)
        for (f in 0 until 6) ring.write(f.toFloat(), -f.toFloat())
        assertThat(ring.newestFrame).isEqualTo(5L)
        assertThat(ring.oldestFrame).isEqualTo(2L)
        assertThat(ring.get(5, 0)).isEqualTo(5f)
        assertThat(ring.get(2, 1)).isEqualTo(-2f)
        assertThat(ring.contains(1)).isFalse()
        assertThat(ring.get(1, 0)).isEqualTo(0f)
        assertThat(ring.get(6, 0)).isEqualTo(0f)
        assertThat(ring.get(3, 2)).isEqualTo(0f)
    }

    @Test
    fun `the history ring stores non-finite input as silence and restarts at a given frame`() {
        val ring = HistoryRing(1, 8)
        ring.write(Float.NaN, 0f)
        ring.write(Float.POSITIVE_INFINITY, 0f)
        ring.write(0.25f, 9f)
        assertThat(ring.get(0, 0)).isEqualTo(0f)
        assertThat(ring.get(1, 0)).isEqualTo(0f)
        assertThat(ring.get(2, 0)).isEqualTo(0.25f)
        assertThat(ring.get(2, 1)).isEqualTo(0f) // mono: no second channel
        ring.reset(nextFrame = 1_000)
        assertThat(ring.newestFrame).isEqualTo(999L)
        assertThat(ring.contains(2)).isFalse()
        ring.write(0.5f, 0f)
        assertThat(ring.oldestFrame).isEqualTo(1_000L)
        assertThat(ring.get(1_000, 0)).isEqualTo(0.5f)
    }

    @Test
    fun `the history ring holds 400 ms and accepts one or two channels`() {
        assertThat(HistoryRing.capacityFor(48_000)).isEqualTo(19_200)
        assertThat(HistoryRing.capacityFor(44_100)).isEqualTo(17_640)
        assertThrows(IllegalArgumentException::class.java) { HistoryRing(3, 10) }
        assertThrows(IllegalArgumentException::class.java) { HistoryRing(2, 0) }
    }

    // --- PresentationClock --------------------------------------------------------------------------------------

    @Test
    fun `a reported position maps to a source frame through the anchor`() {
        // The stage restarted counting at frame 1000 when the media time was 2.0 s.
        assertThat(PresentationClock.presentedFrame(2_500_000, 2_000_000, 1_000, 48_000)).isEqualTo(25_000.0)
        assertThat(PresentationClock.presentedFrame(2_000_000, 2_000_000, 1_000, 44_100)).isEqualTo(1_000.0)
    }

    @Test
    fun `the heard frame goes back from the presented frame by the touch age at the playback speed`() {
        val clock = PresentationClock(48_000)
        assertThat(clock.heardFrameAt(0)).isNaN()
        clock.record(nowNanos = 5_000_000_000, framesHandedDown = 120_000, presentedFrame = 114_000.0, speed = 1.0)
        assertThat(clock.queuedFrames).isEqualTo(6_000.0)
        // A touch 50 ms ago was 2400 source frames back at speed 1.0...
        assertThat(clock.heardFrameAt(4_950_000_000)).isWithin(1e-6).of(111_600.0)
        // ...and 3000 at speed 1.25 (the track runs faster than the wall clock).
        clock.record(nowNanos = 5_000_000_000, framesHandedDown = 120_000, presentedFrame = 114_000.0, speed = 1.25)
        assertThat(clock.heardFrameAt(4_950_000_000)).isWithin(1e-6).of(111_000.0)
        // A touch stamped after the record (clock skew) is heard at the presented frame.
        assertThat(clock.heardFrameAt(5_010_000_000)).isEqualTo(114_000.0)
    }

    @Test
    fun `the clock sanitises its inputs`() {
        val clock = PresentationClock(48_000)
        clock.record(nowNanos = 1_000_000_000, framesHandedDown = 10_000, presentedFrame = 12_000.0, speed = Double.NaN)
        assertThat(clock.speed).isEqualTo(1.0)
        assertThat(clock.queuedFrames).isEqualTo(0.0) // presented can never run ahead of what was handed down
        clock.record(nowNanos = 1_000_000_000, framesHandedDown = 10_000, presentedFrame = Double.NaN, speed = -2.0)
        assertThat(clock.presentedFrame).isEqualTo(10_000.0)
        assertThat(clock.speed).isEqualTo(1.0)
    }

    // --- PendingSendRing ----------------------------------------------------------------------------------------

    /** A history of [frames] ones on both channels with a pending ring advanced in step. */
    private fun rings(frames: Int, rate: Int = 48_000): Pair<HistoryRing, PendingSendRing> {
        val history = HistoryRing(2, HistoryRing.capacityFor(rate))
        val pending = PendingSendRing(history, rate)
        step(history, pending, frames)
        return history to pending
    }

    private fun step(history: HistoryRing, pending: PendingSendRing, frames: Int) = repeat(frames) {
        history.write(1f, 1f)
        pending.advance()
    }

    @Test
    fun `a press is compensated only when the delay is longer than the depth to the heard frame`() {
        val (history, pending) = rings(10_000)
        // Frontier 10000, heard 5000: a depth of 5000 frames.
        assertThat(pending.press(5_000.0, delayFrames = 5_000.0)).isEqualTo(ThrowMode.FRONTIER)
        assertThat(pending.pressFrame).isEqualTo(10_000L)
        pending.release(Double.NaN)
        step(history, pending, 1_000)
        assertThat(pending.press(5_000.0 + 1_000, delayFrames = 5_000.5)).isEqualTo(ThrowMode.COMPENSATED)
        assertThat(pending.pressFrame).isEqualTo(6_000L)
    }

    @Test
    fun `the send fades in over 5 ms from the heard frame and out over 5 ms from the release`() {
        val (history, pending) = rings(10_000)
        val ramp = 240L // 5 ms at 48 kHz
        pending.press(4_000.0, delayFrames = 24_000.0)
        assertThat(pending.sendAt(3_999, 0)).isEqualTo(0f)
        assertThat(pending.sendAt(4_000, 0)).isWithin(1e-7f).of(1f / ramp)
        assertThat(pending.sendAt(4_000 + ramp - 1, 1)).isEqualTo(1f)
        assertThat(pending.sendAt(9_999, 0)).isEqualTo(1f)
        // The release is heard at 8000, before the frontier: the queued part after it fades out.
        pending.release(8_000.0)
        assertThat(pending.sendAt(7_999, 0)).isEqualTo(1f)
        assertThat(pending.sendAt(8_000, 0)).isEqualTo(1f)
        assertThat(pending.sendAt(8_000 + ramp / 2, 0)).isWithin(1e-6f).of(0.5f)
        assertThat(pending.sendAt(8_000 + ramp, 0)).isEqualTo(0f)
        assertThat(pending.sendAt(9_999, 0)).isEqualTo(0f)
        assertThat(pending.isActive).isFalse()
        step(history, pending, 1)
        assertThat(pending.sendAt(10_000, 0)).isEqualTo(0f)
    }

    @Test
    fun `a release at the frontier finishes its fade on the frames that follow`() {
        val (history, pending) = rings(10_000)
        pending.press(4_000.0, delayFrames = 24_000.0)
        pending.release(10_000.0)
        assertThat(pending.isActive).isTrue()
        step(history, pending, 240)
        assertThat(pending.sendAt(10_000, 0)).isEqualTo(1f)
        assertThat(pending.sendAt(10_120, 0)).isWithin(1e-6f).of(0.5f)
        assertThat(pending.sendAt(10_239, 0)).isWithin(1e-6f).of(1f / 240)
        assertThat(pending.isActive).isFalse()
    }

    @Test
    fun `a heard frame older than the history is clamped to it, and a second press while held is ignored`() {
        val (history, pending) = rings(30_000)
        assertThat(pending.press(-5_000.0, delayFrames = 48_000.0)).isEqualTo(ThrowMode.COMPENSATED)
        assertThat(pending.pressFrame).isEqualTo(history.oldestFrame)
        assertThat(pending.press(29_000.0, delayFrames = 48_000.0)).isEqualTo(ThrowMode.COMPENSATED)
        assertThat(pending.pressFrame).isEqualTo(history.oldestFrame)
    }

    @Test
    fun `a new press during a fade-out keeps the louder of the two gains`() {
        val (_, pending) = rings(10_000)
        pending.press(4_000.0, delayFrames = 24_000.0)
        pending.release(8_000.0)
        pending.press(8_100.0, delayFrames = 24_000.0)
        // 8100 is 100 frames into the old fade-out (gain 140/240) and the first frame of the new fade-in (1/240).
        assertThat(pending.sendAt(8_100, 0)).isWithin(1e-6f).of(140f / 240)
        assertThat(pending.sendAt(8_400, 0)).isEqualTo(1f)
        assertThat(pending.isActive).isTrue()
    }

    @Test
    fun `the pending ring taps frames by age for the delay line`() {
        val history = HistoryRing(1, 1_000)
        val pending = PendingSendRing(history, 48_000)
        for (f in 0 until 2_000) {
            history.write(f.toFloat(), 0f)
            pending.advance()
        }
        pending.press(1_500.0, delayFrames = 24_000.0)
        repeat(1_000) { assertThat(pending.tap(it, 0)).isEqualTo(pending.sendAt(1_999L - it, 0)) }
        assertThat(pending.tap(0, 0)).isEqualTo(1_999f)
        assertThat(pending.tap(499, 0)).isWithin(1e-3f).of(1_500f / 240)
        assertThat(pending.tap(500, 0)).isEqualTo(0f)
        assertThat(pending.tap(1_000, 0)).isEqualTo(0f)
        assertThat(pending.tap(-1, 0)).isEqualTo(0f)
    }

    // --- Overlay on the delay line ------------------------------------------------------------------------------

    @Test
    fun `the delay line reads an overlay summed with its ring and commits into its past`() {
        val line = FractionalDelayLine().apply {
            prepare(48_000f)
            setTimeMs(20f) // 960 frames
            setFeedback(0f)
            setMix(1f)
            reset()
        }
        // The overlay says: the frame 960 frames ago carried 0.5 (only at frame 2000).
        var frame = 0
        val overlay = DelayOverlay { framesAgo, _ -> if (frame - framesAgo == 1_040) 0.5f else 0f }
        val out = FloatArray(3_000) { i ->
            frame = i
            line.process(0f, overlay, 0)
        }
        assertThat(out[2_000]).isEqualTo(0.5f)
        assertThat(out[1_999]).isEqualTo(0f)
        assertThat(out[2_001]).isEqualTo(0f)

        // A committed value lands where a write would have been: 10 frames before the next write.
        line.reset()
        repeat(100) { line.process(0f) }
        line.addWritten(10, 0.25f)
        val later = FloatArray(1_000) { line.process(0f) }
        assertThat(later[960 - 10]).isEqualTo(0.25f)
        assertThat(later.count { it != 0f }).isEqualTo(1)
        line.addWritten(10, Float.NaN)
        line.addWritten(0, 1f)
        line.addWritten(line.ringFrames, 1f)
        assertThat(FloatArray(2_000) { line.process(0f) }.all { it == 0f }).isTrue()
    }
}
