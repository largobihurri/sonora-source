package io.sonora.core.djengine.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class BesselFiltersTest {

    /** The reverse Bessel polynomial of order 4, written out (Thomson 1949): s^4 + 10 s^3 + 45 s^2 + 105 s + 105. */
    private val theta4 = doubleArrayOf(105.0, 105.0, 45.0, 10.0, 1.0)

    private fun thetaAt(re: Double, im: Double): Pair<Double, Double> {
        var pr = 0.0
        var pi = 0.0
        for (k in theta4.indices.reversed()) { // Horner
            val nr = pr * re - pi * im + theta4[k]
            val ni = pr * im + pi * re
            pr = nr
            pi = ni
        }
        return pr to pi
    }

    /** Magnitude in dB of the analogue Bessel low-pass with the -3 dB point at [cutoffRatio] times the cutoff. */
    private fun analogueLowPassDb(ratio: Double, omega3: Double): Double {
        val (re, im) = thetaAt(0.0, omega3 * ratio)
        return 10 * log10(theta4[0] * theta4[0] / (re * re + im * im))
    }

    @Test
    fun `the Bessel polynomial follows the recurrence`() {
        assertThat(BesselFilters.besselPolynomial(1)).usingTolerance(0.0).containsExactly(1.0, 1.0).inOrder()
        assertThat(BesselFilters.besselPolynomial(2)).usingTolerance(0.0).containsExactly(3.0, 3.0, 1.0).inOrder()
        assertThat(BesselFilters.besselPolynomial(3)).usingTolerance(0.0).containsExactly(15.0, 15.0, 6.0, 1.0).inOrder()
        assertThat(BesselFilters.besselPolynomial(4)).usingTolerance(0.0).containsExactly(105.0, 105.0, 45.0, 10.0, 1.0).inOrder()
    }

    @Test
    fun `the delay-normalised poles are the roots of the order 4 polynomial`() {
        val poles = BesselFilters.delayNormalisedPoles(4)

        assertThat(poles).hasSize(2) // upper half plane; the conjugates are implied
        for (pole in poles) {
            val (re, im) = thetaAt(pole.re, pole.im)
            assertThat(sqrt(re * re + im * im)).isLessThan(1e-9)
            assertThat(pole.im).isGreaterThan(0.0)
            assertThat(pole.re).isLessThan(0.0)
        }
        // Published values of the order 4 roots (Bessel filter tables).
        val sorted = poles.sortedBy { it.im }
        assertThat(sorted[0].re).isWithin(1e-6).of(-2.8962106028)
        assertThat(sorted[0].im).isWithin(1e-6).of(0.8672341289)
        assertThat(sorted[1].re).isWithin(1e-6).of(-2.1037893972)
        assertThat(sorted[1].im).isWithin(1e-6).of(2.6574180419)
    }

    @Test
    fun `the minus 3 dB frequency of the order 4 prototype is 2_1139 rad per second`() {
        val omega3 = BesselFilters.threeDbFrequency(4)

        assertThat(omega3).isWithin(1e-4).of(2.1139)
        assertThat(analogueLowPassDb(1.0, omega3)).isWithin(1e-9).of(-10 * log10(2.0))
    }

    @Test
    fun `the prototype poles have the minus 3 dB point at 1 rad per second`() {
        val poles = BesselFilters.prototypePoles(4)
        val omega3 = BesselFilters.threeDbFrequency(4)

        // |H(j)|^2 = prod |p|^2 / |j - p|^2 over the poles and their conjugates.
        var gainSquared = 1.0
        for (p in poles) {
            val pSquared = p.re * p.re + p.im * p.im
            val plus = p.re * p.re + (1 - p.im) * (1 - p.im)
            val minus = p.re * p.re + (1 + p.im) * (1 + p.im)
            gainSquared *= pSquared * pSquared / (plus * minus)
        }
        assertThat(gainSquared).isWithin(1e-9).of(0.5)
        val delayPoles = BesselFilters.delayNormalisedPoles(4)
        assertThat(poles.sortedBy { it.im }[0].re).isWithin(1e-9).of(delayPoles.sortedBy { it.im }[0].re / omega3)
    }

    @Test
    fun `low-pass and high-pass are minus 3 dB at their cutoff`() {
        for (rate in intArrayOf(44_100, 48_000, 22_050)) {
            for (cutoff in doubleArrayOf(600.0, 4_000.0)) {
                val low = BesselFilters.lowPass(cutoff, rate).magnitudeDbAt(cutoff, rate)
                val high = BesselFilters.highPass(cutoff, rate).magnitudeDbAt(cutoff, rate)

                assertThat(low).isWithin(0.01).of(-3.0103)
                assertThat(high).isWithin(0.01).of(-3.0103)
            }
        }
    }

    @Test
    fun `the digital low-pass follows the analogue Bessel response`() {
        val omega3 = BesselFilters.threeDbFrequency(4)
        val filter = BesselFilters.lowPass(600.0, 44_100)

        for (ratio in doubleArrayOf(0.1, 0.25, 0.5, 0.75, 1.5, 2.0)) {
            val digital = filter.magnitudeDbAt(600.0 * ratio, 44_100)
            assertThat(digital).isWithin(0.35).of(analogueLowPassDb(ratio, omega3))
        }
    }

    @Test
    fun `the low-pass passes DC and the high-pass passes Nyquist at unit gain and blocks the opposite end`() {
        val rate = 44_100
        val low = BesselFilters.lowPass(600.0, rate)
        val high = BesselFilters.highPass(4_000.0, rate)

        assertThat(low.magnitudeAt(0.0, rate)).isWithin(1e-9).of(1.0)
        assertThat(low.magnitudeAt(rate / 2.0, rate)).isLessThan(1e-9)
        assertThat(high.magnitudeAt(rate / 2.0, rate)).isWithin(1e-9).of(1.0)
        assertThat(high.magnitudeAt(0.0, rate)).isLessThan(1e-9)
    }

    @Test
    fun `the Bessel response falls monotonically past the cutoff`() {
        val filter = BesselFilters.lowPass(600.0, 44_100)
        var previous = Double.MAX_VALUE
        var frequency = 0.0
        while (frequency < 20_000.0) {
            val gain = filter.magnitudeAt(frequency, 44_100)
            assertThat(gain).isAtMost(previous + 1e-12)
            previous = gain
            frequency += 25.0
        }
    }

    @Test
    fun `the band-pass is minus 3 dB at both edges and flat between them`() {
        for (rate in intArrayOf(44_100, 48_000, 22_050)) {
            val band = BesselFilters.bandPass(600.0, 4_000.0, rate)

            assertThat(band.magnitudeDbAt(600.0, rate)).isWithin(0.25).of(-3.0103)
            assertThat(band.magnitudeDbAt(4_000.0, rate)).isWithin(0.25).of(-3.0103)
            // Geometric centre of the band: the Bessel skirts are gentle, so the plateau is within a dB of unity.
            assertThat(band.magnitudeDbAt(sqrt(600.0 * 4_000.0), rate)).isGreaterThan(-1.0)
            assertThat(band.magnitudeDbAt(100.0, rate)).isLessThan(-30.0)
            assertThat(band.magnitudeDbAt(10_000.0, rate)).isLessThan(-15.0)
        }
    }

    @Test
    fun `a cascade has two biquads per pole pair and filters like its response says`() {
        val rate = 44_100
        val filter = BesselFilters.lowPass(600.0, rate)
        assertThat(filter.sectionCount).isEqualTo(2)
        assertThat(BesselFilters.bandPass(600.0, 4_000.0, rate).sectionCount).isEqualTo(4)

        // Steady-state amplitude of a sine through the filter matches magnitudeAt.
        val frequency = 900.0
        var peak = 0.0
        for (n in 0 until rate) {
            val y = filter.process(kotlin.math.sin(2 * PI * frequency * n / rate))
            if (n > rate / 2) peak = maxOf(peak, abs(y))
        }
        assertThat(peak).isWithin(1e-3).of(filter.magnitudeAt(frequency, rate))
    }

    @Test
    fun `reset clears the state and flushDenormals only removes a decayed one`() {
        val filter = BesselFilters.lowPass(600.0, 44_100)
        repeat(100) { filter.process(1.0) }
        val live = filter.process(0.0)
        filter.reset()
        assertThat(filter.process(0.0)).isEqualTo(0.0)
        assertThat(live).isNotEqualTo(0.0)

        val other = BesselFilters.lowPass(600.0, 44_100)
        repeat(100) { other.process(1.0) }
        val reference = BesselFilters.lowPass(600.0, 44_100)
        repeat(100) { reference.process(1.0) }
        other.flushDenormals() // a live state is untouched
        assertThat(other.process(0.0)).isEqualTo(reference.process(0.0))
    }

    @Test
    fun `invalid designs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BesselFilters.lowPass(0.0, 44_100) }
        assertThrows(IllegalArgumentException::class.java) { BesselFilters.lowPass(22_050.0, 44_100) }
        assertThrows(IllegalArgumentException::class.java) { BesselFilters.highPass(600.0, 0) }
        assertThrows(IllegalArgumentException::class.java) { BesselFilters.bandPass(4_000.0, 600.0, 44_100) }
        assertThrows(IllegalArgumentException::class.java) { BesselFilters.besselPolynomial(0) }
        assertThrows(IllegalArgumentException::class.java) { BesselFilters.delayNormalisedPoles(3) }
    }
}
