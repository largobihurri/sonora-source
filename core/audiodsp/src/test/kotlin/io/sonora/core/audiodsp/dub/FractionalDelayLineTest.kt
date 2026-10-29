package io.sonora.core.audiodsp.dub

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.audiodsp.dub.DubSignals.TWO_PI
import io.sonora.core.audiodsp.dub.DubSignals.argMaxAbs
import io.sonora.core.audiodsp.dub.DubSignals.burst
import io.sonora.core.audiodsp.dub.DubSignals.energy
import io.sonora.core.audiodsp.dub.DubSignals.onset
import io.sonora.core.audiodsp.dub.DubSignals.sum
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import org.junit.Test

/** The fourteen delay cases ported from the developer's own siren engine, plus a 44.1 kHz Hermite case. */
class FractionalDelayLineTest {

    private val rate = 48_000f

    /** Settled line: setters applied, then reset() snaps the smoothed time and mix. */
    private fun makeLine(timeMs: Float, feedback: Float, mix: Float, sampleRate: Float = rate) =
        FractionalDelayLine().apply {
            prepare(sampleRate)
            setTimeMs(timeMs)
            setFeedback(feedback)
            setMix(mix)
            reset()
        }

    private fun renderImpulse(line: FractionalDelayLine, amplitude: Float, n: Int) =
        FloatArray(n) { line.process(if (it == 0) amplitude else 0f) }

    private fun renderBurst(
        line: FractionalDelayLine,
        hz: Double,
        amplitude: Double,
        burstFrames: Int,
        n: Int,
        sampleRate: Float = rate,
    ) = FloatArray(n) { line.process(burst(hz, amplitude, it, burstFrames, sampleRate.toDouble())) }

    @Test
    fun `defaults are the ported values`() {
        val line = FractionalDelayLine()
        assertThat(line.timeMs).isEqualTo(375f)
        assertThat(line.feedback).isEqualTo(0.5f)
        assertThat(line.mix).isEqualTo(0.35f)
    }

    @Test
    fun `an impulse echoes at the delay time and decays geometrically`() {
        val feedback = 0.6f
        val mix = 0.5f
        val impulse = 0.25f // small: the saturator is linear here
        val delayFrames = 18_000 // 375 ms at 48 kHz
        val line = makeLine(375f, feedback, mix)
        val out = renderImpulse(line, impulse, 4 * delayFrames + 100)

        // Dry part only at t = 0, then silence until the first echo.
        assertThat(out[0]).isWithin(1e-7f).of((1f - mix) * impulse)
        for (i in 1 until delayFrames - 1) assertWithMessage("sample $i").that(out[i]).isEqualTo(0f)

        var previousArea = 0.0
        for (k in 1..4) {
            val at = k * delayFrames
            // Each echo starts exactly one delay time after the previous one...
            assertWithMessage("echo $k onset").that(abs(onset(out, at - 50, at + 50) - at)).isAtMost(1)
            // ...and the damping low-pass smears it by its group delay a / (1 - a), about 1.25 samples per repeat,
            // so the peak trails the onset slightly on later echoes.
            val peak = argMaxAbs(out, at - 50, at + 50)
            assertWithMessage("echo $k peak").that(peak).isAtLeast(at - 1)
            assertWithMessage("echo $k peak").that(peak).isAtMost(at + 2 * (k - 1) + 1)
            // The unity-DC-gain damping preserves the area over the echo, so it follows mix * feedback^(k-1) even
            // though later echoes are softened in peak.
            val area = sum(out, at - 2, at + 24)
            val expected = mix * impulse * feedback.toDouble().pow(k - 1)
            assertWithMessage("echo $k area").that(area).isWithin(0.05 * expected).of(expected)
            if (k > 1) assertWithMessage("echo $k ratio").that(area / previousArea).isWithin(0.05 * feedback).of(feedback.toDouble())
            previousArea = area
        }
        // The first echo is an exact (undamped) copy.
        assertThat(out[delayFrames]).isWithin(1e-7f).of(mix * impulse)
    }

    @Test
    fun `changing the time mid-stream glides instead of clicking`() {
        val hz = 100.0 // 480-sample period
        val amplitude = 0.5
        val line = makeLine(375f, 0f, 1f)
        val switchAt = 48_000 + 120 // a quarter period past a whole second
        val out = FloatArray(3 * 48_000) { i ->
            if (i == switchAt) line.setTimeMs(750f)
            line.process((amplitude * sin(TWO_PI * hz * i / rate)).toFloat())
        }
        // An un-smoothed jump from 375 ms to 750 ms would read half a period (37.5 periods) away: the output would
        // flip sign at the peak.
        fun delayed(n: Int, ms: Double) = amplitude * sin(TWO_PI * hz * (n / rate.toDouble() - ms / 1000.0))
        val naiveJump = abs(delayed(switchAt, 750.0) - delayed(switchAt - 1, 375.0))
        assertThat(naiveJump).isGreaterThan(0.9)

        var maxStep = 0f
        for (i in 18_001 until out.size) maxStep = maxOf(maxStep, abs(out[i] - out[i - 1]))
        // The glide is a pitch bend (the read head slews), never a discontinuity...
        assertThat(maxStep).isLessThan(0.1f)
        // ...and it does arrive: two seconds later the wet signal is the 750 ms delayed sine.
        val late = 3 * 48_000 - 1
        assertThat(out[late].toDouble()).isWithin(1e-3).of(delayed(late, 750.0))
    }

    @Test
    fun `feedback above one self-oscillates but stays bounded`() {
        val line = makeLine(375f, 1.2f, 0.5f)
        val seconds = 20
        val out = renderBurst(line, 440.0, 0.1, 2_400, seconds * 48_000)
        var peak = 0f
        for (s in out) {
            assertThat(s.isFinite()).isTrue()
            peak = maxOf(peak, abs(s))
        }
        // mix * (saturated loop) + (1 - mix) * silence after the burst.
        assertThat(peak).isAtMost(1f)
        // The loop keeps ringing at a healthy level long after the input stopped...
        val lastSecond = (seconds - 1) * 48_000
        val rmsLast = sqrt(energy(out, lastSecond, seconds * 48_000) / 48_000.0)
        assertThat(rmsLast).isGreaterThan(0.1)
        // ...and has not died down compared with ten seconds earlier.
        val rmsEarlier = sqrt(energy(out, lastSecond - 10 * 48_000, lastSecond - 9 * 48_000) / 48_000.0)
        assertThat(rmsLast).isGreaterThan(0.5 * rmsEarlier)
    }

    @Test
    fun `DC input does not accumulate in the loop`() {
        val dc = 0.5f
        val line = makeLine(100f, 0.95f, 1f)
        var last = 0f
        repeat(10 * 48_000) { last = line.process(dc) }
        // Without the blocker the loop would settle at dc / (1 - 0.95) = 10. With it, only the first (direct) echo
        // of the DC remains.
        assertThat(last).isWithin(0.02f).of(dc)
    }

    @Test
    fun `damping darkens each repeat`() {
        val delayFrames = 18_000
        val burstFrames = 960
        fun decayRatio(hz: Double): Double {
            val line = makeLine(375f, 0.8f, 1f)
            val out = renderBurst(line, hz, 0.25, burstFrames, 4 * delayFrames + 2 * burstFrames)
            val first = energy(out, delayFrames, delayFrames + 2 * burstFrames)
            val fourth = energy(out, 4 * delayFrames, 4 * delayFrames + 2 * burstFrames)
            return fourth / first
        }
        val low = decayRatio(200.0)
        val high = decayRatio(8_000.0)
        // Low frequencies decay by feedback^2 per repeat in energy; highs much faster.
        val expected = 0.8.pow(6.0)
        assertThat(low).isWithin(0.1 * expected).of(expected)
        assertThat(high).isLessThan(0.1 * low)
    }

    @Test
    fun `mix zero is exactly dry and mix one has no dry`() {
        val dry = makeLine(20f, 0.8f, 0f)
        val wet = makeLine(20f, 0.8f, 1f)
        for (i in 0 until 48_000) {
            val input = 0.5f * sin(0.01f * i)
            assertWithMessage("sample $i").that(dry.process(input)).isEqualTo(input)
            val out = wet.process(input)
            // Before the first echo (20 ms = 960 samples) a wet-only output is exact silence.
            if (i < 960) assertWithMessage("sample $i").that(out).isEqualTo(0f)
        }
    }

    @Test
    fun `setters clamp and reject NaN`() {
        val line = FractionalDelayLine()
        line.setTimeMs(1f)
        assertThat(line.timeMs).isEqualTo(FractionalDelayLine.MIN_TIME_MS)
        line.setTimeMs(5_000f)
        assertThat(line.timeMs).isEqualTo(FractionalDelayLine.MAX_TIME_MS)
        line.setTimeMs(Float.POSITIVE_INFINITY)
        assertThat(line.timeMs).isEqualTo(FractionalDelayLine.MAX_TIME_MS)
        line.setTimeMs(Float.NaN)
        assertThat(line.timeMs).isEqualTo(FractionalDelayLine.MIN_TIME_MS)

        line.setFeedback(-1f)
        assertThat(line.feedback).isEqualTo(0f)
        line.setFeedback(3f)
        assertThat(line.feedback).isEqualTo(FractionalDelayLine.MAX_FEEDBACK)
        line.setFeedback(Float.NaN)
        assertThat(line.feedback).isEqualTo(0f)

        line.setMix(-0.5f)
        assertThat(line.mix).isEqualTo(0f)
        line.setMix(2f)
        assertThat(line.mix).isEqualTo(1f)
        line.setMix(Float.NaN)
        assertThat(line.mix).isEqualTo(0f)

        // A smaller buffer caps the time at its capacity.
        line.prepare(rate, 0.5f)
        line.setTimeMs(1_500f)
        assertThat(line.timeMs).isEqualTo(500f)
        // Invalid sample rates are ignored and leave a working line.
        line.prepare(0f)
        line.prepare(Float.NaN)
        line.setMix(1f)
        line.reset()
        repeat(1_000) { assertThat(line.process(0.5f).isFinite()).isTrue() }
    }

    @Test
    fun `non-finite input is treated as silence`() {
        val line = makeLine(20f, 1f, 1f)
        line.process(Float.NaN)
        line.process(Float.POSITIVE_INFINITY)
        for (i in 0 until 48_000) assertWithMessage("sample $i").that(line.process(0f)).isEqualTo(0f)
    }

    @Test
    fun `reset clears the tail`() {
        val line = makeLine(100f, 1.2f, 1f)
        renderBurst(line, 440.0, 0.5, 2_400, 48_000)
        line.reset()
        for (i in 0 until 3 * 48_000) assertWithMessage("sample $i").that(line.process(0f)).isEqualTo(0f)
    }

    @Test
    fun `a decaying tail reaches exact silence`() {
        val line = makeLine(20f, 0.5f, 0.35f)
        renderBurst(line, 440.0, 0.5, 2_400, 2_400)
        // -6 dB per 20 ms repeat: far below the flush threshold after a few seconds.
        repeat(5 * 48_000) { line.process(0f) }
        for (i in 0 until 48_000) assertWithMessage("sample $i").that(line.process(0f)).isEqualTo(0f)
    }

    /**
     * Re-running prepare() on a live configuration (a new stream rate after a format change): the set time must be
     * re-clamped to the new buffer and every read must stay inside it, even mid-glide.
     */
    @Test
    fun `re-prepare at 96 kHz with a smaller capacity re-clamps the time and stays in bounds`() {
        val line = FractionalDelayLine()
        line.prepare(48_000f)
        line.setTimeMs(FractionalDelayLine.MAX_TIME_MS)
        line.setFeedback(1.2f)
        line.setMix(1f)
        renderBurst(line, 440.0, 0.5, 2_400, 4_800) // mid-glide towards the maximum

        line.prepare(96_000f, 0.5f)
        assertThat(line.timeMs).isEqualTo(500f)
        // The echo lands at the re-clamped time on the new rate (impulse onset, +-1 sample).
        line.setFeedback(0f)
        line.reset()
        val echo = renderImpulse(line, 1f, 60_000)
        assertThat(abs(argMaxAbs(echo, 1, 60_000) - 48_000)).isAtMost(1)
    }

    @Test
    fun `any sequence of prepares keeps reads finite and bounded`() {
        val line = FractionalDelayLine()
        val rates = floatArrayOf(8_000f, 192_000f, 44_100f, 22_050f, 96_000f, 48_000f)
        val maxSeconds = floatArrayOf(2f, 0.02f, 1f, 0.03f, 2.5f, 0.5f)
        for (round in rates.indices) {
            line.setTimeMs(FractionalDelayLine.MAX_TIME_MS) // always ask for more than the next buffer holds
            line.prepare(rates[round], maxSeconds[round])
            assertWithMessage("round $round").that(line.timeMs).isAtMost(maxSeconds[round] * 1_000f)
            line.setFeedback(1.2f)
            line.setMix(1f)
            line.setTimeMs(FractionalDelayLine.MAX_TIME_MS)
            for (i in 0 until 20_000) {
                val y = line.process(if (i % 97 == 0) 1f else 0f)
                assertWithMessage("round $round sample $i").that(y.isFinite()).isTrue()
                // Wet <= |in| + |saturated loop| = 2, plus a small interpolation overshoot.
                assertWithMessage("round $round sample $i").that(abs(y)).isAtMost(2.5f)
            }
        }
    }

    @Test
    fun `the saturator is transparent for small signals and bounded`() {
        assertThat(SoftSaturator.process(0.01f)).isWithin(1e-6f).of(0.01f)
        assertThat(SoftSaturator.process(0.1f)).isWithin(1e-4f).of(tanh(0.1f))
        assertThat(SoftSaturator.process(3f)).isEqualTo(1f)
        assertThat(SoftSaturator.process(1e9f)).isEqualTo(1f)
        assertThat(SoftSaturator.process(-1e9f)).isEqualTo(-1f)
        var previous = -1f
        var x = -4f
        while (x <= 4f) {
            val y = SoftSaturator.process(x)
            assertWithMessage("x $x").that(y).isAtLeast(previous) // monotonic
            assertThat(abs(y)).isAtMost(SoftSaturator.BOUND)
            previous = y
            x += 0.01f
        }
    }

    @Test
    fun `a fractional delay at 44_1 kHz reads a sine with Hermite accuracy`() {
        val sampleRate = 44_100f
        val hz = 1_000.0
        val line = makeLine(375f, 0f, 1f, sampleRate) // 16537.5 frames: half-way between two samples
        assertThat(line.currentDelayFrames).isEqualTo(16_537.5)
        val out = FloatArray(2 * 44_100) { line.process(DubSignals.sine(hz, 0.5, it, sampleRate.toDouble())) }
        var worst = 0.0
        for (n in 20_000 until out.size) {
            val expected = 0.5 * sin(TWO_PI * hz * (n - 16_537.5) / sampleRate)
            worst = maxOf(worst, abs(out[n] - expected))
        }
        // Linear interpolation would be 0.25 % low at 1 kHz here (an error of 1.3e-3); Hermite is about 1e-5 off.
        assertThat(worst).isLessThan(5e-4)
    }
}
