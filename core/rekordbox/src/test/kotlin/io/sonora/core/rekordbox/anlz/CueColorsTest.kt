package io.sonora.core.rekordbox.anlz

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CueColorsTest {

    @Test
    fun `there are 62 hot cue colour codes and each maps to itself`() {
        assertThat(CueColors.HOT_CUE_DISPLAY_RGB).hasSize(62)
        for (code in 1..62) {
            assertThat(CueColors.hotCueCode(CueColors.hotCueRgb(code))).isEqualTo(code)
        }
    }

    @Test
    fun `colours seen in rekordbox exports keep the code rekordbox gave them where the tables agree`() {
        // Pairs of code and explicit RGB from the PCP2 entries of a rekordbox export: the player's display colour of
        // code 9 is the same 00e0ff, and the saturated pad colours land near their neighbours.
        assertThat(CueColors.hotCueCode(0x00E0FF)).isEqualTo(0x09)
        assertThat(CueColors.hotCueCode(0x28E214)).isEqualTo(0x16)
        assertThat(CueColors.hotCueCode(0x305AFF)).isEqualTo(0x01)
    }

    @Test
    fun `a colour between two codes takes the nearer one and the code is never zero`() {
        for (rgb in listOf(0xFFFFFF, 0x000000, 0x808080, 0xFF0000, 0x00FF00, 0x0000FF, 0xFF3B30, 0xE6E6E6)) {
            assertThat(CueColors.hotCueCode(rgb)).isIn(1..62)
        }
        assertThat(CueColors.hotCueCode(0xFF0000)).isIn(listOf(0x28, 0x29, 0x2A))
    }

    @Test
    fun `memory colours are the eight rekordbox colours by name`() {
        assertThat(CueColors.MEMORY_NAMES)
            .containsExactly("Pink", "Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple").inOrder()
        for (id in 1..8) assertThat(CueColors.memoryColorId(CueColors.memoryRgb(id))).isEqualTo(id)
        assertThat(CueColors.memoryColorId(0xFF3B30)).isEqualTo(2) // Red
        assertThat(CueColors.memoryColorId(0x30C8E8)).isEqualTo(6) // Aqua
        assertThat(CueColors.memoryColorId(0x2F5BFF)).isEqualTo(7) // Blue
        assertThat(CueColors.memoryColorId(0xE040FB)).isIn(listOf(1, 8))
    }
}
