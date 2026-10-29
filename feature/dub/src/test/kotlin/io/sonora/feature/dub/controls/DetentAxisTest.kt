package io.sonora.feature.dub.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DetentAxisTest {

    private val axis = DetentAxis(centre = 0.75, zone = 0.05)

    @Test
    fun `the whole detent zone holds exactly at the centre`() {
        for (raw in listOf(0.70, 0.72, 0.75, 0.78, 0.80)) {
            assertThat(axis.effectiveOf(raw)).isEqualTo(0.75)
        }
    }

    @Test
    fun `both ends stay reachable`() {
        assertThat(axis.effectiveOf(0.0)).isEqualTo(0.0)
        assertThat(axis.effectiveOf(1.0)).isWithin(1e-12).of(1.0)
        assertThat(axis.effectiveOf(-3.0)).isEqualTo(0.0)
        assertThat(axis.effectiveOf(4.0)).isWithin(1e-12).of(1.0)
    }

    @Test
    fun `the value leaves the detent without a jump`() {
        assertThat(axis.effectiveOf(0.70 - 1e-9)).isWithin(1e-6).of(0.75)
        assertThat(axis.effectiveOf(0.80 + 1e-9)).isWithin(1e-6).of(0.75)
    }

    @Test
    fun `rawOf inverts effectiveOf outside the zone and gives the centre for the centre`() {
        assertThat(axis.rawOf(0.75)).isEqualTo(0.75)
        for (value in listOf(0.0, 0.1, 0.5, 0.74, 0.76, 0.9, 1.0)) {
            assertThat(axis.effectiveOf(axis.rawOf(value))).isWithin(1e-9).of(value)
        }
    }

    @Test
    fun `NaN reads as the centre`() {
        assertThat(axis.effectiveOf(Double.NaN)).isEqualTo(0.75)
        assertThat(axis.rawOf(Double.NaN)).isEqualTo(0.75)
    }

    @Test
    fun `entering the zone or jumping across it hits the detent, moving inside or leaving does not`() {
        assertThat(axis.hitsDetent(0.6, 0.72)).isTrue()
        assertThat(axis.hitsDetent(0.9, 0.79)).isTrue()
        assertThat(axis.hitsDetent(0.6, 0.9)).isTrue()
        assertThat(axis.hitsDetent(0.72, 0.78)).isFalse()
        assertThat(axis.hitsDetent(0.75, 0.9)).isFalse()
        assertThat(axis.hitsDetent(0.5, 0.6)).isFalse()
    }
}
