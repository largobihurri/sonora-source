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
import kotlin.math.ceil
import kotlin.math.roundToLong
import org.junit.Assert.assertThrows
import org.junit.Test

class PolyphaseResamplerTest {

    private val out = 44_100

    /** Output frames to ignore at each end: the filter's start-up and run-out. */
    private fun edge(r: PolyphaseResampler) = r.tapsPerPhase

    @Test
    fun `the conversion ratio is reduced to lowest terms`() {
        fun ratio(input: Int) = PolyphaseResampler(input, out, 1).let { it.upFactor to it.downFactor }

        assertThat(ratio(96_000)).isEqualTo(147 to 320)
        assertThat(ratio(88_200)).isEqualTo(1 to 2)
        assertThat(ratio(192_000)).isEqualTo(147 to 640)
        assertThat(ratio(176_400)).isEqualTo(1 to 4)
        assertThat(ratio(48_000)).isEqualTo(147 to 160)
    }

    @Test
    fun `passband tones keep their frequency and level within 0_1 dB`() {
        for (input in listOf(88_200, 96_000, 176_400, 192_000)) {
            for (hz in listOf(100.0, 1_000.0, 10_000.0, 19_000.0)) {
                val r = PolyphaseResampler(input, out, 1)
                val y = channel(resampleAll(r, sines(input, input / 5, 0.5, hz), 4_096), 1, 0)
                val from = edge(r)
                val to = y.size - edge(r)

                val level = db(amplitudeAt(y, from, to, hz, out) / 0.5)
                val measuredHz = zeroCrossingFrequency(y, from, to, out)
                println("passband $input Hz -> 44100 Hz, $hz Hz: level ${"%.4f".format(level)} dB, frequency ${"%.3f".format(measuredHz)} Hz")
                assertThat(abs(level)).isLessThan(0.1)
                assertThat(abs(measuredHz - hz) / hz).isLessThan(0.0005)
            }
        }
    }

    @Test
    fun `tones above the output Nyquist frequency are suppressed by more than 90 dB`() {
        val cases = mapOf(
            96_000 to listOf(22_500.0, 23_000.0, 30_000.0, 40_000.0, 47_000.0),
            192_000 to listOf(23_000.0, 30_000.0, 60_000.0, 95_000.0),
            88_200 to listOf(22_500.0, 30_000.0, 44_000.0),
        )
        var worst = -1_000.0
        for ((input, tones) in cases) {
            for (hz in tones) {
                val r = PolyphaseResampler(input, out, 1)
                val y = channel(resampleAll(r, sines(input, input / 5, 0.5, hz), 4_096), 1, 0)
                val level = db(rms(y, edge(r), y.size - edge(r)) * Math.sqrt(2.0) / 0.5)
                println("stopband $input Hz -> 44100 Hz, $hz Hz: ${"%.1f".format(level)} dB")
                worst = maxOf(worst, level)
                assertThat(level).isLessThan(-90.0)
            }
        }
        println("stopband worst case: ${"%.1f".format(worst)} dB")
    }

    @Test
    fun `output length is the input duration at the new rate`() {
        for (input in listOf(48_000, 88_200, 96_000, 176_400, 192_000)) {
            for (frames in listOf(0, 1, 2, 3, 999, 4_800, 48_007)) {
                val r = PolyphaseResampler(input, out, 2)
                val y = resampleAll(r, FloatArray(frames * 2), 1_000)
                val produced = y.size / 2L
                val exact = frames.toDouble() * out / input

                assertThat(produced).isEqualTo(ceil(exact - 1e-9).toLong())
                assertThat(abs(produced - exact.roundToLong())).isAtMost(1L)
                assertThat(r.outputFrameCount(frames.toLong())).isEqualTo(produced)
            }
        }
    }

    @Test
    fun `output does not depend on the block size`() {
        val input = sines(96_000, 4_800, 0.7, 1_234.0, 17_000.0)
        val reference = resampleAll(PolyphaseResampler(96_000, out, 2), input, 4_096)

        for (chunk in listOf(1, 37, 320, 1_000)) {
            assertThat(resampleAll(PolyphaseResampler(96_000, out, 2), input, chunk)).isEqualTo(reference)
        }
    }

    @Test
    fun `an impulse comes out at the same time position`() {
        val input = FloatArray(19_200).also { it[9_600] = 1f }
        val y = channel(resampleAll(PolyphaseResampler(96_000, out, 1), input, 4_096), 1, 0)

        val peak = y.indices.maxBy { y[it] }
        assertThat(peak).isEqualTo(4_410)
    }

    @Test
    fun `DC passes at unity gain and silence stays exactly silent`() {
        val r = PolyphaseResampler(96_000, out, 2)
        val dc = FloatArray(9_600 * 2) { if (it % 2 == 0) 0.5f else 0f }
        val y = resampleAll(r, dc, 4_096)

        val left = channel(y, 2, 0)
        for (n in edge(r) until left.size - edge(r)) assertThat(abs(left[n] - 0.5)).isLessThan(1e-5)
        assertThat(channel(y, 2, 1).all { it == 0.0 }).isTrue()
    }

    @Test
    fun `odd rates are approximated with a bounded number of phases`() {
        val r = PolyphaseResampler(96_001, out, 1)

        assertThat(r.upFactor).isAtMost(PolyphaseResampler.MAX_PHASES)
        val y = resampleAll(r, FloatArray(9_600), 4_096)
        assertThat(abs(y.size - 9_600.0 * out / 96_001)).isAtMost(1.0)
    }

    @Test
    fun `filter length follows the transition band`() {
        val r96 = PolyphaseResampler(96_000, out, 1)
        val r192 = PolyphaseResampler(192_000, out, 1)
        println("taps per phase: 96 kHz ${r96.tapsPerPhase}, 192 kHz ${r192.tapsPerPhase}, 88.2 kHz ${PolyphaseResampler(88_200, out, 1).tapsPerPhase}")

        assertThat(r96.tapsPerPhase % 2).isEqualTo(0)
        assertThat(r192.tapsPerPhase).isGreaterThan(r96.tapsPerPhase)
    }

    @Test
    fun `flush may be called only once and process not after it`() {
        val r = PolyphaseResampler(96_000, out, 1)
        val buffer = FloatArray(r.maxOutputFrames(16))
        r.flush(buffer)

        assertThrows(IllegalStateException::class.java) { r.flush(buffer) }
        assertThrows(IllegalStateException::class.java) { r.process(FloatArray(16), 16, buffer) }
    }

    @Test
    fun `invalid arguments are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PolyphaseResampler(0, out, 1) }
        assertThrows(IllegalArgumentException::class.java) { PolyphaseResampler(96_000, out, 0) }
        val r = PolyphaseResampler(96_000, out, 2)
        assertThrows(IllegalArgumentException::class.java) { r.process(FloatArray(10), 6, FloatArray(r.maxOutputFrames(6) * 2)) }
        assertThrows(IllegalArgumentException::class.java) { r.process(FloatArray(4_096), 2_048, FloatArray(4)) }
    }

    @Test
    fun `throughput at 96 kHz stereo is reported`() {
        val input = sines(96_000, 96_000, 0.5, 1_000.0, 2_000.0)
        val r = PolyphaseResampler(96_000, out, 2)
        resampleAll(r, input, 4_096) // warm-up
        val start = System.nanoTime()
        resampleAll(PolyphaseResampler(96_000, out, 2), input, 4_096)
        val seconds = (System.nanoTime() - start) / 1e9
        println("throughput 96 kHz stereo: ${"%.1f".format(1.0 / seconds)}x real time on the JVM")
    }
}
