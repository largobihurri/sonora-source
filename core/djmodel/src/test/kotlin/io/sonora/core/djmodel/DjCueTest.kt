package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class DjCueTest {

    private val green = CueColor(6, 0x28E214)

    private fun hot(slot: Int? = 0, position: Double = 1_000.0, loopEnd: Double? = null, name: String = "") =
        DjCue(DjCue.hotId(slot ?: 0), CueKind.HOT, slot, position, loopEnd, green, name)

    @Test
    fun `a hot cue id is derived from its pad so setting a pad again replaces its cue`() {
        assertThat(DjCue.hotId(0)).isEqualTo("hot-0")
        assertThat(DjCue.hotId(7)).isEqualTo("hot-7")
    }

    @Test
    fun `pads are labelled A to H`() {
        assertThat((0 until DjCue.HOT_SLOTS).map(DjCue::slotLabel).joinToString("")).isEqualTo("ABCDEFGH")
        assertThat(hot(slot = 2).label).isEqualTo('C')
    }

    @Test
    fun `a memory cue has no label`() {
        val memory = DjCue("m1", CueKind.MEMORY, null, 500.0, null, green, "")

        assertThat(memory.label).isNull()
        assertThat(memory.isLoop).isFalse()
    }

    @Test
    fun `a cue with a loop end is a saved loop`() {
        assertThat(hot(loopEnd = 3_000.0).isLoop).isTrue()
    }

    @Test
    fun `a hot cue needs a pad from A to H and a memory cue needs none`() {
        assertThrows(IllegalArgumentException::class.java) { DjCue("x", CueKind.HOT, null, 0.0, null, green, "") }
        assertThrows(IllegalArgumentException::class.java) { DjCue("x", CueKind.HOT, 8, 0.0, null, green, "") }
        assertThrows(IllegalArgumentException::class.java) { DjCue("x", CueKind.HOT, -1, 0.0, null, green, "") }
        assertThrows(IllegalArgumentException::class.java) { DjCue("x", CueKind.MEMORY, 0, 0.0, null, green, "") }
    }

    @Test
    fun `the position must be finite and not negative and a loop must end after it`() {
        assertThrows(IllegalArgumentException::class.java) { hot(position = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { hot(position = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { hot(position = Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { hot(position = 1_000.0, loopEnd = 1_000.0) }
        assertThrows(IllegalArgumentException::class.java) { hot(position = 1_000.0, loopEnd = Double.NaN) }
    }

    @Test
    fun `the id must not be blank and the name has a limit`() {
        assertThrows(IllegalArgumentException::class.java) { DjCue(" ", CueKind.MEMORY, null, 0.0, null, green, "") }
        assertThrows(IllegalArgumentException::class.java) { hot(name = "x".repeat(DjCue.MAX_NAME_LENGTH + 1)) }
        assertThat(hot(name = "x".repeat(DjCue.MAX_NAME_LENGTH)).name).hasLength(DjCue.MAX_NAME_LENGTH)
    }

    @Test
    fun `a colour keeps its palette index and its rgb within range`() {
        assertThrows(IllegalArgumentException::class.java) { CueColor(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { CueColor(CuePalette.SIZE + 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { CueColor(1, 0x1000000) }
        assertThrows(IllegalArgumentException::class.java) { CueColor(1, -1) }
        assertThat(CueColor.custom(0x123456).paletteIndex).isEqualTo(0)
    }
}
