package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DjWaveformPaletteTest {

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF
    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF

    @Test
    fun `the bands are drawn low then mid then high`() {
        assertThat(DjWaveformPalette.DRAW_ORDER).containsExactly(WaveformBand.LOW, WaveformBand.MID, WaveformBand.HIGH).inOrder()
    }

    @Test
    fun `low is blue, mid is amber and high is white`() {
        val low = DjWaveformPalette.colorOf(WaveformBand.LOW)
        val mid = DjWaveformPalette.colorOf(WaveformBand.MID)
        val high = DjWaveformPalette.colorOf(WaveformBand.HIGH)
        assertThat(blue(low)).isGreaterThan(red(low))
        assertThat(blue(low)).isGreaterThan(green(low))
        assertThat(red(mid)).isGreaterThan(blue(mid))
        assertThat(green(mid)).isGreaterThan(blue(mid))
        assertThat(red(mid)).isAtLeast(green(mid))
        assertThat(red(high)).isAtLeast(0xF0)
        assertThat(green(high)).isAtLeast(0xF0)
        assertThat(blue(high)).isAtLeast(0xF0)
        listOf(low, mid, high).forEach { assertThat(alpha(it)).isEqualTo(0xFF) }
    }

    @Test
    fun `the phrase line is a vivid green, apart from the red bars and the band colours`() {
        val phrase = DjWaveformPalette.PHRASE_COLOR
        assertThat(phrase).isEqualTo(0xFF30D158.toInt())
        assertThat(green(phrase)).isGreaterThan(red(phrase) + 0x50)
        assertThat(green(phrase)).isGreaterThan(blue(phrase) + 0x50)
        assertThat(alpha(phrase)).isEqualTo(0xFF)
        assertThat(phrase).isNotEqualTo(DjWaveformPalette.DOWNBEAT_TICK_COLOR)
    }

    @Test
    fun `the level curve maps 0 to 0 and 255 to 1 and never decreases`() {
        assertThat(DjWaveformPalette.levelFraction(0)).isEqualTo(0f)
        assertThat(DjWaveformPalette.levelFraction(255)).isEqualTo(1f)
        var previous = -1f
        for (level in 0..255) {
            val fraction = DjWaveformPalette.levelFraction(level)
            assertThat(fraction).isAtLeast(previous)
            assertThat(fraction).isAtLeast(0f)
            assertThat(fraction).isAtMost(1f)
            previous = fraction
        }
    }

    @Test
    fun `the level curve lifts quiet levels above the straight line`() {
        assertThat(DjWaveformPalette.levelFraction(64)).isGreaterThan(64f / 255f)
    }

    @Test
    fun `the level curve clamps levels outside a byte`() {
        assertThat(DjWaveformPalette.levelFraction(-5)).isEqualTo(0f)
        assertThat(DjWaveformPalette.levelFraction(999)).isEqualTo(1f)
    }

    @Test
    fun `a bar is mirrored around the centre line`() {
        val half = DjWaveformPalette.halfHeightPx(level = 255, maxHalfHeightPx = 100f)
        assertThat(half).isEqualTo(100f)
        assertThat(DjWaveformPalette.halfHeightPx(level = 0, maxHalfHeightPx = 100f)).isEqualTo(0f)
        // Top and bottom edges sit symmetrically either side of the centre.
        val centre = 150f
        assertThat(DjWaveformPalette.topPx(centre, half)).isEqualTo(50f)
        assertThat(DjWaveformPalette.bottomPx(centre, half)).isEqualTo(250f)
    }
}
