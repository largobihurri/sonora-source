package io.sonora.core.audiodsp.resample

import com.google.common.truth.Truth.assertThat
import io.sonora.core.audiodsp.resample.Signals.amplitudeAt
import io.sonora.core.audiodsp.resample.Signals.channel
import io.sonora.core.audiodsp.resample.Signals.db
import io.sonora.core.audiodsp.resample.Signals.resampleAll
import io.sonora.core.audiodsp.resample.Signals.rms
import io.sonora.core.audiodsp.resample.Signals.sines
import io.sonora.core.audiodsp.resample.Signals.zeroCrossingFrequency
import kotlin.math.abs
import org.junit.Test

/**
 * The DJ analysis engine resamples 44.1 kHz and 48 kHz music to 22.05 kHz mono before it computes features; these
 * cases pin the conversions it relies on: the exact output length, where a sample lands in time (the filter is
 * linear-phase and centred on the output instant, so the group delay is zero input frames), block-size invariance
 * and the preservation of a passband tone.
 */
class PolyphaseResamplerDjRatesTest {

    private val out = 22_050

    /** Output frames to ignore at each end: the filter's start-up and run-out. */
    private fun edge(r: PolyphaseResampler) = r.tapsPerPhase

    @Test
    fun `the DJ ratios reduce to 1 over 2 and 147 over 320`() {
        PolyphaseResampler(44_100, out, 1).let {
            assertThat(it.upFactor to it.downFactor).isEqualTo(1 to 2)
        }
        PolyphaseResampler(48_000, out, 1).let {
            assertThat(it.upFactor to it.downFactor).isEqualTo(147 to 320)
        }
    }

    @Test
    fun `44_1 kHz to 22_05 kHz yields exactly ceil of half the input frames`() {
        for (frames in listOf(0, 1, 2, 3, 4_409, 4_410, 4_411, 44_100, 44_101)) {
            for (channels in listOf(1, 2)) {
                val r = PolyphaseResampler(44_100, out, channels)
                val y = resampleAll(r, FloatArray(frames * channels), 1_000)

                val expected = (frames + 1) / 2 // ceil(frames * 1 / 2)
                assertThat(y.size / channels).isEqualTo(expected)
                assertThat(r.outputFrameCount(frames.toLong())).isEqualTo(expected.toLong())
            }
        }
    }

    @Test
    fun `48 kHz to 22_05 kHz yields exactly ceil of 147 over 320 of the input frames`() {
        for (frames in listOf(0, 1, 2, 3, 319, 320, 321, 4_800, 48_000, 48_001, 100_003)) {
            for (channels in listOf(1, 2)) {
                val r = PolyphaseResampler(48_000, out, channels)
                val y = resampleAll(r, FloatArray(frames * channels), 1_000)

                val expected = (frames.toLong() * 147 + 319) / 320
                assertThat((y.size / channels).toLong()).isEqualTo(expected)
                assertThat(r.outputFrameCount(frames.toLong())).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `a click at an even input frame lands on half that output frame at 44_1 kHz`() {
        for (n in listOf(0, 100, 2_000, 5_000, 8_818)) {
            val input = FloatArray(8_820).also { it[n] = 1f }
            val y = channel(resampleAll(PolyphaseResampler(44_100, out, 1), input, 4_096), 1, 0)

            val peak = y.indices.maxBy { y[it] }
            assertThat(peak).isEqualTo(n / 2)
            // Linear phase: the response around an on-grid click is symmetric.
            if (n in 1 until 8_818) assertThat(abs(y[peak - 1] - y[peak + 1])).isLessThan(1e-4)
        }
    }

    @Test
    fun `a click between two output frames splits its energy evenly at 44_1 kHz`() {
        val n = 4_001 // input time 4001 is output time 2000.5
        val input = FloatArray(8_820).also { it[n] = 1f }
        val y = channel(resampleAll(PolyphaseResampler(44_100, out, 1), input, 4_096), 1, 0)

        assertThat(abs(y[2_000] - y[2_001])).isLessThan(1e-4)
        assertThat(y[2_000]).isGreaterThan(0.3)
        for (m in y.indices) if (m != 2_000 && m != 2_001) assertThat(abs(y[m])).isLessThan(abs(y[2_000]))
    }

    @Test
    fun `a click at an input frame that is a multiple of 320 lands on 147 over 320 of it at 48 kHz`() {
        for (k in listOf(0, 1, 5, 15, 29)) {
            val n = k * 320
            val input = FloatArray(10_240).also { it[n] = 1f }
            val y = channel(resampleAll(PolyphaseResampler(48_000, out, 1), input, 4_096), 1, 0)

            val peak = y.indices.maxBy { y[it] }
            assertThat(peak).isEqualTo(k * 147)
        }
    }

    @Test
    fun `a click at an arbitrary input frame lands within one output frame of its time at 48 kHz`() {
        for (n in listOf(1, 777, 3_201, 5_123, 9_000)) {
            val input = FloatArray(10_240).also { it[n] = 1f }
            val y = channel(resampleAll(PolyphaseResampler(48_000, out, 1), input, 4_096), 1, 0)

            val peak = y.indices.maxBy { y[it] }
            val exact = n * 147.0 / 320
            assertThat(abs(peak - exact)).isAtMost(0.5)
        }
    }

    @Test
    fun `streaming look-ahead is half the filter length and there is no other delay`() {
        for (input in listOf(44_100, 48_000)) {
            val r = PolyphaseResampler(input, out, 1)
            val half = r.tapsPerPhase / 2
            val buffer = FloatArray(r.maxOutputFrames(half + 1))

            // Output 0 is centred on input frame 0, so it needs the frames up to index K / 2: K / 2 + 1 frames.
            assertThat(r.process(FloatArray(half), half, buffer)).isEqualTo(0)
            assertThat(r.process(FloatArray(1), 1, buffer)).isEqualTo(1)
        }
    }

    @Test
    fun `a 1 kHz sine keeps its frequency and amplitude within 0_1 dB`() {
        for (input in listOf(44_100, 48_000)) {
            val r = PolyphaseResampler(input, out, 1)
            val y = channel(resampleAll(r, sines(input, input, 0.5, 1_000.0), 4_096), 1, 0)
            val from = edge(r)
            val to = y.size - edge(r)

            val level = db(amplitudeAt(y, from, to, 1_000.0, out) / 0.5)
            val measuredHz = zeroCrossingFrequency(y, from, to, out)
            println("1 kHz $input Hz -> 22050 Hz: level ${"%.4f".format(level)} dB, frequency ${"%.3f".format(measuredHz)} Hz")
            assertThat(abs(level)).isLessThan(0.1)
            assertThat(abs(measuredHz - 1_000.0) / 1_000.0).isLessThan(0.0005)
        }
    }

    @Test
    fun `a tone above 22_05 kHz Nyquist is suppressed by more than 90 dB`() {
        for ((input, hz) in listOf(44_100 to 13_000.0, 44_100 to 20_000.0, 48_000 to 13_000.0, 48_000 to 23_000.0)) {
            val r = PolyphaseResampler(input, out, 1)
            val y = channel(resampleAll(r, sines(input, input / 2, 0.5, hz), 4_096), 1, 0)

            val level = db(rms(y, edge(r), y.size - edge(r)) * Math.sqrt(2.0) / 0.5)
            println("stopband $input Hz -> 22050 Hz, $hz Hz: ${"%.1f".format(level)} dB")
            assertThat(level).isLessThan(-90.0)
        }
    }

    @Test
    fun `output does not depend on the block size at the DJ rates`() {
        for (input in listOf(44_100, 48_000)) {
            val signal = sines(input, 4_800, 0.7, 1_234.0, 5_678.0)
            val reference = resampleAll(PolyphaseResampler(input, out, 2), signal, 4_096)

            for (chunk in listOf(1, 37, 320, 1_000)) {
                assertThat(resampleAll(PolyphaseResampler(input, out, 2), signal, chunk)).isEqualTo(reference)
            }
        }
    }
}
