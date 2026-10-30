package io.sonora.feature.dub

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The effects page next to the preamp: the XY pad over the fixed rows, or beside them when that leaves it too small. */
class EffectsFitTest {

    private fun fit(width: Int, height: Int, controls: Int, besideAllowed: Boolean = true) = EffectsFit.of(
        width = width,
        height = height,
        controlsHeight = controls,
        gap = 8,
        minPad = 200,
        maxPad = 420,
        controlsMinWidth = 260,
        besideAllowed = besideAllowed,
    )

    @Test
    fun `a wide short area on a tablet puts the pad beside the controls`() {
        // The Pad 6 in landscape: the rows under the pad would leave it 52 dp.
        val fit = fit(width = 556, height = 420, controls = 360)

        assertThat(fit.beside).isTrue()
        assertThat(fit.pad).isEqualTo(288)
        assertThat(fit.controlsWidth).isEqualTo(260)
    }

    @Test
    fun `beside the controls the pad stops at the area height and at its largest`() {
        assertThat(fit(width = 1000, height = 380, controls = 360).pad).isEqualTo(380)
        val wide = fit(width = 1200, height = 600, controls = 500)

        assertThat(wide.pad).isEqualTo(420)
        assertThat(wide.controlsWidth).isEqualTo(772)
    }

    @Test
    fun `a pad that keeps its minimum above the controls stays above them`() {
        val fit = fit(width = 700, height = 650, controls = 360)

        assertThat(fit.beside).isFalse()
        assertThat(fit.pad).isEqualTo(282)
        assertThat(fit.controlsWidth).isEqualTo(700)
    }

    @Test
    fun `an area taller than wide keeps the pad above the controls however small`() {
        val fit = fit(width = 500, height = 520, controls = 480)

        assertThat(fit.beside).isFalse()
        assertThat(fit.pad).isEqualTo(32)
    }

    @Test
    fun `without the tablet allowance the stacked layout is kept`() {
        val fit = fit(width = 556, height = 420, controls = 360, besideAllowed = false)

        assertThat(fit.beside).isFalse()
        assertThat(fit.pad).isEqualTo(52)
        assertThat(fit.controlsWidth).isEqualTo(556)
    }

    @Test
    fun `beside is not taken when it would leave the pad smaller still`() {
        val fit = fit(width = 300, height = 290, controls = 200)

        assertThat(fit.beside).isFalse()
        assertThat(fit.pad).isEqualTo(82)
    }

    @Test
    fun `stacked the pad never goes below nothing nor past the width or its largest`() {
        assertThat(fit(width = 556, height = 300, controls = 360, besideAllowed = false).pad).isEqualTo(0)
        assertThat(fit(width = 300, height = 900, controls = 300).pad).isEqualTo(300)
        assertThat(fit(width = 800, height = 1200, controls = 300).pad).isEqualTo(420)
    }
}
