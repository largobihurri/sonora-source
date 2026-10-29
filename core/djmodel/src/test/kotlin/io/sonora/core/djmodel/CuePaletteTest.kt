package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CuePaletteTest {

    @Test
    fun `the palette has sixteen distinct colours indexed from 1`() {
        assertThat(CuePalette.COLORS).hasSize(CuePalette.SIZE)
        assertThat(CuePalette.COLORS.map { it.paletteIndex }).containsExactlyElementsIn(1..CuePalette.SIZE).inOrder()
        assertThat(CuePalette.COLORS.map { it.rgb }.toSet()).hasSize(CuePalette.SIZE)
    }

    @Test
    fun `a colour is found by its index and nothing else is`() {
        assertThat(CuePalette.byIndex(1)).isEqualTo(CuePalette.COLORS.first())
        assertThat(CuePalette.byIndex(CuePalette.SIZE)).isEqualTo(CuePalette.COLORS.last())
        assertThat(CuePalette.byIndex(0)).isNull()
        assertThat(CuePalette.byIndex(CuePalette.SIZE + 1)).isNull()
    }

    @Test
    fun `each pad has its own default colour from the palette and A is green`() {
        val defaults = (0 until DjCue.HOT_SLOTS).map(CuePalette::defaultHot)

        assertThat(defaults.toSet()).hasSize(DjCue.HOT_SLOTS)
        assertThat(CuePalette.COLORS).containsAtLeastElementsIn(defaults)
        assertThat(defaults.first().rgb).isEqualTo(0x28E214)
    }

    @Test
    fun `a memory cue is red by default`() {
        assertThat(CuePalette.MEMORY_DEFAULT).isEqualTo(CuePalette.byIndex(2))
        assertThat(CuePalette.MEMORY_DEFAULT.rgb).isEqualTo(0xFF3B30)
    }
}
