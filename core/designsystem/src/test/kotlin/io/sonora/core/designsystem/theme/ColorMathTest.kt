package io.sonora.core.designsystem.theme

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class ColorMathTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun hueDistance(a: Int, b: Int): Double {
        val d = abs(ColorMath.toOklch(a).h - ColorMath.toOklch(b).h) % 360.0
        return minOf(d, 360.0 - d)
    }

    @Test
    fun `contrast ratio follows WCAG`() {
        assertThat(ColorMath.contrastRatio(white, black)).isWithin(0.01).of(21.0)
        assertThat(ColorMath.contrastRatio(black, white)).isWithin(0.01).of(21.0)
        assertThat(ColorMath.contrastRatio(0xFF777777.toInt(), 0xFF777777.toInt())).isWithin(1e-9).of(1.0)
        // #767676 is the classic lightest grey reaching 4.5:1 on white.
        assertThat(ColorMath.contrastRatio(0xFF767676.toInt(), white)).isWithin(0.02).of(4.54)
    }

    @Test
    fun `OKLCH round trips in gamut colours`() {
        for (color in listOf(0xFFFA2D48, 0xFF007AFF, 0xFF34C759, 0xFFFFCC00, 0xFF808080).map { it.toInt() }) {
            assertThat(ColorMath.fromOklch(ColorMath.toOklch(color))).isEqualTo(color)
        }
    }

    @Test
    fun `a colour that already passes is kept as it is`() {
        val darkBlue = 0xFF0040DD.toInt()

        assertThat(ColorMath.ensureContrast(darkBlue, LIGHT)).isEqualTo(darkBlue)
    }

    @Test
    fun `yellow is darkened just enough to reach AA on light backgrounds and keeps its hue`() {
        val yellow = 0xFFFFCC00.toInt()

        val adjusted = ColorMath.ensureContrast(yellow, LIGHT)

        LIGHT.forEach { assertThat(ColorMath.contrastRatio(adjusted, it)).isAtLeast(4.5) }
        assertThat(LIGHT.minOf { ColorMath.contrastRatio(adjusted, it) }).isLessThan(4.8) // not over-adjusted
        assertThat(hueDistance(adjusted, yellow)).isLessThan(4.0)
    }

    @Test
    fun `a deep colour is lightened to reach AA on true black and grouped grey and keeps its hue`() {
        val navy = 0xFF1A237E.toInt()

        val adjusted = ColorMath.ensureContrast(navy, DARK)

        DARK.forEach { assertThat(ColorMath.contrastRatio(adjusted, it)).isAtLeast(4.5) }
        assertThat(DARK.minOf { ColorMath.contrastRatio(adjusted, it) }).isLessThan(4.8)
        assertThat(hueDistance(adjusted, navy)).isLessThan(4.0)
    }

    @Test
    fun `content on an accent is white on deep colours and black on light ones`() {
        assertThat(ColorMath.contentOn(0xFF0040DD.toInt())).isEqualTo(white)
        assertThat(ColorMath.contentOn(0xFFFFCC00.toInt())).isEqualTo(black)
    }

    private companion object {
        val LIGHT = listOf(0xFFFFFFFF.toInt(), 0xFFF2F2F7.toInt())
        val DARK = listOf(0xFF000000.toInt(), 0xFF1C1C1E.toInt())
    }
}
