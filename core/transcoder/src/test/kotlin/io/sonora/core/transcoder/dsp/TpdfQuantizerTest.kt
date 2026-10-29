package io.sonora.core.transcoder.dsp

import com.google.common.truth.Truth.assertThat
import io.sonora.core.transcoder.dsp.Signals.amplitudeAt
import io.sonora.core.transcoder.dsp.Signals.db
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test

class TpdfQuantizerTest {

    private val lsb = 1.0 / 32_768

    @Test
    fun `without dither samples round to the nearest 16-bit step`() {
        val q = TpdfQuantizer(seed = 1, dither = false)

        assertThat(q.quantize(0f)).isEqualTo(0)
        assertThat(q.quantize((0.49 * lsb).toFloat())).isEqualTo(0)
        assertThat(q.quantize((0.51 * lsb).toFloat())).isEqualTo(1)
        assertThat(q.quantize((-0.51 * lsb).toFloat())).isEqualTo(-1)
        assertThat(q.quantize(0.5f)).isEqualTo(16_384)
        for (value in listOf(-32_768, -12_345, -1, 1, 7, 32_767)) {
            assertThat(q.quantize(value / 32_768f)).isEqualTo(value)
        }
    }

    @Test
    fun `full scale and overs stay inside 16 bits, with or without dither`() {
        for (dither in listOf(false, true)) {
            val q = TpdfQuantizer(seed = 7, dither = dither)
            repeat(1_000) {
                for (x in listOf(1f, 0.99999994f, -1f, 2f, -2f, 0.99998f, -0.99998f)) {
                    assertThat(q.quantize(x)).isIn(-32_768..32_767)
                }
            }
            assertThat(q.quantize(-2f)).isEqualTo(-32_768)
            assertThat(q.quantize(2f)).isEqualTo(32_767)
        }
    }

    @Test
    fun `dithered silence is noise of at most 1 LSB with zero mean`() {
        val q = TpdfQuantizer(seed = 42, dither = true)
        val values = IntArray(200_000) { q.quantize(0f) }

        assertThat(values.all { it in -1..1 }).isTrue()
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        println("dithered silence: mean ${"%.5f".format(mean)} LSB, variance ${"%.4f".format(variance)} LSB^2")
        assertThat(abs(mean)).isLessThan(0.01)
        // round(TPDF on (-1, 1)) is +-1 with probability 1/4: variance 0.25 LSB^2.
        assertThat(variance).isWithin(0.02).of(0.25)
    }

    @Test
    fun `the same seed gives the same noise, another seed different noise`() {
        fun run(seed: Long) = TpdfQuantizer(seed, dither = true).let { q -> IntArray(1_000) { q.quantize(0.1234f) } }

        assertThat(run(5)).isEqualTo(run(5))
        assertThat(run(5)).isNotEqualTo(run(6))
    }

    @Test
    fun `a low-level sine keeps its spectrum when dithered, unlike plain rounding`() {
        val n = 65_536
        val bin = 1_486 // ~1 kHz at 44.1 kHz, an exact number of cycles
        val hz = bin * 44_100.0 / n
        val amplitude = 1.5 * lsb
        val signal = FloatArray(n) { (amplitude * sin(2 * PI * bin * it / n)).toFloat() }

        fun spectrum(dither: Boolean): Triple<Double, Double, Double> {
            val q = TpdfQuantizer(seed = 3, dither = dither)
            val y = DoubleArray(n) { q.quantize(signal[it]) * lsb }
            val fundamental = amplitudeAt(y, 0, n, hz, 44_100)
            val third = amplitudeAt(y, 0, n, 3 * hz, 44_100)
            val fifth = amplitudeAt(y, 0, n, 5 * hz, 44_100)
            return Triple(fundamental, third, fifth)
        }

        val (roundedFundamental, roundedThird, roundedFifth) = spectrum(dither = false)
        val (ditheredFundamental, ditheredThird, ditheredFifth) = spectrum(dither = true)
        println(
            "1.5 LSB sine: rounding H3 ${"%.1f".format(db(roundedThird / roundedFundamental))} dB, " +
                "H5 ${"%.1f".format(db(roundedFifth / roundedFundamental))} dB; " +
                "TPDF H3 ${"%.1f".format(db(ditheredThird / ditheredFundamental))} dB, " +
                "H5 ${"%.1f".format(db(ditheredFifth / ditheredFundamental))} dB, " +
                "fundamental error ${"%.3f".format(db(ditheredFundamental / amplitude))} dB",
        )

        assertThat(db(roundedThird / roundedFundamental)).isGreaterThan(-30.0)
        assertThat(db(ditheredThird / ditheredFundamental)).isLessThan(-40.0)
        assertThat(db(ditheredFifth / ditheredFundamental)).isLessThan(-40.0)
        assertThat(abs(db(ditheredFundamental / amplitude))).isLessThan(0.5)
    }

    @Test
    fun `blocks are written as big-endian 16-bit samples`() {
        val q = TpdfQuantizer(seed = 1, dither = false)
        val target = ByteArray(8)

        q.quantizeToBigEndian(floatArrayOf(-1f, 0.5f, 1f / 32_768, 2f), 4, target)

        assertThat(target).isEqualTo(byteArrayOf(0x80.toByte(), 0x00, 0x40, 0x00, 0x00, 0x01, 0x7F, 0xFF.toByte()))
    }

    @Test
    fun `non-finite samples never click to full scale`() {
        for (dither in listOf(false, true)) {
            val q = TpdfQuantizer(seed = 3, dither = dither)
            repeat(100) { assertThat(abs(q.quantize(Float.NaN))).isAtMost(1) }
            assertThat(q.quantize(Float.POSITIVE_INFINITY)).isEqualTo(32_767)
            assertThat(q.quantize(Float.NEGATIVE_INFINITY)).isEqualTo(-32_768)
        }
    }
}
