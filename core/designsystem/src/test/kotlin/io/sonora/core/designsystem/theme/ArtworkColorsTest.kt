package io.sonora.core.designsystem.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Accent seeds from synthetic artwork pixels. */
class ArtworkColorsTest {

    private fun pixels(vararg parts: Pair<Int, Long>): IntArray =
        parts.flatMap { (count, argb) -> List(count) { argb.toInt() } }.toIntArray()

    private fun hueOf(argb: Int) = ColorMath.toOklch(argb).h

    @Test
    fun `greyscale artwork gives no colour`() {
        val art = pixels(400 to 0xFF000000, 300 to 0xFF808080, 300 to 0xFFFFFFFF)

        assertThat(ArtworkColors.accentSeed(art)).isNull()
        assertThat(ArtworkColors.accentSeed(IntArray(0))).isNull()
    }

    @Test
    fun `the colour of a mostly grey cover with a red area is red`() {
        val art = pixels(700 to 0xFF9E9E9E, 300 to 0xFFE53935)

        val seed = ArtworkColors.accentSeed(art)

        assertThat(seed).isNotNull()
        assertThat(hueOf(seed!!)).isWithin(6.0).of(hueOf(0xFFE53935.toInt()))
    }

    @Test
    fun `a vivid colour wins over a larger muted one`() {
        val mutedBlue = 0xFF3A4250L // low saturation, dark
        val vividOrange = 0xFFFF8C00L
        val art = pixels(600 to mutedBlue, 400 to vividOrange)

        val seed = ArtworkColors.accentSeed(art)!!

        assertThat(hueOf(seed)).isWithin(6.0).of(hueOf(vividOrange.toInt()))
    }

    @Test
    fun `transparent pixels are ignored`() {
        val art = pixels(800 to 0x00FF0000, 200 to 0xFF2E7D32)

        val seed = ArtworkColors.accentSeed(art)!!

        assertThat(hueOf(seed)).isWithin(6.0).of(hueOf(0xFF2E7D32.toInt()))
        assertThat(seed ushr 24).isEqualTo(0xFF)
    }

    @Test
    fun `a few coloured specks on a grey cover give no colour`() {
        val art = pixels(995 to 0xFF808080, 5 to 0xFF00FF00)

        assertThat(ArtworkColors.accentSeed(art)).isNull()
    }

    @Test
    fun `the dominant colour is the one covering the most area, vivid or not`() {
        val paleTeal = 0xFF8FD3C4L
        val magenta = 0xFFE040A0L
        val dominant = ArtworkColors.dominant(pixels(650 to paleTeal, 350 to magenta))!!

        assertThat(hueOf(dominant)).isWithin(6.0).of(hueOf(paleTeal.toInt()))
    }

    @Test
    fun `a mostly black or grey cover is dominated by its neutral tone`() {
        val black = ArtworkColors.dominant(pixels(800 to 0xFF050505, 200 to 0xFFC0C0C0))!!
        assertThat(ColorMath.toOklch(black).l).isLessThan(0.45)
        assertThat(ColorMath.toOklch(black).c).isLessThan(0.02)

        val slate = ArtworkColors.dominant(pixels(700 to 0xFF6E7C86, 300 to 0xFFB04030))!!
        assertThat(ColorMath.toOklch(slate).c).isLessThan(0.05)
    }

    @Test
    fun `no opaque pixels give no dominant colour`() {
        assertThat(ArtworkColors.dominant(IntArray(0))).isNull()
        assertThat(ArtworkColors.dominant(pixels(10 to 0x00FF0000))).isNull()
    }
}
