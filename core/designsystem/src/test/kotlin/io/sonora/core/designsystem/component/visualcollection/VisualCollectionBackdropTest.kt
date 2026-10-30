package io.sonora.core.designsystem.component.visualcollection

import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.theme.ArtworkPalette
import io.sonora.core.designsystem.theme.ColorMath
import kotlin.math.abs
import org.junit.Test

class VisualCollectionBackdropTest {

    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFD0302A.toInt()
    private val navy = 0xFF0B1A3A.toInt()

    @Test
    fun `darkening lowers the luminance and keeps the hue`() {
        val dark = VisualCollectionBackdrop.darkened(red)

        assertThat(ColorMath.relativeLuminance(dark)).isLessThan(ColorMath.relativeLuminance(red))
        val hueShift = abs(ColorMath.toOklch(dark).h - ColorMath.toOklch(red).h)
        assertThat(minOf(hueShift, 360 - hueShift)).isLessThan(8.0)
    }

    @Test
    fun `darkening caps even white at a deep tone`() {
        assertThat(ColorMath.toOklch(VisualCollectionBackdrop.darkened(white)).l).isAtMost(VisualCollectionBackdrop.MAX_LIGHTNESS + 0.005) // 8-bit rounding
        assertThat(ColorMath.contrastRatio(VisualCollectionBackdrop.darkened(white), white)).isAtLeast(7.0)
    }

    @Test
    fun `the scrim keeps white text readable over the brightest blurred artwork`() {
        for (dominant in listOf(white, red, navy, null)) {
            val palette = ArtworkPalette.from(dominant)
            val scrim = VisualCollectionBackdrop.darkened(palette.background)
            val alpha = VisualCollectionBackdrop.scrimAlpha(palette.background, scrim)

            val worst = VisualCollectionBackdrop.blend(palette.background, white, VisualCollectionBackdrop.ARTWORK_ALPHA)
            val shown = VisualCollectionBackdrop.blend(worst, scrim, alpha)
            assertThat(ColorMath.contrastRatio(white, shown)).isAtLeast(VisualCollectionBackdrop.TEXT_CONTRAST)
            assertThat(alpha).isAtLeast(VisualCollectionBackdrop.MIN_SCRIM)
            assertThat(alpha).isAtMost(1f)
        }
    }

    @Test
    fun `the scrim is no heavier than it needs to be`() {
        val palette = ArtworkPalette.from(red)
        val scrim = VisualCollectionBackdrop.darkened(palette.background)
        val alpha = VisualCollectionBackdrop.scrimAlpha(palette.background, scrim)

        if (alpha > VisualCollectionBackdrop.MIN_SCRIM) {
            val worst = VisualCollectionBackdrop.blend(palette.background, white, VisualCollectionBackdrop.ARTWORK_ALPHA)
            val lighter = VisualCollectionBackdrop.blend(worst, scrim, alpha - 0.03f)
            assertThat(ColorMath.contrastRatio(white, lighter)).isLessThan(VisualCollectionBackdrop.TEXT_CONTRAST)
        }
    }

    @Test
    fun `blending goes from the colour below to the colour above`() {
        assertThat(VisualCollectionBackdrop.blend(navy, white, 0f)).isEqualTo(navy)
        assertThat(VisualCollectionBackdrop.blend(navy, white, 1f)).isEqualTo(white)
        assertThat(VisualCollectionBackdrop.blend(0xFF000000.toInt(), white, 0.5f) and 0xFF).isAnyOf(127, 128)
    }
}
