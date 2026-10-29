package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverviewGeometryTest {

    @Test
    fun `the played fraction is position over duration`() {
        assertThat(OverviewGeometry.playedFraction(30_000.0, 120_000.0)).isWithin(1e-12).of(0.25)
        assertThat(OverviewGeometry.playedFraction(0.0, 120_000.0)).isEqualTo(0.0)
        assertThat(OverviewGeometry.playedFraction(120_000.0, 120_000.0)).isEqualTo(1.0)
    }

    @Test
    fun `the played fraction clamps to 0 and 1`() {
        assertThat(OverviewGeometry.playedFraction(-50.0, 1_000.0)).isEqualTo(0.0)
        assertThat(OverviewGeometry.playedFraction(5_000.0, 1_000.0)).isEqualTo(1.0)
    }

    @Test
    fun `an unknown duration gives zero rather than a division by zero`() {
        assertThat(OverviewGeometry.playedFraction(500.0, 0.0)).isEqualTo(0.0)
        assertThat(OverviewGeometry.playedFraction(500.0, -1.0)).isEqualTo(0.0)
        assertThat(OverviewGeometry.playedFraction(Double.NaN, 1_000.0)).isEqualTo(0.0)
        assertThat(OverviewGeometry.playedFraction(500.0, Double.NaN)).isEqualTo(0.0)
    }

    @Test
    fun `ms at a fraction scales the duration and clamps`() {
        assertThat(OverviewGeometry.msAt(0.5, 200_000.0)).isWithin(1e-9).of(100_000.0)
        assertThat(OverviewGeometry.msAt(-0.2, 200_000.0)).isEqualTo(0.0)
        assertThat(OverviewGeometry.msAt(1.7, 200_000.0)).isEqualTo(200_000.0)
        assertThat(OverviewGeometry.msAt(Double.NaN, 200_000.0)).isEqualTo(0.0)
        assertThat(OverviewGeometry.msAt(0.5, 0.0)).isEqualTo(0.0)
    }

    @Test
    fun `a touch x converts to a fraction and then to ms inside the bar`() {
        assertThat(OverviewGeometry.fractionAt(xPx = 270.0, widthPx = 1_080.0)).isWithin(1e-12).of(0.25)
        assertThat(OverviewGeometry.fractionAt(xPx = -20.0, widthPx = 1_080.0)).isEqualTo(0.0)
        assertThat(OverviewGeometry.fractionAt(xPx = 2_000.0, widthPx = 1_080.0)).isEqualTo(1.0)
        assertThat(OverviewGeometry.fractionAt(xPx = 10.0, widthPx = 0.0)).isEqualTo(0.0)
    }

    @Test
    fun `fraction and ms round trip`() {
        for (duration in doubleArrayOf(1.0, 215_000.0, 3_600_000.0)) {
            for (fraction in doubleArrayOf(0.0, 0.001, 0.5, 0.999, 1.0)) {
                val ms = OverviewGeometry.msAt(fraction, duration)
                assertThat(OverviewGeometry.playedFraction(ms, duration)).isWithin(1e-12).of(fraction)
            }
        }
    }
}
