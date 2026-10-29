package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CueMarkerGeometryTest {

    @Test
    fun `an overview marker sits at its share of the track`() {
        assertThat(CueMarkerGeometry.overviewX(50_000.0, 200_000.0, 400f)).isWithin(1e-3f).of(100f)
        assertThat(CueMarkerGeometry.overviewX(0.0, 200_000.0, 400f)).isEqualTo(0f)
        assertThat(CueMarkerGeometry.overviewX(200_000.0, 200_000.0, 400f)).isEqualTo(400f)
    }

    @Test
    fun `an overview marker never leaves the bar`() {
        assertThat(CueMarkerGeometry.overviewX(-5.0, 200_000.0, 400f)).isEqualTo(0f)
        assertThat(CueMarkerGeometry.overviewX(300_000.0, 200_000.0, 400f)).isEqualTo(400f)
    }

    @Test
    fun `an unknown duration puts the marker at the start`() {
        assertThat(CueMarkerGeometry.overviewX(5_000.0, 0.0, 400f)).isEqualTo(0f)
        assertThat(CueMarkerGeometry.overviewX(Double.NaN, 100.0, 400f)).isEqualTo(0f)
    }

    @Test
    fun `a flag shows while any of it is inside the view`() {
        assertThat(CueMarkerGeometry.flagVisible(100f, 20f, 400f)).isTrue()
        assertThat(CueMarkerGeometry.flagVisible(-10f, 20f, 400f)).isTrue() // its label still reaches in
        assertThat(CueMarkerGeometry.flagVisible(-20f, 20f, 400f)).isFalse()
        assertThat(CueMarkerGeometry.flagVisible(-30f, 20f, 400f)).isFalse()
        assertThat(CueMarkerGeometry.flagVisible(399f, 20f, 400f)).isTrue()
        assertThat(CueMarkerGeometry.flagVisible(400f, 20f, 400f)).isFalse()
    }

    @Test
    fun `a label needs a gap after the previous one`() {
        assertThat(CueMarkerGeometry.labelFits(Float.NEGATIVE_INFINITY, 0f, 2f)).isTrue()
        assertThat(CueMarkerGeometry.labelFits(50f, 52f, 2f)).isTrue()
        assertThat(CueMarkerGeometry.labelFits(50f, 51f, 2f)).isFalse()
        assertThat(CueMarkerGeometry.labelFits(50f, 30f, 2f)).isFalse()
    }

    @Test
    fun `light colours take dark lettering and dark colours take white`() {
        assertThat(CueMarkerGeometry.inkFor(0xFFFFD60A.toInt())).isEqualTo(CueMarkerGeometry.DARK_INK) // yellow
        assertThat(CueMarkerGeometry.inkFor(0xFFE6E6E6.toInt())).isEqualTo(CueMarkerGeometry.DARK_INK) // white
        assertThat(CueMarkerGeometry.inkFor(0xFF305AFF.toInt())).isEqualTo(CueMarkerGeometry.LIGHT_INK) // blue
        assertThat(CueMarkerGeometry.inkFor(0xFFB04CFF.toInt())).isEqualTo(CueMarkerGeometry.LIGHT_INK) // purple
        assertThat(CueMarkerGeometry.inkFor(0xFF000000.toInt())).isEqualTo(CueMarkerGeometry.LIGHT_INK)
    }
}
