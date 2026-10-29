package io.sonora.core.domain.playback

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong
import org.junit.Test

/**
 * The drawn position while a DJ loop repeats: the player's readings wrap from the loop's end to its start, and the
 * smoother must wrap with them instead of treating every wrap as a seek. The readings come in steps (a fresh one only
 * every 125 ms, as in `PositionSmootherTest`), so loops shorter than a step are in play too.
 */
class PositionSmootherLoopTest {

    /**
     * A simulated looping player at 1x: the audio started the loop at [loopStartAtMs] of the run (time zero is the run's
     * first frame), the reading is refreshed every [refreshMs] with the position heard at that time.
     */
    private class LoopRun(
        val smoother: PositionSmoother = PositionSmoother(presentLeadNanos = 0L),
        val inMs: Double,
        val outMs: Double,
        val refreshMs: Double = 125.0,
        val hz: Double = 60.0,
        private val speed: Float = 1f,
        /** The position the loop is at when the run starts, ms from In. */
        phaseMs: Double = 0.0,
    ) {
        private val sample = ClockSample()
        private val frameNanos = (1e9 / hz).roundToLong()
        private val length = outMs - inMs
        private var elapsedMs = 0.0
        private var nextRefreshMs = 0.0
        private var reading = 0L
        private val startPhase = phaseMs

        var nanos = 1_000_000_000L
        var looping = true
        var playing = true

        /** Added to the time every reading is taken at, ms: a reading running ahead of the truth. */
        var readingAheadMs = 0.0

        /** The heard position at [t] ms into the run, wrapped into the loop. */
        fun truthAt(t: Double): Double = inMs + (startPhase + t * speed).mod(length)

        val truth: Double get() = truthAt(elapsedMs)

        /** Draws one frame and returns the output. */
        fun frame(): Double {
            nanos += frameNanos
            elapsedMs += frameNanos / 1e6
            if (elapsedMs >= nextRefreshMs) {
                reading = truthAt(elapsedMs + readingAheadMs).roundToLong()
                nextRefreshMs = elapsedMs + refreshMs
            }
            sample.queueItemId = 1L
            sample.positionMs = reading
            sample.speed = speed
            sample.playing = playing
            sample.looping = looping
            sample.loopInMs = inMs
            sample.loopOutMs = outMs
            return smoother.advance(nanos, sample)
        }
    }

    private fun smooth(lead: Long = 0L) = PositionSmoother(presentLeadNanos = lead)

    /** The shortest way from [from] to [to] around a loop of [length]: in (-length/2, length/2]. */
    private fun modular(from: Double, to: Double, length: Double): Double {
        var d = (to - from) % length
        if (d > length / 2) d -= length else if (d <= -length / 2) d += length
        return d
    }

    private fun assertEvenAround(run: LoopRun, seconds: Int, advance: Double = 1_000.0 / 60) {
        val length = run.outMs - run.inMs
        var previous = run.frame()
        // The first fresh reading after the start may settle (a small forward snap): the loop is steady after that.
        repeat(WARM_UP_FRAMES) { previous = run.frame() }
        var worstStep = 0.0
        repeat(60 * seconds) {
            val out = run.frame()
            assertThat(out).isAtLeast(run.inMs)
            assertThat(out).isLessThan(run.outMs)
            // No snap at a wrap, or anywhere else: the loop is steady.
            assertThat(run.smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_NONE)
            val step = modular(previous, out, length)
            worstStep = maxOf(worstStep, abs(step - advance))
            previous = out
        }
        // The drawn motion stays within the slew cap of the playback speed (0.9x to 1.1x), wraps included.
        assertThat(worstStep).isLessThan(advance * 0.15)
    }

    @Test
    fun `a two second loop draws evenly across its wraps with the staircase readings, never snapping`() {
        assertEvenAround(LoopRun(inMs = 10_000.0, outMs = 12_000.0), seconds = 12)
    }

    @Test
    fun `a loop shorter than the reading's 125 ms refresh draws evenly too`() {
        assertEvenAround(LoopRun(inMs = 10_000.0, outMs = 10_100.0), seconds = 6)
        assertEvenAround(LoopRun(inMs = 10_000.0, outMs = 10_062.0, refreshMs = 130.0), seconds = 6)
    }

    @Test
    fun `a loop is drawn within a couple of frames of the truth, wrapped`() {
        val run = LoopRun(inMs = 4_000.0, outMs = 4_500.0, phaseMs = 120.0)
        repeat(60) { run.frame() }
        var worst = 0.0
        repeat(60 * 6) {
            val out = run.frame()
            worst = maxOf(worst, abs(modular(run.truth, out, 500.0)))
        }
        // A fresh reading is at most one frame old, so the drawn position stays within a few ms of the truth.
        assertThat(worst).isLessThan(25.0)
    }

    @Test
    fun `the frame that wraps keeps the prediction instead of resetting it`() {
        // After the warm-up (90 frames = 1.5 s) the loop is 40 ms short of its end.
        val run = LoopRun(inMs = 0.0, outMs = 1_000.0, phaseMs = 460.0)
        repeat(WARM_UP_FRAMES) { run.frame() }
        val wraps = ArrayList<Double>()
        var previous = run.frame()
        repeat(30) {
            val out = run.frame()
            if (out < previous) wraps += previous - out
            assertThat(run.smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_NONE)
            previous = out
        }
        // Exactly one wrap, and it falls from near Out back to near In.
        assertThat(wraps).hasSize(1)
        assertThat(wraps[0]).isWithin(40.0).of(1_000.0)
    }

    @Test
    fun `a reading that already wrapped does not pull the position the long way round`() {
        // Fresh readings every frame, running 30 ms ahead of the truth: each time the audio wraps, the reading is
        // already past In while the prediction is still short of Out.
        val run = LoopRun(inMs = 1_000.0, outMs = 2_000.0, refreshMs = 1_000.0 / 60, phaseMs = 0.0)
        repeat(WARM_UP_FRAMES) { run.frame() }
        run.readingAheadMs = 30.0
        var previous = run.frame()
        var wraps = 0
        repeat(60 * 4) {
            val out = run.frame()
            if (out < previous) wraps++
            assertThat(run.smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_NONE)
            // Mod the loop the error is +30 ms, worked off gently: the position never steps back or stops.
            assertThat(modular(previous, out, 1_000.0)).isGreaterThan(14.0)
            previous = out
        }
        assertThat(wraps).isAtLeast(3)
    }

    @Test
    fun `entering a loop whose first reading is back at In does not snap`() {
        val smoother = smooth()
        val sample = ClockSample().apply {
            queueItemId = 1L
            speed = 1f
            playing = true
        }
        var nanos = 1_000_000_000L
        var truth = 10_200.0
        fun frame(looping: Boolean, reading: Long): Double {
            nanos += 16_666_667L
            truth += 16.666667
            sample.positionMs = reading
            sample.looping = looping
            sample.loopInMs = 10_000.0
            sample.loopOutMs = 12_000.0
            return smoother.advance(nanos, sample)
        }
        // Linear playback toward Out, readings refreshed every frame for clarity (the start settles first).
        var out = 0.0
        repeat(WARM_UP_FRAMES / 2) { out = frame(false, truth.roundToLong()) }
        // The audio passes Out at 12000 and the loop takes it back to In: the reading is the wrapped position.
        while (truth < 12_000.0) out = frame(false, truth.roundToLong())
        val overshoot = truth - 12_000.0
        val wrapped = 10_000.0 + overshoot
        val first = frame(true, wrapped.roundToLong() + 17)
        assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_NONE)
        assertThat(first).isLessThan(10_200.0)
        assertThat(first).isAtLeast(10_000.0)
        assertThat(out).isGreaterThan(11_900.0)
    }

    @Test
    fun `leaving a loop carries on linearly from where the loop was, without a snap`() {
        val run = LoopRun(inMs = 10_000.0, outMs = 12_000.0, phaseMs = 200.0)
        repeat(60 * 3) { run.frame() }
        // Exit mid-loop: from now the audio goes on in a straight line (no more wraps), and the flag says so.
        val exitAt = run.truth
        run.looping = false
        var previous = run.frame()
        var worst = 0.0
        repeat(30) {
            val out = run.frame()
            worst = maxOf(worst, abs(out - previous - 1_000.0 / 60))
            previous = out
        }
        assertThat(exitAt).isGreaterThan(10_000.0)
        assertThat(worst).isLessThan(2.5)
        assertThat(run.smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_NONE)
    }

    @Test
    fun `halving the loop while it plays keeps the motion`() {
        val smoother = smooth()
        val sample = ClockSample().apply {
            queueItemId = 1L
            speed = 1f
            playing = true
            looping = true
            loopInMs = 5_000.0
            loopOutMs = 6_000.0
        }
        var nanos = 1_000_000_000L
        var truth = 5_100.0
        fun frame(outMs: Double): Double {
            nanos += 16_666_667L
            truth += 16.666667
            val length = outMs - 5_000.0
            sample.positionMs = (5_000.0 + (truth - 5_000.0).mod(length)).roundToLong()
            sample.loopOutMs = outMs
            return smoother.advance(nanos, sample)
        }
        var previous = 0.0
        repeat(120) { previous = frame(6_000.0) }
        repeat(120) {
            val out = frame(5_500.0)
            assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_NONE)
            assertThat(abs(modular(previous, out, 500.0) - 16.667)).isLessThan(4.0)
            previous = out
        }
    }

    @Test
    fun `paused inside a loop the exact reading is drawn`() {
        val run = LoopRun(inMs = 3_000.0, outMs = 3_400.0)
        repeat(20) { run.frame() }
        run.playing = false
        val out = run.frame()
        val second = run.frame()
        assertThat(out).isEqualTo(second)
        assertThat(out).isAtLeast(3_000.0)
        assertThat(out).isLessThan(3_400.0)
    }

    @Test
    fun `the presentation lead wraps with the loop`() {
        // 500 ms loop; a reading right at the end: the lead must not draw past Out.
        val smoother = smooth(lead = 16_666_667L)
        val sample = ClockSample().apply {
            queueItemId = 1L
            speed = 1f
            playing = true
            looping = true
            loopInMs = 1_000.0
            loopOutMs = 1_500.0
            positionMs = 1_495L
        }
        val out = smoother.advance(1_000_000_000L, sample)
        // 1495 + 16.7 = 1511.7: wrapped to 1011.7.
        assertThat(out).isWithin(0.5).of(1_011.7)
    }

    @Test
    fun `without a loop the behaviour is the old one`() {
        val smoother = smooth()
        val sample = ClockSample().apply {
            queueItemId = 1L
            speed = 1f
            playing = true
            positionMs = 5_000L
            // Stale bounds from an earlier loop must not matter while looping is false.
            loopInMs = 1_000.0
            loopOutMs = 1_100.0
        }
        assertThat(smoother.advance(1_000_000_000L, sample)).isEqualTo(5_000.0)
        sample.positionMs = 9_000L
        smoother.advance(1_016_666_667L, sample)
        assertThat(smoother.lastSnap).isEqualTo(PositionSmoother.SNAP_JUMP_AHEAD)
        assertThat(floor(smoother.advance(1_033_333_334L, sample))).isAtLeast(9_000.0)
    }

    private companion object {
        const val WARM_UP_FRAMES = 90
    }
}
