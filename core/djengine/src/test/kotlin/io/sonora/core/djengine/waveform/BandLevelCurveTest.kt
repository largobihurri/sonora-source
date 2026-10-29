package io.sonora.core.djengine.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BandLevelCurveTest {

    @Test
    fun `the default curve is linear with full scale at 255`() {
        val curve = BandLevelCurve.DEFAULT

        assertThat(curve.ceiling).isEqualTo(1.0)
        assertThat(curve.exponent).isEqualTo(1.0)
        assertThat(curve.level(0.0)).isEqualTo(0)
        assertThat(curve.level(1.0)).isEqualTo(255)
        assertThat(curve.level(0.5)).isEqualTo(128) // 127.5 rounds half up
        assertThat(curve.level(0.25)).isEqualTo(64) // 63.75
        assertThat(curve.level(1.0 / 255)).isEqualTo(1)
    }

    @Test
    fun `halving the amplitude halves the level`() {
        val curve = BandLevelCurve.DEFAULT

        for (amplitude in doubleArrayOf(0.8, 0.6, 0.4, 0.2)) {
            val full = curve.level(amplitude).toDouble()
            val half = curve.level(amplitude / 2).toDouble()
            assertThat(half).isWithin(0.5).of(full / 2)
        }
    }

    @Test
    fun `amplitudes above the ceiling saturate and the sign is ignored`() {
        val curve = BandLevelCurve(ceiling = 0.5)

        assertThat(curve.level(0.5)).isEqualTo(255)
        assertThat(curve.level(0.25)).isEqualTo(128)
        assertThat(curve.level(2.0)).isEqualTo(255)
        assertThat(curve.level(-0.25)).isEqualTo(128)
        assertThat(curve.level(Double.POSITIVE_INFINITY)).isEqualTo(255)
        assertThat(curve.level(Double.NaN)).isEqualTo(0)
    }

    @Test
    fun `the exponent bends the curve`() {
        val gamma = BandLevelCurve(exponent = 0.5)

        assertThat(gamma.level(0.25)).isEqualTo(128) // sqrt(0.25) = 0.5
        assertThat(gamma.level(1.0)).isEqualTo(255)
        assertThat(gamma.level(0.0)).isEqualTo(0)
    }

    @Test
    fun `the level never decreases when the amplitude grows`() {
        for (curve in listOf(BandLevelCurve.DEFAULT, BandLevelCurve(0.7, 0.6), BandLevelCurve(1.2, 1.5))) {
            var previous = 0
            for (i in 0..2000) {
                val level = curve.level(i / 1000.0)
                assertThat(level).isAtLeast(previous)
                assertThat(level).isIn(0..255)
                previous = level
            }
        }
    }

    @Test
    fun `invalid parameters are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BandLevelCurve(ceiling = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { BandLevelCurve(ceiling = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { BandLevelCurve(exponent = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { BandLevelCurve(exponent = -1.0) }
    }
}
