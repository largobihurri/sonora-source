package io.sonora.core.designsystem.component

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranslucentVinylPaletteTest {

    private val accent = Color(0xFF8B1A2B)

    @Test
    fun `the record label is tinted with the user's accent`() {
        val palette = TranslucentVinylPalette.of(accent, dark = false)

        assertThat(palette.labelEdge.copy(alpha = 1f)).isEqualTo(accent)
        // The centre catches the light: the same hue, brighter.
        assertThat(palette.labelCentre.red).isGreaterThan(accent.red)
        assertThat(palette.labelCentre.green).isGreaterThan(accent.green)
    }

    @Test
    fun `the light behind the translucent disc follows the accent`() {
        val red = TranslucentVinylPalette.of(accent, dark = true)
        val blue = TranslucentVinylPalette.of(Color(0xFF0A84FF), dark = true)

        assertThat(red.glow.copy(alpha = 1f)).isEqualTo(accent)
        assertThat(blue.glow.copy(alpha = 1f)).isEqualTo(Color(0xFF0A84FF))
        assertThat(red.glow.alpha).isLessThan(1f)
    }

    @Test
    fun `the tile is a light neutral in light mode and a dark neutral in dark mode`() {
        val light = TranslucentVinylPalette.of(accent, dark = false)
        val dark = TranslucentVinylPalette.of(accent, dark = true)

        assertThat(light.tileTop).isEqualTo(Color(0xFFF4F2F5))
        assertThat(light.tileBottom).isEqualTo(Color(0xFFDCD8DE))
        assertThat(dark.tileTop).isEqualTo(Color(0xFF2E2B2F))
        assertThat(dark.tileBottom).isEqualTo(Color(0xFF141215))
    }
}
