package io.sonora.core.designsystem.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Page colours derived from an artwork's dominant colour. */
class ArtworkPaletteTest {

    private val white = 0xFFFFFFFF.toInt()

    private val samples = listOf(
        0xFF8FD3C4, // pale teal
        0xFFFFF27A, // yellow
        0xFF8B5A3C, // warm brown photo
        0xFF050505, // black
        0xFF6E7C86, // cold slate photo
        0xFFFFFFFF, // white
        0xFFFF0000, // pure red
        0xFF0000FF, // pure blue
        0xFF3A1F5C, // dark purple
    ).map { it.toInt() }

    private fun lightness(argb: Int) = ColorMath.toOklch(argb).l

    private fun chroma(argb: Int) = ColorMath.toOklch(argb).c

    @Test
    fun `white titles stay readable on the page and on its lower band`() {
        for (dominant in samples + listOf(null)) {
            val palette = ArtworkPalette.from(dominant)
            assertThat(ColorMath.contrastRatio(white, palette.background)).isAtLeast(7.0)
            assertThat(ColorMath.contrastRatio(white, palette.lowerBand)).isAtLeast(7.0)
        }
    }

    @Test
    fun `secondary and muted text reach AA on both bands`() {
        for (dominant in samples + listOf(null)) {
            val palette = ArtworkPalette.from(dominant)
            for (text in listOf(palette.secondaryText, palette.mutedText)) {
                assertThat(ColorMath.contrastRatio(text, palette.background)).isAtLeast(ColorMath.AA_CONTRAST)
                assertThat(ColorMath.contrastRatio(text, palette.lowerBand)).isAtLeast(ColorMath.AA_CONTRAST)
            }
        }
    }

    @Test
    fun `the page keeps the artwork's hue, fairly dark and never garish`() {
        for (dominant in listOf(0xFF8FD3C4, 0xFFFFF27A, 0xFF8B5A3C, 0xFFFF0000, 0xFF0000FF).map { it.toInt() }) {
            val palette = ArtworkPalette.from(dominant)
            val hue = ColorMath.toOklch(dominant).h
            assertThat(ColorMath.toOklch(palette.background).h).isWithin(12.0).of(hue)
            assertThat(lightness(palette.background)).isAtMost(0.40)
            assertThat(chroma(palette.background)).isAtMost(ArtworkPalette.MAX_CHROMA + 0.005)
        }
    }

    @Test
    fun `a black cover gives a near-black page and a white one a dark grey page`() {
        val black = ArtworkPalette.from(0xFF050505.toInt())
        assertThat(lightness(black.background)).isLessThan(0.22)

        val whiteCover = ArtworkPalette.from(white)
        assertThat(chroma(whiteCover.background)).isLessThan(0.01)
        assertThat(lightness(whiteCover.background)).isAtLeast(0.25)
    }

    @Test
    fun `the lower band is a little darker than the page and buttons a little lighter`() {
        for (dominant in samples) {
            val palette = ArtworkPalette.from(dominant)
            assertThat(lightness(palette.lowerBand)).isLessThan(lightness(palette.background))
            assertThat(lightness(palette.buttonFill)).isGreaterThan(lightness(palette.background))
            assertThat(lightness(palette.secondaryText)).isGreaterThan(lightness(palette.mutedText))
        }
    }

    @Test
    fun `without artwork colour the page is a neutral dark grey`() {
        val palette = ArtworkPalette.from(null)
        assertThat(chroma(palette.background)).isLessThan(0.01)
        assertThat(lightness(palette.background)).isIn(com.google.common.collect.Range.closed(0.15, 0.35))
    }
}
