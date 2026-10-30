package io.sonora.core.playback.equalizer

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test

/** The look-ahead limiter on generated frames. */
class PeakLimiterTest {

    private val rate = 48_000
    private val limiter = PeakLimiter().apply { configure(rate, 2) }
    private val lookahead = limiter.latencyFrames
    private val ceiling = PeakLimiter.CEILING.toDouble()

    /** Runs interleaved stereo [input] through the limiter, frame by frame, into a new array. */
    private fun run(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        for (frame in 0 until input.size / 2) limiter.process(input, frame * 2, out, frame * 2)
        return out
    }

    /** Stereo frames: [left] and [right] as functions of the frame index. */
    private fun stereo(frames: Int, left: (Int) -> Float, right: (Int) -> Float = left) =
        FloatArray(frames * 2) { if (it % 2 == 0) left(it / 2) else right(it / 2) }

    private fun ms(milliseconds: Int) = rate * milliseconds / 1_000

    @Test
    fun `its look-ahead is about 4 ms`() {
        assertThat(lookahead).isEqualTo(192)
    }

    @Test
    fun `a burst 6 dB over full scale never comes out above the ceiling`() {
        val burst = ms(100) until ms(150)
        val input = stereo(ms(400), { f ->
            val amplitude = if (f in burst) 2.0 else 0.5
            (amplitude * sin(2 * PI * 1_000 * f / rate)).toFloat()
        })

        val output = run(input)

        for (sample in output) assertThat(abs(sample.toDouble())).isAtMost(ceiling + 1e-6)
        // Limited, not crushed: the burst still reaches the ceiling.
        assertThat(output.maxOf { abs(it) }.toDouble()).isGreaterThan(ceiling - 0.01)
    }

    @Test
    fun `a signal below the ceiling comes out unchanged, only later by the look-ahead`() {
        val input = stereo(ms(200), { f -> (0.95 * sin(2 * PI * 440 * f / rate)).toFloat() }, { f -> ((f * 7_919) % 1_000 - 500) / 600f })

        val output = run(input)

        for (i in 0 until lookahead * 2) assertThat(output[i]).isEqualTo(0f)
        for (i in lookahead * 2 until output.size) assertThat(output[i]).isEqualTo(input[i - lookahead * 2])
    }

    @Test
    fun `frames taken without limiting come out unchanged even over the ceiling, and the gain recovers to unity`() {
        // A burst limited first, then over-ceiling frames that ask for no reduction (the release reaches exact unity
        // within about 13 time constants, 650 ms).
        val burst = stereo(ms(20), { 1.5f })
        val out = FloatArray(burst.size)
        for (frame in 0 until burst.size / 2) limiter.process(burst, frame * 2, out, frame * 2)
        val loud = stereo(ms(1_000), { f -> if ((f / 50) % 2 == 0) 0.999f else -0.999f })
        val passed = FloatArray(loud.size)
        for (frame in 0 until loud.size / 2) limiter.process(loud, frame * 2, passed, frame * 2, limit = false)

        assertThat(limiter.gain).isEqualTo(1.0)
        val tail = ms(800) * 2
        for (i in tail until passed.size) assertThat(passed[i]).isEqualTo(loud[i - lookahead * 2])
    }

    @Test
    fun `the gain falls before the peak arrives and recovers smoothly to unity`() {
        val burstStart = ms(100)
        val burstEnd = ms(120)
        val input = stereo(ms(1_200), { f -> if (f in burstStart until burstEnd) 2f else 0.25f })

        val output = run(input)
        fun gainAt(frame: Int) = output[(frame + lookahead) * 2] / input[frame * 2].toDouble()

        // Already well down on the last quiet frame before the burst, reached gradually.
        assertThat(gainAt(burstStart - 1)).isLessThan(0.6)
        assertThat(gainAt(burstStart - lookahead / 2)).isWithin(0.05).of((1 + PeakLimiter.CEILING / 2.0) / 2)
        // Released over tens of milliseconds, not at once.
        assertThat(gainAt(burstEnd + ms(20))).isLessThan(0.8)
        assertThat(gainAt(burstEnd + ms(300))).isGreaterThan(0.99)
        for (frame in burstEnd + 1 until ms(1_000)) assertThat(gainAt(frame)).isAtLeast(gainAt(frame - 1))
        // Then exactly unity again.
        assertThat(limiter.gain).isEqualTo(1.0)
        for (frame in ms(1_000) until ms(1_200) - lookahead) assertThat(output[(frame + lookahead) * 2]).isEqualTo(0.25f)
    }

    @Test
    fun `both channels get the same gain`() {
        val input = stereo(ms(200), { f -> if (f in ms(100) until ms(110)) 2f else 0.1f }, { 0.1f })

        val output = run(input)

        for (frame in lookahead until ms(200)) {
            val leftGain = output[frame * 2] / input[(frame - lookahead) * 2].toDouble()
            assertThat(output[frame * 2 + 1].toDouble()).isWithin(1e-7).of(0.1 * leftGain)
        }
        assertThat(output[(ms(105) + lookahead) * 2 + 1].toDouble()).isWithin(1e-4).of(0.1 * ceiling / 2)
    }

    @Test
    fun `reset forgets the delayed audio and the gain`() {
        run(stereo(ms(50), { 2f }))
        limiter.reset()

        assertThat(limiter.gain).isEqualTo(1.0)
        assertThat(limiter.primed).isFalse()
        val input = stereo(ms(20), { 0.5f })
        val output = run(input)

        for (i in 0 until lookahead * 2) assertThat(output[i]).isEqualTo(0f)
        for (i in lookahead * 2 until output.size) assertThat(output[i]).isEqualTo(0.5f)
        assertThat(limiter.primed).isTrue()
    }

    @Test
    fun `the output may be the input array`() {
        val input = stereo(ms(20), { f -> f / 1_000f })
        val inPlace = input.copyOf()

        val expected = run(input)
        limiter.reset()
        for (frame in 0 until inPlace.size / 2) limiter.process(inPlace, frame * 2, inPlace, frame * 2)

        assertThat(inPlace).isEqualTo(expected)
    }
}
