package io.sonora.core.audiodsp.dub

import com.google.common.truth.Truth.assertThat
import io.sonora.core.audiodsp.dub.DubSignals.db
import io.sonora.core.audiodsp.dub.DubSignals.rms
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

/** The DUB stage's signal flow (design §3.3, §10.1): idle passthrough, the pad ramps, the throw, fuzzing, re-prepare. */
class DubEngineTest {

    private val rate = 48_000
    private val tenMs = rate / 100

    private fun engine(channels: Int = 2) = DubEngine().apply { prepare(rate, channels) }

    /** Interleaved stereo [frames] of a sine (left) and its inverse at half level (right). */
    private fun stereoSine(hz: Double, frames: Int, amplitude: Double = 0.5) = FloatArray(frames * 2) { i ->
        val s = DubSignals.sine(hz, amplitude, i / 2, rate.toDouble())
        if (i % 2 == 0) s else -0.5f * s
    }

    private fun left(x: FloatArray) = FloatArray(x.size / 2) { x[2 * it] }

    /** Runs [input] through [engine] in blocks of [block] frames, calling [between] before each block. */
    private fun run(
        engine: DubEngine,
        input: FloatArray,
        channels: Int = 2,
        block: Int = 256,
        between: (frame: Int) -> Unit = {},
    ): FloatArray {
        val out = input.copyOf()
        val frames = input.size / channels
        var f = 0
        while (f < frames) {
            between(f)
            val n = minOf(block, frames - f)
            engine.process(out, f * channels, n)
            f += n
        }
        return out
    }

    @Test
    fun `idle output equals the input bit for bit in stereo and mono`() {
        val noise = DubSignals.Noise(7, 0.9f)
        val stereo = FloatArray(4_800 * 2) { noise.next() }
        assertThat(run(engine(), stereo)).isEqualTo(stereo)
        val mono = FloatArray(4_800) { noise.next() }
        assertThat(run(engine(1), mono, channels = 1)).isEqualTo(mono)
        assertThat(engine().isIdle).isTrue()
    }

    @Test
    fun `CUT mutes the dry signal within 10 ms and gives it back within 10 ms`() {
        val dub = engine()
        val input = stereoSine(1_000.0, rate / 2)
        val pressAt = 4_096
        val releaseAt = 12_288
        val out = run(dub, input) { f ->
            if (f == pressAt) dub.setCut(true)
            if (f == releaseAt) dub.setCut(false)
        }
        for (f in pressAt + tenMs until releaseAt) assertThat(abs(out[2 * f])).isEqualTo(0f)
        for (f in releaseAt + tenMs until input.size / 2) assertThat(out[2 * f]).isEqualTo(input[2 * f])
        // The ramp is a ramp: no frame jumps by more than the sine itself does between two frames plus a ramp step.
        assertThat(DubSignals.maxStep(left(out), pressAt - 1, pressAt + tenMs)).isLessThan(0.08f)
    }

    @Test
    fun `DROP kills the low ways within 10 ms and leaves the top`() {
        val dub = engine()
        val low = stereoSine(60.0, rate)
        val pressAt = 16_384
        val out = left(run(dub, low) { f -> if (f == pressAt) dub.setDrop(true) })
        val before = rms(out, pressAt - 4_800, pressAt)
        val after = rms(out, pressAt + tenMs, pressAt + 6 * tenMs)
        assertThat(db(after / before)).isLessThan(-30.0)

        val top = engine()
        val high = stereoSine(3_000.0, rate / 2)
        val kept = left(run(top, high) { f -> if (f == 8_192) top.setDrop(true) })
        assertThat(db(rms(kept, 8_192 + 2 * tenMs, 8_192 + 8 * tenMs) / rms(kept, 4_096, 8_192))).isWithin(1.0).of(0.0)
    }

    @Test
    fun `a throw sends the input into the echo and its first repeat lands one delay later`() {
        val dub = engine()
        dub.setTempo(120.0)
        dub.setDivision(EchoDivision.QUARTER) // 0.5 s = 24 000 frames
        dub.setFeedback(0f)
        dub.setReturnLevel(1f)
        val frames = rate
        val clickAt = 2_000
        val input = FloatArray(frames * 2).also { it[2 * clickAt] = 0.5f; it[2 * clickAt + 1] = 0.5f }
        val out = left(run(dub, input) { f ->
            if (f == 0) {
                dub.setCut(true)
                assertThat(dub.throwDown(Double.NaN)).isNotNull()
            }
            if (f == 8_192) dub.throwUp(Double.NaN)
        })
        val repeat = DubSignals.argMaxAbs(out, clickAt + 1_000, frames)
        assertThat(repeat - clickAt).isEqualTo(24_000)
        assertThat(abs(out[repeat])).isGreaterThan(0.3f)
        assertThat(dub.isIdle).isFalse()
    }

    @Test
    fun `a repeat after a silent gap longer than the ring's hold is not lost`() {
        val dub = engine()
        dub.setTempo(60.0)
        dub.setDivision(EchoDivision.HALF) // 2 s
        dub.setFeedback(0.5f)
        dub.setCut(true)
        val frames = rate * 5
        val input = stereoSine(440.0, frames)
        val out = left(run(dub, input) { f ->
            if (f == 0) dub.throwDown(Double.NaN)
            if (f == 2_560) dub.throwUp(Double.NaN)
        })
        // Repeats at 2 s and 4 s, with 2 s of silence between them.
        assertThat(rms(out, 2 * rate + 600, 2 * rate + 2_000)).isGreaterThan(0.05)
        assertThat(rms(out, 4 * rate + 600, 4 * rate + 2_000)).isGreaterThan(0.02)
    }

    @Test
    fun `FREEZE closes the throw and a throw while frozen is ignored`() {
        val dub = engine()
        dub.setFreeze(true)
        assertThat(dub.throwDown(Double.NaN)).isNull()
        val input = stereoSine(440.0, rate / 4)
        assertThat(run(dub, input)).isEqualTo(input) // nothing was sent, nothing comes back
    }

    @Test
    fun `DUB STOP fades the dry signal out over 20 ms and lets the echo ring`() {
        val dub = engine()
        dub.setTempo(120.0)
        dub.setDivision(EchoDivision.EIGHTH) // 12 000 frames
        dub.setFeedback(0.5f)
        val input = stereoSine(440.0, rate)
        val stopAt = 18_432
        val out = left(run(dub, input) { f ->
            if (f == 0) dub.throwDown(Double.NaN)
            if (f == stopAt) dub.setDryMuted(true)
        })
        assertThat(dub.throwHeld).isFalse() // STOP closes the send, so the tail can end
        // After 20 ms only the echo is left, and it is still ringing.
        assertThat(rms(out, stopAt + 2 * tenMs, stopAt + 12_000)).isGreaterThan(0.01)
        val dry = DubEngine().apply { prepare(rate, 2); setDryMuted(true) }
        val muted = left(run(dry, input))
        for (f in 2 * tenMs until muted.size) assertThat(abs(muted[f])).isEqualTo(0f)
    }

    @Test
    fun `random pad fuzzing never gives a non-finite or runaway sample`() {
        val random = Random(42)
        val dub = engine()
        dub.setTempo(174.0)
        val noise = DubSignals.Noise(3, 1f)
        val frames = rate * 8
        val input = FloatArray(frames * 2) { i ->
            when (random.nextInt(4_000)) {
                0 -> Float.NaN
                1 -> Float.POSITIVE_INFINITY
                else -> noise.next()
            }
        }
        val out = run(dub, input, block = 333) {
            repeat(random.nextInt(3)) {
                when (random.nextInt(12)) {
                    0 -> dub.throwDown(Double.NaN)
                    1 -> dub.throwUp(Double.NaN)
                    2 -> dub.setCut(random.nextBoolean())
                    3 -> dub.setDrop(random.nextBoolean())
                    4 -> dub.setFreeze(random.nextBoolean())
                    5 -> dub.setRunaway(random.nextBoolean())
                    6 -> dub.setFilter(random.nextFloat() * 2 - 1, random.nextFloat())
                    7 -> dub.setKilled(random.nextInt(5), random.nextBoolean())
                    8 -> dub.setDivision(EchoDivision.entries.random(random))
                    9 -> dub.setFeedback(random.nextFloat())
                    10 -> dub.setWay(random.nextInt(5), 20f + random.nextFloat() * 2_000f, 20_000f * random.nextFloat(), 2f * random.nextFloat())
                    else -> dub.releaseAll()
                }
            }
        }
        for (x in out) {
            assertThat(x.isFinite()).isTrue()
            assertThat(abs(x)).isAtMost(DubEngine.OUTPUT_BOUND)
        }
    }

    @Test
    fun `release all lets every pad go and the stage returns to idle`() {
        val dub = engine()
        dub.throwDown(Double.NaN)
        dub.setCut(true)
        dub.setRunaway(true)
        dub.setKilled(2, true)
        dub.setFilter(0.8f, 0.5f)
        dub.releaseAll()
        assertThat(dub.throwHeld).isFalse()
        assertThat(dub.isKilled(2)).isFalse()
        val silence = FloatArray(rate * 2 * 4)
        run(dub, silence)
        assertThat(dub.isIdle).isTrue()
    }

    @Test
    fun `a prepare in the same format keeps the tail and a new format clears it`() {
        val dub = engine()
        dub.setTempo(120.0)
        dub.setDivision(EchoDivision.SIXTEENTH) // 6 000 frames
        dub.setFeedback(0.8f)
        val burst = stereoSine(440.0, 4_000)
        run(dub, burst) { f -> if (f == 0) dub.throwDown(Double.NaN) }
        dub.throwUp(Double.NaN)
        assertThat(dub.prepare(rate, 2)).isFalse()
        val tail = left(run(dub, FloatArray(rate)))
        assertThat(rms(tail, 0, tail.size)).isGreaterThan(0.01)

        assertThat(dub.prepare(44_100, 2)).isTrue()
        val after = run(dub, FloatArray(44_100))
        assertThat(after.all { it == 0f }).isTrue()
        assertThat(dub.isIdle).isTrue()
    }
}
