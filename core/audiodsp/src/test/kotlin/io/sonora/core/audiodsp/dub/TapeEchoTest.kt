package io.sonora.core.audiodsp.dub

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.audiodsp.dub.DubSignals.TWO_PI
import io.sonora.core.audiodsp.dub.DubSignals.burst
import io.sonora.core.audiodsp.dub.DubSignals.db
import io.sonora.core.audiodsp.dub.DubSignals.energy
import io.sonora.core.audiodsp.dub.DubSignals.maxStep
import io.sonora.core.audiodsp.dub.DubSignals.onset
import io.sonora.core.audiodsp.dub.DubSignals.peak
import io.sonora.core.audiodsp.dub.DubSignals.rms
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertThrows
import org.junit.Test

class TapeEchoTest {

    private val rate = 48_000

    /** A settled mono echo at [bpm] and [division]. */
    private fun makeEcho(
        bpm: Double = 120.0,
        division: EchoDivision = EchoDivision.QUARTER,
        feedback: Float = 0.55f,
        returnLevel: Float = 1f,
        sampleRate: Int = rate,
        channels: Int = 1,
    ) = TapeEcho().apply {
        prepare(sampleRate, channels)
        setTempo(bpm)
        setDivision(division)
        setFeedback(feedback)
        setReturnLevel(returnLevel)
        reset()
    }

    /** Runs a mono echo over [frames] frames; [send] gives the send for a frame and may change the echo's state. */
    private fun render(echo: TapeEcho, frames: Int, send: (Int) -> Float): FloatArray =
        FloatArray(frames) { i ->
            echo.processFrame(send(i), 0f)
            echo.outLeft
        }

    // --- Beat-synced time ---------------------------------------------------------------------------------------

    @Test
    fun `the echo time is sourceRate times 60 over bpm times the division times 4`() {
        val expected = mapOf(
            EchoDivision.SIXTEENTH to 6_000.0,
            EchoDivision.EIGHTH to 12_000.0,
            EchoDivision.DOTTED_EIGHTH to 18_000.0,
            EchoDivision.QUARTER to 24_000.0,
            EchoDivision.DOTTED_QUARTER to 36_000.0,
            EchoDivision.HALF to 48_000.0,
        )
        for ((division, frames) in expected) {
            assertWithMessage("$division").that(EchoTiming.delayFrames(48_000, 120.0, division)).isEqualTo(frames)
        }
        assertThat(EchoTiming.delayFrames(44_100, 124.0, EchoDivision.QUARTER)).isWithin(1e-9).of(44_100 * 60.0 / 124.0)
        assertThat(EchoDivision.entries.map { it.fraction })
            .containsExactly(1.0 / 16, 1.0 / 8, 3.0 / 16, 1.0 / 4, 3.0 / 8, 1.0 / 2).inOrder()
    }

    @Test
    fun `a time longer than 2 s falls back to the next shorter division`() {
        // 60 BPM: a half note is exactly 2.0 s, which is allowed.
        assertThat(EchoTiming.effectiveDivision(48_000, 60.0, EchoDivision.HALF)).isEqualTo(EchoDivision.HALF)
        // 50 BPM: 1/2 is 2.4 s, 3/8 is 1.8 s.
        assertThat(EchoTiming.effectiveDivision(48_000, 50.0, EchoDivision.HALF)).isEqualTo(EchoDivision.DOTTED_QUARTER)
        assertThat(EchoTiming.delayFrames(48_000, 50.0, EchoDivision.HALF)).isWithin(1e-9).of(1.8 * 48_000)
        // 30 BPM: 1/2 is 4 s, 3/8 is 3 s, 1/4 is 2 s.
        assertThat(EchoTiming.effectiveDivision(48_000, 30.0, EchoDivision.HALF)).isEqualTo(EchoDivision.QUARTER)
        // Shorter divisions are untouched.
        assertThat(EchoTiming.effectiveDivision(48_000, 30.0, EchoDivision.EIGHTH)).isEqualTo(EchoDivision.EIGHTH)
        // 5 BPM: even 1/16 is 3 s, so it is clamped to 2.0 s.
        assertThat(EchoTiming.effectiveDivision(48_000, 5.0, EchoDivision.HALF)).isEqualTo(EchoDivision.SIXTEENTH)
        assertThat(EchoTiming.delayFrames(48_000, 5.0, EchoDivision.HALF)).isEqualTo(2.0 * 48_000)
    }

    @Test
    fun `the echo follows the tempo and division and ignores an invalid tempo`() {
        val echo = makeEcho(bpm = 120.0, division = EchoDivision.QUARTER)
        assertThat(echo.delayFrames).isEqualTo(24_000.0)
        echo.setTempo(90.0)
        assertThat(echo.delayFrames).isEqualTo(32_000.0)
        for (bad in doubleArrayOf(Double.NaN, 0.0, -10.0, Double.POSITIVE_INFINITY)) {
            echo.setTempo(bad)
            assertThat(echo.bpm).isEqualTo(90.0)
        }
        echo.setDivision(EchoDivision.DOTTED_EIGHTH)
        assertThat(echo.delayFrames).isEqualTo(24_000.0)
        echo.setTempo(40.0)
        echo.setDivision(EchoDivision.HALF) // 3 s at 40 BPM: falls back to 3/8 (2.25 s), then 1/4 (1.5 s)
        assertThat(echo.effectiveDivision).isEqualTo(EchoDivision.QUARTER)
        assertThat(echo.delayFrames).isEqualTo(72_000.0)
    }

    @Test
    fun `an impulse returns one beat-synced delay later scaled by the return level`() {
        val echo = makeEcho(feedback = 0f, returnLevel = 0.8f)
        val out = render(echo, 30_000) { if (it == 0) 0.5f else 0f }
        for (i in 0 until 24_000) assertWithMessage("frame $i").that(out[i]).isEqualTo(0f)
        assertThat(out[24_000]).isWithin(1e-7f).of(0.4f)
        for (i in 24_001 until 30_000) assertWithMessage("frame $i").that(out[i]).isEqualTo(0f)
    }

    @Test
    fun `the feedback slider is clamped to 0 to 0_95`() {
        val echo = makeEcho()
        echo.setFeedback(1.5f)
        assertThat(echo.feedback).isEqualTo(TapeEcho.MAX_USER_FEEDBACK)
        echo.setFeedback(-0.2f)
        assertThat(echo.feedback).isEqualTo(0f)
        echo.setFeedback(Float.NaN)
        assertThat(echo.feedback).isEqualTo(0f)
        echo.setFeedback(0.7f)
        echo.reset()
        assertThat(echo.currentFeedback).isEqualTo(0.7f)
    }

    @Test
    fun `the return level is smoothed and zero is exact silence`() {
        val echo = makeEcho(feedback = 0.5f, returnLevel = 1f)
        val out = render(echo, 4 * rate) { i ->
            if (i == 2 * rate) echo.setReturnLevel(0f)
            DubSignals.sine(220.0, 0.3, i, rate.toDouble())
        }
        // The return does not jump: it fades over its 20 ms time constant.
        assertThat(maxStep(out, 2 * rate - 10, 2 * rate + 4_800)).isLessThan(0.05f)
        // isWithin(0) also accepts -0.0, which a zero gain can give.
        for (i in 3 * rate until 4 * rate) assertWithMessage("frame $i").that(out[i]).isWithin(0f).of(0f)
    }

    // --- FREEZE -------------------------------------------------------------------------------------------------

    /** A frozen burst's pass levels in dB re pass 1, and the echo output's largest magnitude (NaN if not finite). */
    private class FrozenRun(val levels: DoubleArray, val peak: Float)

    /**
     * Freezes a [multiTone] burst whose true sample peak is [peakDbfs] and measures the echo output energy per pass of
     * the loop over [seconds]; the burst ends before FREEZE, so pass 1 is the plain echo of the send.
     */
    private fun frozenRun(bpm: Double, peakDbfs: Double, seconds: Int): FrozenRun {
        val echo = makeEcho(bpm = bpm, feedback = 0.55f)
        val delay = echo.delayFrames
        val burstFrames = (0.8 * delay).toInt()
        val freezeAt = (0.9 * delay).toInt()
        val unitPeak = (0 until burstFrames).maxOf { abs(multiTone(it, burstFrames, 1.0)) }
        val amplitude = Math.pow(10.0, peakDbfs / 20.0) / unitPeak
        val total = seconds * rate + delay.toInt() * 2
        val out = render(echo, total) { i ->
            if (i == freezeAt) echo.setFreeze(true)
            multiTone(i, burstFrames, amplitude)
        }
        val passes = ((total - delay) / delay).toInt() - 1
        val first = energy(out, delay.toInt(), (2 * delay).toInt())
        val levels = DoubleArray(passes) { k ->
            val from = ((k + 2) * delay).toInt()
            10.0 * kotlin.math.log10(energy(out, from, (from + delay).toInt()) / first)
        }
        val finite = out.all { it.isFinite() }
        return FrozenRun(levels, if (finite) peak(out) else Float.NaN)
    }

    /** 110 + 440 + 1760 + 3520 Hz, Hann-windowed over [burstFrames]: bass to treble program in one burst. */
    private fun multiTone(i: Int, burstFrames: Int, amplitude: Double): Float {
        if (i >= burstFrames) return 0f
        val w = 0.5 - 0.5 * cos(TWO_PI * i / burstFrames)
        var s = 0.0
        for (hz in doubleArrayOf(110.0, 440.0, 1_760.0, 3_520.0)) s += sin(TWO_PI * hz * i / rate)
        return (amplitude * w * s).toFloat()
    }

    @Test
    fun `FREEZE keeps the loop energy constant within 0_5 dB over 10 s at -20, -6 and -3 dBFS peaks`() {
        // Integer delay (120 BPM) and a fractional one (124 BPM, 23225.8 frames). DUB runs before ReplayGain, so a
        // thrown mastered track reaches the loop near full scale: the frozen loop must not compress below its knee.
        for (bpm in doubleArrayOf(120.0, 124.0)) {
            for (peakDbfs in doubleArrayOf(-20.0, -6.0, -3.0)) {
                val run = frozenRun(bpm, peakDbfs, seconds = 10)
                val levels = run.levels.joinToString { "%.3f".format(it) }
                println("FREEZE at $bpm BPM, $peakDbfs dBFS: pass levels $levels dB")
                assertThat(run.levels.size).isAtLeast(19)
                for ((k, level) in run.levels.withIndex()) {
                    assertWithMessage("$bpm BPM, $peakDbfs dBFS, pass ${k + 2}").that(abs(level)).isAtMost(0.5)
                }
                assertWithMessage("$bpm BPM, $peakDbfs dBFS, output peak").that(run.peak.isFinite()).isTrue()
                assertWithMessage("$bpm BPM, $peakDbfs dBFS, output peak").that(run.peak).isAtMost(1f)
            }
        }
    }

    @Test
    fun `engaging and releasing FREEZE at -3 dBFS causes no sample step`() {
        // A 100 Hz tone steps at most 2 pi 100 / 48000 x 0.708 = 0.0093 per sample; switching the loop curve at once
        // would step the held loop by about 0.09. Both switches land while the echo is loud (not at a zero crossing,
        // where the two loop curves agree).
        val hz = 100.0
        val amplitude = 0.708
        for (bpm in doubleArrayOf(120.0, 124.0)) {
            val echo = makeEcho(bpm = bpm, feedback = 0.55f)
            val delay = echo.delayFrames
            val burstFrames = (0.8 * delay).toInt()
            val freezeFrom = (1.2 * delay).toInt()
            val releaseFrom = (5.3 * delay).toInt()
            var frozenAt = -1
            var releasedAt = -1
            val out = render(echo, (8 * delay).toInt()) { i ->
                if (frozenAt < 0 && i >= freezeFrom && abs(echo.outLeft) > 0.3f) {
                    echo.setFreeze(true)
                    frozenAt = i
                }
                if (releasedAt < 0 && i >= releaseFrom && abs(echo.outLeft) > 0.5f) {
                    echo.setFreeze(false)
                    releasedAt = i
                }
                burst(hz, amplitude, i, burstFrames, rate.toDouble())
            }
            assertWithMessage("$bpm BPM freeze").that(frozenAt).isLessThan((1.4 * delay).toInt())
            assertWithMessage("$bpm BPM release").that(releasedAt).isLessThan((5.5 * delay).toInt())
            assertWithMessage("$bpm BPM").that(out.all { it.isFinite() }).isTrue()
            assertWithMessage("$bpm BPM").that(maxStep(out, 0, out.size)).isLessThan(0.012f)
        }
    }

    @Test
    fun `FREEZE closes the input`() {
        fun run(sendAfterFreeze: Boolean): FloatArray {
            val echo = makeEcho()
            return render(echo, 3 * rate) { i ->
                if (i == 12_000) echo.setFreeze(true)
                when {
                    i < 6_000 -> burst(440.0, 0.3, i, 6_000, rate.toDouble())
                    sendAfterFreeze && i >= 12_000 + 480 -> DubSignals.sine(300.0, 0.9, i, rate.toDouble())
                    else -> 0f
                }
            }
        }
        // 10 ms after FREEZE the input gate (5 ms) is shut: a loud send changes nothing.
        assertThat(run(sendAfterFreeze = true)).isEqualTo(run(sendAfterFreeze = false))
    }

    @Test
    fun `releasing FREEZE returns the feedback to the slider over 50 ms`() {
        val echo = makeEcho(feedback = 0.55f)
        echo.setFreeze(true)
        repeat(rate) { echo.processFrame(0f, 0f) }
        assertThat(echo.currentFeedback).isEqualTo(TapeEcho.FREEZE_FEEDBACK)
        echo.setFreeze(false)
        val ramp = FloatArray(2_400) { echo.processFrame(0f, 0f); echo.currentFeedback }
        for (i in 1 until ramp.size) assertThat(ramp[i]).isAtMost(ramp[i - 1])
        assertThat(ramp[1_199]).isWithin(0.01f).of((1f + 0.55f) / 2f)
        assertThat(ramp[2_399]).isEqualTo(0.55f)
        assertThat(ramp[2_300]).isGreaterThan(0.55f)
    }

    // --- RUNAWAY ------------------------------------------------------------------------------------------------

    @Test
    fun `RUNAWAY self-oscillates and stays within the saturator bound`() {
        val returnLevel = 0.8f
        val echo = makeEcho(feedback = 0.55f, returnLevel = returnLevel)
        echo.setRunaway(true)
        val out = render(echo, 12 * rate) { burst(440.0, 0.1, it, 14_400, rate.toDouble()) }
        for ((i, s) in out.withIndex()) {
            assertWithMessage("frame $i").that(s.isFinite()).isTrue()
            // In = 0 once the burst is over, so every ring sample is a saturated loop sample: |wet| <= 1.
            assertWithMessage("frame $i").that(abs(s)).isAtMost(returnLevel * SoftSaturator.BOUND)
        }
        // It keeps growing into a drone instead of dying away.
        assertThat(rms(out, 11 * rate, 12 * rate)).isGreaterThan(0.1)
        assertThat(rms(out, 11 * rate, 12 * rate)).isGreaterThan(rms(out, rate / 2, rate))
    }

    @Test
    fun `RUNAWAY with a loud send and any toggling stays bounded and finite`() {
        val echo = makeEcho(bpm = 137.0, division = EchoDivision.DOTTED_EIGHTH, feedback = 0.95f, channels = 2)
        val noise = DubSignals.Noise(7, 0.9f)
        var worst = 0f
        for (i in 0 until 15 * rate) {
            if (i % 9_000 == 0) echo.setRunaway((i / 9_000) % 3 != 2)
            if (i % 13_000 == 0) echo.setFreeze((i / 13_000) % 4 == 1)
            if (i % 31_000 == 0) echo.setDivision(EchoDivision.entries[(i / 31_000) % EchoDivision.entries.size])
            echo.processFrame(noise.next(), noise.next())
            assertThat(echo.outLeft.isFinite() && echo.outRight.isFinite()).isTrue()
            worst = maxOf(worst, abs(echo.outLeft), abs(echo.outRight))
        }
        // |ring| <= |send| + |saturated loop| = 1.9; Catmull-Rom can overshoot its taps by at most 25 %.
        assertThat(worst).isAtMost(1.25f * (0.9f + SoftSaturator.BOUND))
    }

    @Test
    fun `releasing RUNAWAY glides the feedback back over 250 ms`() {
        val echo = makeEcho(feedback = 0.4f)
        echo.setRunaway(true)
        repeat(rate) { echo.processFrame(0f, 0f) }
        assertThat(echo.currentFeedback).isEqualTo(TapeEcho.RUNAWAY_FEEDBACK)
        echo.setRunaway(false)
        val ramp = FloatArray(12_000) { echo.processFrame(0f, 0f); echo.currentFeedback }
        for (i in 1 until ramp.size) assertThat(ramp[i]).isAtMost(ramp[i - 1])
        assertThat(ramp[5_999]).isWithin(0.01f).of((1.12f + 0.4f) / 2f)
        assertThat(ramp[11_000]).isGreaterThan(0.4f)
        assertThat(ramp[11_999]).isEqualTo(0.4f)
    }

    // --- Division glide -----------------------------------------------------------------------------------------

    @Test
    fun `a division change glides like tape with no discontinuity`() {
        val hz = 100.0
        val amplitude = 0.5
        val echo = makeEcho(feedback = 0f)
        val switchAt = rate + 120
        val back = 3 * rate + 120
        val out = render(echo, 5 * rate) { i ->
            if (i == switchAt) echo.setDivision(EchoDivision.DOTTED_EIGHTH)
            if (i == back) echo.setDivision(EchoDivision.DOTTED_QUARTER)
            (amplitude * sin(TWO_PI * hz * i / rate)).toFloat()
        }
        fun delayed(n: Int, frames: Double) = amplitude * sin(TWO_PI * hz * (n - frames) / rate)
        // Jumping from 1/4 (24000 frames) to 3/16 (18000: 12.5 periods away), or from 3/16 to 3/8 (36000: 37.5
        // periods away), would flip the sine near its peak.
        assertThat(abs(delayed(switchAt, 18_000.0) - delayed(switchAt - 1, 24_000.0))).isGreaterThan(0.9)
        assertThat(abs(delayed(back, 36_000.0) - delayed(back - 1, 18_000.0))).isGreaterThan(0.9)
        assertThat(maxStep(out, 24_001, out.size)).isLessThan(0.1f)
        assertThat(out[3 * rate - 1].toDouble()).isWithin(1e-3).of(delayed(3 * rate - 1, 18_000.0))
        assertThat(out[5 * rate - 1].toDouble()).isWithin(1e-3).of(delayed(5 * rate - 1, 36_000.0))
        assertThat(echo.currentDelayFrames).isEqualTo(36_000.0)
    }

    // --- Shape --------------------------------------------------------------------------------------------------

    @Test
    fun `stereo channels echo independently and the block call matches the frame call`() {
        val a = makeEcho(feedback = 0.6f, channels = 2)
        val b = makeEcho(feedback = 0.6f, channels = 2)
        val frames = 60_000
        val send = FloatArray(frames * 2) { i ->
            val f = i / 2
            if (i % 2 == 0) burst(330.0, 0.4, f, 4_800, rate.toDouble()) else 0f
        }
        val block = FloatArray(frames * 2)
        var done = 0
        while (done < frames) { // uneven block sizes
            val n = minOf(1_237, frames - done)
            a.process(send, done * 2, block, done * 2, n)
            done += n
        }
        for (f in 0 until frames) {
            b.processFrame(send[2 * f], send[2 * f + 1])
            assertWithMessage("frame $f left").that(block[2 * f]).isEqualTo(b.outLeft)
            assertWithMessage("frame $f right").that(block[2 * f + 1]).isEqualTo(b.outRight)
            assertWithMessage("frame $f right").that(b.outRight).isEqualTo(0f)
        }
        assertThat(onset(block.filterIndexed { i, _ -> i % 2 == 0 }.toFloatArray(), 20_000, 30_000)).isAtLeast(24_000)
    }

    @Test
    fun `prepare accepts one or two channels and a positive rate only`() {
        assertThrows(IllegalArgumentException::class.java) { TapeEcho().prepare(48_000, 3) }
        assertThrows(IllegalArgumentException::class.java) { TapeEcho().prepare(48_000, 0) }
        assertThrows(IllegalArgumentException::class.java) { TapeEcho().prepare(0, 2) }
        val echo = makeEcho(sampleRate = 96_000)
        assertThat(echo.delayFrames).isEqualTo(48_000.0)
        assertThat(peak(render(echo, 1_000) { 0f })).isEqualTo(0f)
    }

    @Test
    fun `the line damping can be faded out to make repeats flat`() {
        fun decay(hz: Double, damping: Float): Double {
            val line = FractionalDelayLine().apply {
                prepare(48_000f)
                setTimeMs(375f)
                setFeedback(0.8f)
                setMix(1f)
                setDampingAmount(damping)
                reset()
            }
            val out = FloatArray(4 * 18_000 + 1_920) { line.process(burst(hz, 0.05, it, 960, 48_000.0)) }
            return db(kotlin.math.sqrt(energy(out, 4 * 18_000, 4 * 18_000 + 1_920) / energy(out, 18_000, 18_000 + 1_920)))
        }
        // Three passes at feedback 0.8 are -5.8 dB; full damping takes an 8 kHz burst far lower.
        assertThat(decay(8_000.0, 1f)).isLessThan(-15.0)
        assertThat(decay(8_000.0, 0f)).isWithin(0.1).of(db(0.8 * 0.8 * 0.8))
        assertThat(decay(200.0, 0f)).isWithin(0.1).of(db(0.8 * 0.8 * 0.8))
    }
}
