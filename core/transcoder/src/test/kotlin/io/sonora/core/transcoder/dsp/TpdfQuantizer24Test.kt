package io.sonora.core.transcoder.dsp

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Assert.assertThrows
import org.junit.Test

class TpdfQuantizer24Test {

    private val lsb = 1.0 / 8_388_608

    @Test
    fun `without dither samples round to the nearest 24-bit step`() {
        val q = TpdfQuantizer(seed = 1, dither = false, bits = 24)

        assertThat(q.quantize(0f)).isEqualTo(0)
        assertThat(q.quantize((0.49 * lsb).toFloat())).isEqualTo(0)
        assertThat(q.quantize((0.51 * lsb).toFloat())).isEqualTo(1)
        assertThat(q.quantize((-0.51 * lsb).toFloat())).isEqualTo(-1)
        assertThat(q.quantize(0.5f)).isEqualTo(4_194_304)
        for (value in listOf(-8_388_608, -1_234_567, -1, 1, 7, 8_388_607)) {
            assertThat(q.quantize((value * lsb).toFloat())).isEqualTo(value)
        }
    }

    @Test
    fun `16-bit samples land on the 24-bit grid unchanged`() {
        val q = TpdfQuantizer(seed = 1, dither = false, bits = 24)
        for (value in listOf(-32_768, -12_345, -1, 1, 32_767)) {
            assertThat(q.quantize(value / 32_768f)).isEqualTo(value * 256)
        }
    }

    @Test
    fun `full scale, overs, NaN and infinities stay inside 24 bits`() {
        for (dither in listOf(false, true)) {
            val q = TpdfQuantizer(seed = 7, dither = dither, bits = 24)
            repeat(1_000) {
                for (x in listOf(1f, 0.99999994f, -1f, 2f, -2f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                    assertThat(q.quantize(x)).isIn(-8_388_608..8_388_607)
                }
            }
            assertThat(q.quantize(-2f)).isEqualTo(-8_388_608)
            assertThat(q.quantize(2f)).isEqualTo(8_388_607)
        }
        assertThat(TpdfQuantizer(seed = 1, dither = false, bits = 24).quantize(Float.NaN)).isEqualTo(0)
    }

    @Test
    fun `dithered silence is noise of at most 1 LSB with zero mean`() {
        val q = TpdfQuantizer(seed = 42, dither = true, bits = 24)
        val values = IntArray(200_000) { q.quantize(0f) }

        assertThat(values.all { it in -1..1 }).isTrue()
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        assertThat(abs(mean)).isLessThan(0.01)
        assertThat(variance).isWithin(0.02).of(0.25)
    }

    @Test
    fun `24-bit samples are packed in three bytes in either byte order`() {
        val q = TpdfQuantizer(seed = 1, dither = false, bits = 24)
        val samples = floatArrayOf((0x123456 * lsb).toFloat(), (-2 * lsb).toFloat())
        val big = ByteArray(6)
        val little = ByteArray(6)

        q.quantizeToBigEndian(samples, 2, big)
        q.quantizeToLittleEndian(samples, 2, little)

        assertThat(big).isEqualTo(byteArrayOf(0x12, 0x34, 0x56, -1, -1, -2))
        assertThat(little).isEqualTo(byteArrayOf(0x56, 0x34, 0x12, -2, -1, -1))
    }

    @Test
    fun `quantised floats sit exactly on the output grid`() {
        val q = TpdfQuantizer(seed = 3, dither = true, bits = 24)
        val samples = FloatArray(1_000) { (it - 500) / 1_000f }
        val grid = FloatArray(samples.size)

        q.quantizeToFloat(samples, samples.size, grid)

        for (value in grid) {
            val steps = value * 8_388_608.0
            assertThat(steps).isEqualTo(Math.rint(steps))
            assertThat(steps).isIn(com.google.common.collect.Range.closed(-8_388_608.0, 8_388_607.0))
        }
    }

    @Test
    fun `only 16 and 24 bits are offered`() {
        assertThrows(IllegalArgumentException::class.java) { TpdfQuantizer(seed = 1, dither = false, bits = 20) }
    }

    @Test
    fun `a target too small for three bytes per sample is rejected`() {
        val q = TpdfQuantizer(seed = 1, dither = false, bits = 24)
        assertThrows(IllegalArgumentException::class.java) { q.quantizeToBigEndian(FloatArray(2), 2, ByteArray(5)) }
    }
}
