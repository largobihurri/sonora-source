package io.sonora.core.designsystem.theme

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.AccentMode
import io.sonora.core.domain.model.AccentPreset
import io.sonora.core.domain.model.AppearancePreferences
import org.junit.Test

class AccentResolverTest {

    private val dynamicLight = 0xFF3F51B5.toInt()
    private val dynamicDark = 0xFFB0BEFF.toInt()
    private val asked = mutableListOf<Boolean>()
    private val wallpaper: (Boolean) -> Int? = { dark -> asked += dark; if (dark) dynamicDark else dynamicLight }

    private fun resolve(mode: AccentMode, sdk: Int, seed: Int? = null, preset: AccentPreset = AccentPreset.PINK, dynamic: (Boolean) -> Int? = wallpaper) =
        AccentResolver.resolve(AppearancePreferences(accentMode = mode, preset = preset), sdk, dynamic, seed)

    @Test
    fun `every preset reaches AA on the backgrounds of both themes`() {
        for (preset in AccentPreset.entries) {
            val accent = AccentResolver.preset(preset)
            AccentResolver.LIGHT_BACKGROUNDS.forEach { assertThat(ColorMath.contrastRatio(accent.light, it)).isAtLeast(4.5) }
            AccentResolver.DARK_BACKGROUNDS.forEach { assertThat(ColorMath.contrastRatio(accent.dark, it)).isAtLeast(4.5) }
        }
    }

    @Test
    fun `the preset mode uses the chosen preset`() {
        assertThat(resolve(AccentMode.PRESET, sdk = 36, preset = AccentPreset.TEAL)).isEqualTo(AccentResolver.preset(AccentPreset.TEAL))
        assertThat(asked).isEmpty()
    }

    @Test
    fun `Material You falls back to Sonora pink on Android 10 and 11 without asking for wallpaper colours`() {
        assertThat(resolve(AccentMode.MATERIAL_YOU, sdk = 29, preset = AccentPreset.BLUE)).isEqualTo(AccentResolver.preset(AccentPreset.PINK))
        assertThat(resolve(AccentMode.MATERIAL_YOU, sdk = 30)).isEqualTo(AccentResolver.preset(AccentPreset.PINK))
        assertThat(asked).isEmpty()
    }

    @Test
    fun `Material You uses the wallpaper colour of each theme from Android 12`() {
        val accent = resolve(AccentMode.MATERIAL_YOU, sdk = 31)

        assertThat(asked).containsExactly(false, true)
        assertThat(accent.light).isEqualTo(ColorMath.ensureContrast(dynamicLight, AccentResolver.LIGHT_BACKGROUNDS))
        assertThat(accent.dark).isEqualTo(ColorMath.ensureContrast(dynamicDark, AccentResolver.DARK_BACKGROUNDS))
    }

    @Test
    fun `Material You without wallpaper colours falls back to Sonora pink`() {
        assertThat(resolve(AccentMode.MATERIAL_YOU, sdk = 33, dynamic = { null })).isEqualTo(AccentResolver.preset(AccentPreset.PINK))
    }

    @Test
    fun `follow artwork uses the artwork colour made legible for each theme`() {
        val paleYellow = 0xFFFFF59D.toInt()

        val accent = resolve(AccentMode.ARTWORK, sdk = 36, seed = paleYellow)

        assertThat(accent.light).isEqualTo(ColorMath.ensureContrast(paleYellow, AccentResolver.LIGHT_BACKGROUNDS))
        assertThat(accent.dark).isEqualTo(ColorMath.ensureContrast(paleYellow, AccentResolver.DARK_BACKGROUNDS))
        assertThat(ColorMath.contrastRatio(accent.light, 0xFFFFFFFF.toInt())).isAtLeast(4.5)
    }

    @Test
    fun `follow artwork without a colour falls back to the chosen preset`() {
        assertThat(resolve(AccentMode.ARTWORK, sdk = 36, seed = null, preset = AccentPreset.GREEN))
            .isEqualTo(AccentResolver.preset(AccentPreset.GREEN))
    }

    @Test
    fun `the accent of a theme is picked by darkness`() {
        val accent = AccentResolver.preset(AccentPreset.BLUE)

        assertThat(accent.forTheme(dark = true)).isEqualTo(accent.dark)
        assertThat(accent.forTheme(dark = false)).isEqualTo(accent.light)
    }
}
