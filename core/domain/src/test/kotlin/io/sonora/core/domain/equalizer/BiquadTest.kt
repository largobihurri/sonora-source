package io.sonora.core.domain.equalizer

import com.google.common.truth.Truth.assertThat
import kotlin.math.sqrt
import org.junit.Test

/**
 * RBJ Audio EQ Cookbook filters. Reference coefficients were computed separately from the cookbook formulas
 * (Python, double precision), normalised so a0 = 1.
 */
class BiquadTest {

    private fun assertCoefficients(actual: BiquadCoefficients, vararg expected: Double) {
        val values = doubleArrayOf(actual.b0, actual.b1, actual.b2, actual.a1, actual.a2)
        expected.forEachIndexed { i, value -> assertThat(values[i]).isWithin(1e-9).of(value) }
    }

    @Test
    fun `peaking filter matches the cookbook`() {
        val c = Biquad.design(BandShape.PEAKING, 1_000.0, 1.0, 6.0, 48_000)

        assertCoefficients(c, 1.043953086990, -1.895320723937, 0.867722284760, -1.895320723937, 0.911675371750)
    }

    @Test
    fun `low shelf matches the cookbook`() {
        val c = Biquad.design(BandShape.LOW_SHELF, 60.0, 1 / sqrt(2.0), -9.0, 44_100)

        assertCoefficients(c, 0.996838242893, -1.984375474773, 0.987580422449, -1.984336206276, 0.984457933840)
    }

    @Test
    fun `high shelf matches the cookbook`() {
        val c = Biquad.design(BandShape.HIGH_SHELF, 8_000.0, 1 / sqrt(2.0), 12.0, 48_000)

        assertCoefficients(c, 2.464984058129, -2.353080689948, 0.832012092287, -0.237659837381, 0.181575297849)
    }

    @Test
    fun `a peaking band reaches its gain at its centre`() {
        for (gain in listOf(-12.0, -3.5, 0.0, 4.2, 12.0)) {
            val c = Biquad.design(BandShape.PEAKING, 2_000.0, 1.41, gain, 44_100)

            assertThat(c.magnitudeDb(2_000.0, 44_100)).isWithin(0.1).of(gain)
        }
    }

    @Test
    fun `shelves are at half gain at their corner and at full gain on their plateau`() {
        val low = Biquad.design(BandShape.LOW_SHELF, 60.0, 1 / sqrt(2.0), 12.0, 48_000)
        val high = Biquad.design(BandShape.HIGH_SHELF, 8_000.0, 1 / sqrt(2.0), -12.0, 48_000)

        assertThat(low.magnitudeDb(60.0, 48_000)).isWithin(0.1).of(6.0)
        assertThat(low.magnitudeDb(7.5, 48_000)).isWithin(0.1).of(12.0)
        assertThat(low.magnitudeDb(1_000.0, 48_000)).isWithin(0.1).of(0.0)
        assertThat(high.magnitudeDb(8_000.0, 48_000)).isWithin(0.1).of(-6.0)
        assertThat(high.magnitudeDb(23_000.0, 48_000)).isWithin(0.1).of(-12.0)
        assertThat(high.magnitudeDb(1_000.0, 48_000)).isWithin(0.1).of(0.0)
    }

    @Test
    fun `a flat band is the identity`() {
        for (shape in BandShape.entries) {
            val c = Biquad.design(shape, 1_000.0, 0.7, 0.0, 48_000)

            assertThat(c.b0).isWithin(1e-12).of(1.0)
            assertThat(c.b1).isWithin(1e-12).of(c.a1)
            assertThat(c.b2).isWithin(1e-12).of(c.a2)
        }
    }

    @Test
    fun `a peaking band at or above Nyquist is flat and a shelf there is pulled below it`() {
        val peaking = Biquad.design(BandShape.PEAKING, 16_000.0, 2.87, 12.0, 22_050)
        val shelf = Biquad.design(BandShape.HIGH_SHELF, 16_000.0, 1 / sqrt(2.0), 12.0, 32_000)

        assertThat(peaking).isEqualTo(BiquadCoefficients.IDENTITY)
        assertThat(shelf.magnitudeDb(15_900.0, 32_000)).isWithin(0.5).of(12.0)
    }

    @Test
    fun `designing into an array gives the same coefficients`() {
        val out = DoubleArray(7)
        Biquad.design(BandShape.HIGH_SHELF, 8_000.0, 0.7, 5.0, 96_000, out, offset = 2)

        val c = Biquad.design(BandShape.HIGH_SHELF, 8_000.0, 0.7, 5.0, 96_000)
        assertThat(out.drop(2)).containsExactly(c.b0, c.b1, c.b2, c.a1, c.a2).inOrder()
    }
}
