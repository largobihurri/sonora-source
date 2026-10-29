package io.sonora.core.domain.playback

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random
import org.junit.Test

/** The per-frame position the waveform is drawn at, from the player's jittery readings. */
class PositionSmootherTest {

    /** A simulated player: the true position moves at [speed] from [startMs]; the smoother is asked once per frame. */
    private class Run(
        private val smoother: PositionSmoother,
        val hz: Double,
        var speed: Float = 1f,
        startMs: Double = 5_000.0,
        var item: Long = 1L,
        var playing: Boolean = true,
        private val noiseMs: Double = 0.0,
        seed: Int = 7,
    ) {
        private val random = Random(seed)
        private val sample = ClockSample()
        private val frameNanos = (1e9 / hz).roundToLong()

        /** The true position, ms. */
        var truth = startMs

        /** Time since the run began, ns. */
        var nanos = 1_000_000_000L

        /** Added to every reading, ms (a reading running ahead of the prediction). */
        var readingOffsetMs = 0.0

        /** Advances [frames] frames and returns the last output. */
        fun frames(count: Int, each: (Double) -> Unit = {}): Double {
            var out = Double.NaN
            repeat(count) {
                nanos += frameNanos
                if (playing) truth += frameNanos / 1e6 * speed
                out = frameWith(readingMs = truth + readingOffsetMs + noise())
                each(out)
            }
            return out
        }

        /** One frame at the current time with an explicit reading. */
        fun frameWith(readingMs: Double, speedReading: Float = speed): Double {
            sample.queueItemId = item
            sample.positionMs = readingMs.roundToLong()
            sample.speed = speedReading
            sample.playing = playing
            return smoother.advance(nanos, sample)
        }

        fun frameWith(position: Long, speedReading: Float = speed): Double {
            sample.queueItemId = item
            sample.positionMs = position
            sample.speed = speedReading
            sample.playing = playing
            return smoother.advance(nanos, sample)
        }

        /** The time passes by [ms] with no frame drawn. */
        fun skip(ms: Long) {
            nanos += ms * 1_000_000
            if (playing) truth += ms * speed
        }

        private fun noise(): Double = if (noiseMs == 0.0) 0.0 else (random.nextDouble() * 2 - 1) * noiseMs
    }

    private fun smoother(leadNanos: Long = 0L) = PositionSmoother(presentLeadNanos = leadNanos)

    @Test
    fun `steady playback with jittered readings is monotonic and settles within 2 ms of the truth`() {
        val run = Run(smoother(), hz = 60.0, noiseMs = 10.0)
        var previous = Double.NEGATIVE_INFINITY
        var worst = 0.0

        run.frames(60 * 10) { out ->
            assertThat(out).isAtLeast(previous)
            previous = out
            if (run.nanos > 4_000_000_000L + 1_000_000_000L) worst = maxOf(worst, abs(out - run.truth))
        }

        assertThat(worst).isLessThan(2.0)
    }

    @Test
    fun `at half and one and a half speed the position moves at that speed`() {
        for (speed in listOf(0.5f, 1.5f)) {
            val run = Run(smoother(), hz = 60.0, speed = speed)
            run.frames(60) // One second to settle.
            val from = run.frames(1)
            val to = run.frames(60 * 2)

            assertThat((to - from) / 2.0).isWithin(2.0).of(1_000.0 * speed)
            assertThat(abs(to - run.truth)).isLessThan(1.0)
        }
    }

    @Test
    fun `a seek lands on the new position in one frame, forward or back`() {
        val run = Run(smoother(), hz = 60.0)
        run.frames(120)

        run.truth += 30_000.0
        assertThat(run.frames(1)).isWithin(1.0).of(run.truth)

        run.frames(30)
        run.truth -= 20_000.0
        assertThat(run.frames(1)).isWithin(1.0).of(run.truth)
        assertThat(run.frames(30)).isWithin(1.0).of(run.truth)
    }

    @Test
    fun `paused, the drawn position is exactly the reported one`() {
        val run = Run(smoother(leadNanos = 16_000_000), hz = 60.0)
        run.frames(120)
        run.playing = false

        run.frames(1)
        val held = run.frameWith(7_003L)
        assertThat(held).isEqualTo(7_003.0)

        repeat(60) {
            run.nanos += 16_000_000
            assertThat(run.frameWith(7_003L)).isEqualTo(7_003.0)
        }

        // A seek while paused shows at once.
        assertThat(run.frameWith(12_345L)).isEqualTo(12_345.0)
    }

    @Test
    fun `resuming never steps back from where the pause held`() {
        val run = Run(smoother(leadNanos = 16_000_000), hz = 60.0)
        run.frames(120)
        run.playing = false
        run.nanos += 16_000_000
        val held = run.frameWith(9_000L)
        assertThat(held).isEqualTo(9_000.0)

        run.playing = true
        run.truth = 9_000.0
        var previous = held
        // The player reports a stale position for a couple of frames, then moves on.
        repeat(2) {
            run.nanos += 16_000_000
            val out = run.frameWith(9_000L)
            assertThat(out).isAtLeast(previous)
            previous = out
        }
        run.frames(120) { out ->
            assertThat(out).isAtLeast(previous)
            previous = out
        }
        assertThat(previous).isGreaterThan(9_000.0)
    }

    @Test
    fun `another queue item snaps to its reading even when it is a few ms behind the prediction`() {
        val run = Run(smoother(), hz = 60.0)
        run.frames(120)

        run.item = 2L
        val behind = run.truth - 30.0 // Under the snap distance: only the item change tells it apart from jitter.
        run.nanos += 16_000_000
        run.truth += 16.0
        val out = run.frameWith(behind + 16.0)

        assertThat(out).isWithin(0.6).of(behind + 16.0)
    }

    @Test
    fun `a speed change bends the motion without a jump, even while the reading is stale`() {
        val run = Run(smoother(), hz = 60.0)
        var previous = run.frames(120)

        // A tempo fader drag: the speed changes every few frames while the player's position is the same stale value
        // (Media3 refreshes it only when its playback thread wakes, every ~125 ms at 1x).
        val stale = run.truth.roundToLong() - 60L
        for (step in 1..12) {
            val speed = 1f + step * 0.01f
            repeat(3) {
                run.nanos += 16_666_667L
                val out = run.frameWith(stale, speedReading = speed)
                assertThat(out - previous).isWithin(16.667 * speed * 0.2).of(16.667 * speed)
                previous = out
            }
        }
    }

    /**
     * A device-like clock: the reading is the true position at the player's last update, rounded to ms, and the
     * updates come [updateMs] apart (a random interval each), with a stall of [stallMs] now and then ([stallChance]
     * per update).
     */
    private class DeviceClock(
        private val updateMs: ClosedFloatingPointRange<Double>,
        private val stallMs: ClosedFloatingPointRange<Double> = 0.0..0.0,
        private val stallChance: Double = 0.0,
        seed: Int,
    ) {
        private val random = Random(seed)
        private var nextUpdateNanos = 0L
        private var reading = 0L

        /** The reading seen at [nowNanos], with the true position `startMs + now x speed`. */
        fun readAt(nowNanos: Long, startMs: Double, speed: Double): Long {
            while (nextUpdateNanos <= nowNanos) {
                reading = (startMs + nextUpdateNanos / 1e6 * speed).roundToLong()
                val stall = stallChance > 0 && random.nextDouble() < stallChance
                val gapMs = if (stall) random.nextDouble(stallMs.start, stallMs.endInclusive)
                else random.nextDouble(updateMs.start, updateMs.endInclusive)
                nextUpdateNanos += (gapMs * 1e6).roundToLong()
            }
            return reading
        }
    }

    /**
     * Plays [seconds] of frames at [hz] through [smoother] from [clock] at 1x and checks every frame after the first
     * [settleSeconds]: the drawn position never steps back, each step is within 20 % of the frame's own advance, and it
     * stays within [trackMs] of the truth.
     */
    private fun assertSmoothOn(
        clock: DeviceClock,
        hz: Double,
        seconds: Int = 20,
        settleSeconds: Double = 2.0,
        trackMs: Double = 25.0,
    ) {
        val smoother = smoother()
        val sample = ClockSample().apply { queueItemId = 1L; speed = 1f; playing = true }
        val frameNanos = (1e9 / hz).roundToLong()
        val startMs = 30_000.0
        var previous = Double.NaN
        var worstStep = 0.0
        var worstTrack = 0.0
        for (frame in 1..(seconds * hz).toInt()) {
            val now = frame * frameNanos
            sample.positionMs = clock.readAt(now, startMs, speed = 1.0)
            val out = smoother.advance(now, sample)
            if (now / 1e9 > settleSeconds) {
                val expected = frameNanos / 1e6
                worstStep = maxOf(worstStep, abs((out - previous) - expected) / expected)
                worstTrack = maxOf(worstTrack, abs(out - (startMs + now / 1e6)))
            }
            previous = out
        }
        assertThat(worstStep).isAtMost(0.2)
        assertThat(worstTrack).isLessThan(trackMs)
    }

    @Test
    fun `a device clock stepping every 10 to 40 ms with 60 to 80 ms stalls draws evenly at 60 Hz`() {
        val clock = DeviceClock(updateMs = 10.0..40.0, stallMs = 60.0..80.0, stallChance = 0.08, seed = 11)
        assertSmoothOn(clock, hz = 60.0)
    }

    @Test
    fun `a device clock stepping every 10 to 40 ms with 60 to 80 ms stalls draws evenly at 120 Hz`() {
        val clock = DeviceClock(updateMs = 10.0..40.0, stallMs = 60.0..80.0, stallChance = 0.08, seed = 12)
        assertSmoothOn(clock, hz = 120.0)
    }

    @Test
    fun `a clock refreshed only every 120 to 130 ms, as Media3's dynamic scheduling does, draws evenly`() {
        assertSmoothOn(DeviceClock(updateMs = 120.0..130.0, seed = 13), hz = 60.0)
    }

    @Test
    fun `a fresh reading 100 ms off is slewed without a step back, not snapped`() {
        val run = Run(smoother(), hz = 60.0)
        var previous = run.frames(120)

        run.readingOffsetMs = -100.0 // An AudioTrack timestamp correction, not a seek.
        run.frames(120) { out ->
            assertThat(out - previous).isWithin(16.667 * 0.2).of(16.667)
            previous = out
        }
    }

    @Test
    fun `a 100 ms gap between frames carries on without a step`() {
        val run = Run(smoother(), hz = 60.0)
        val before = run.frames(120)

        run.skip(100)
        val after = run.frames(1)

        // The 16.7 ms of the frame itself and the 100 ms skipped: the prediction runs through the gap.
        assertThat(after - before).isWithin(2.0).of(116.7)
        assertThat(abs(after - run.truth)).isLessThan(2.0)
    }

    @Test
    fun `after a gap of seconds the clock is not trusted and the reading wins`() {
        val run = Run(smoother(), hz = 60.0)
        run.frames(120)

        run.skip(5_000)
        run.readingOffsetMs = 40.0
        val out = run.frames(1)

        assertThat(out).isWithin(0.6).of(run.truth + 40.0)
    }

    @Test
    fun `invalid readings are ignored, never propagated`() {
        val run = Run(smoother(), hz = 60.0)
        val before = run.frames(120)

        run.nanos += 16_666_667
        run.truth += 16.667
        val negativePosition = run.frameWith(-1L)
        assertThat(negativePosition.isNaN()).isFalse()
        assertThat(negativePosition).isWithin(1.0).of(before + 16.667)

        run.nanos += 16_666_667
        val nanSpeed = run.frameWith(run.truth + 16.667, speedReading = Float.NaN)
        assertThat(nanSpeed.isNaN()).isFalse()
        assertThat(nanSpeed).isWithin(1.0).of(before + 33.333)

        run.nanos += 16_666_667
        val unsetPosition = run.frameWith(Long.MIN_VALUE + 1) // C.TIME_UNSET
        assertThat(unsetPosition).isWithin(1.0).of(before + 50.0)

        run.nanos += 16_666_667
        val negativeSpeed = run.frameWith(run.truth + 50.0, speedReading = -2f)
        assertThat(negativeSpeed).isWithin(1.0).of(before + 66.667)
    }

    @Test
    fun `an invalid first reading draws zero, then the first good one snaps`() {
        val smoother = smoother()
        val sample = ClockSample().apply { positionMs = -5; playing = true }

        assertThat(smoother.advance(1_000_000_000L, sample)).isEqualTo(0.0)

        sample.positionMs = 42_000
        assertThat(smoother.advance(1_016_000_000L, sample)).isWithin(0.001).of(42_000.0)
    }

    @Test
    fun `a reading running ahead is caught up with at the same pace at 60 and 120 Hz`() {
        val at60 = Run(smoother(), hz = 60.0)
        val at120 = Run(smoother(), hz = 120.0)
        at60.frames(5)
        at120.frames(10)
        at60.readingOffsetMs = 30.0 // Below the snap distance: slewed, not snapped.
        at120.readingOffsetMs = 30.0

        val out60 = at60.frames(60) // One second later.
        val out120 = at120.frames(120)

        assertThat(abs(out60 - at60.truth - 30.0)).isGreaterThan(1.0) // Not caught up yet: this is a slew, not a snap.
        assertThat(abs((out60 - at60.truth) - (out120 - at120.truth))).isLessThan(0.5)
    }

    @Test
    fun `the drawn frame is ahead by the presentation lead, scaled by the speed, and not when paused`() {
        val lead = 16_000_000L
        val run = Run(smoother(leadNanos = lead), hz = 60.0)
        val normal = run.frames(120)
        assertThat(normal - run.truth).isWithin(0.5).of(16.0)

        val fast = Run(smoother(leadNanos = lead), hz = 60.0, speed = 2f)
        val doubled = fast.frames(120)
        assertThat(doubled - fast.truth).isWithin(0.5).of(32.0)

        run.playing = false
        run.nanos += 16_000_000
        assertThat(run.frameWith(8_000L)).isEqualTo(8_000.0)
    }

    @Test
    fun `reset forgets the past, so the next frame snaps to its reading`() {
        val smoother = smoother()
        val run = Run(smoother, hz = 60.0)
        run.frames(120)

        smoother.reset()
        run.readingOffsetMs = 40.0
        val out = run.frames(1)

        assertThat(out).isWithin(0.6).of(run.truth + 40.0)
    }

    @Test
    fun `a repeated frame time draws the same position`() {
        val run = Run(smoother(), hz = 60.0)
        val first = run.frames(120)

        assertThat(run.frameWith(run.truth)).isEqualTo(first)
    }

    @Test
    fun `each snap says why, and a slewed frame reports none`() {
        val smoother = smoother()
        val run = Run(smoother, hz = 60.0)
        run.frames(1)
        assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_START)
        run.frames(30)
        assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_NONE)

        run.item = 2L
        run.frames(1)
        assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_ITEM)

        run.frames(30)
        run.truth += 10_000.0
        run.frames(1)
        assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_JUMP_AHEAD)

        run.frames(30)
        run.truth -= 10_000.0
        run.frames(1)
        assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_JUMP_BACK)

        run.frames(30)
        run.playing = false
        run.frames(1)
        assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_NONE)
        run.playing = true
        run.frames(1)
        assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_PLAYING)

        run.frames(30)
        run.skip(3_000)
        run.frames(1)
        assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_GAP)
    }

    @Test
    fun `a stale first reading is caught up with by the first fresh one ahead of it`() {
        val smoother = smoother()
        val sample = ClockSample().apply { queueItemId = 1L; speed = 1f; playing = true }
        val frame = 16_666_667L
        // The screen opens 100 ms after the player's last update: the first reading is 100 ms old.
        sample.positionMs = 10_000L
        smoother.advance(frame, sample)
        sample.positionMs = 10_000L
        smoother.advance(2 * frame, sample)
        // The next update: the truth at 2 frames + 100 ms of age.
        sample.positionMs = 10_133L
        val out = smoother.advance(3 * frame, sample)

        assertThat(out).isWithin(0.001).of(10_133.0)
        assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_SETTLE)
    }
}
