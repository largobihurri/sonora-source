package io.sonora.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SonoraColorSchemeTest {

    private val accent = Color(0xFF0A84FF)

    @Test
    fun `dark is true black with grouped greys and the accent as primary`() {
        val scheme = sonoraColorScheme(dark = true, accent = accent)

        assertThat(scheme.background).isEqualTo(Color.Black)
        assertThat(scheme.surface).isEqualTo(Color.Black)
        assertThat(scheme.surfaceVariant).isEqualTo(Color(0xFF1C1C1E))
        assertThat(scheme.primary).isEqualTo(accent)
        assertThat(scheme.onBackground).isEqualTo(Color.White)
    }

    @Test
    fun `light is white with the iOS grouped grey`() {
        val scheme = sonoraColorScheme(dark = false, accent = accent)

        assertThat(scheme.background).isEqualTo(Color.White)
        assertThat(scheme.surfaceVariant).isEqualTo(Color(0xFFF2F2F7))
        assertThat(scheme.primary).isEqualTo(accent)
    }

    @Test
    fun `text on the accent stays readable`() {
        val yellow = Color(0xFFFFD60A)

        assertThat(sonoraColorScheme(dark = true, accent = yellow).onPrimary).isEqualTo(Color.Black)
        assertThat(sonoraColorScheme(dark = false, accent = Color(0xFF0040DD)).onPrimary.toArgb()).isEqualTo(Color.White.toArgb())
    }
}
