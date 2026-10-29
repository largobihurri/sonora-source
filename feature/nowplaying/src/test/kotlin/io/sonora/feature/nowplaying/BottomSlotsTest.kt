package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.feature.nowplaying.BottomSlot.CAST
import io.sonora.feature.nowplaying.BottomSlot.EQUALIZER
import io.sonora.feature.nowplaying.BottomSlot.FILE_INFO
import io.sonora.feature.nowplaying.BottomSlot.LYRICS
import io.sonora.feature.nowplaying.BottomSlot.OUTPUT
import io.sonora.feature.nowplaying.BottomSlot.UP_NEXT
import org.junit.Test

class BottomSlotsTest {

    @Test
    fun `every button shows in order equalizer, lyrics, up next, output, file info`() {
        assertThat(bottomSlots(lyrics = true, output = true, fileInfo = true))
            .containsExactly(EQUALIZER, LYRICS, UP_NEXT, OUTPUT, FILE_INFO).inOrder()
    }

    @Test
    fun `with lyrics off the equalizer still leads and up next follows it`() {
        assertThat(bottomSlots(lyrics = false, output = true, fileInfo = true))
            .containsExactly(EQUALIZER, UP_NEXT, OUTPUT, FILE_INFO).inOrder()
    }

    @Test
    fun `a device without an output switcher leaves it out`() {
        assertThat(bottomSlots(lyrics = true, output = false, fileInfo = true))
            .containsExactly(EQUALIZER, LYRICS, UP_NEXT, FILE_INFO).inOrder()
    }

    @Test
    fun `with file info off the output switcher ends the row`() {
        assertThat(bottomSlots(lyrics = true, output = true, fileInfo = false))
            .containsExactly(EQUALIZER, LYRICS, UP_NEXT, OUTPUT).inOrder()
    }

    @Test
    fun `the equalizer and up next always show`() {
        assertThat(bottomSlots(lyrics = false, output = false, fileInfo = false))
            .containsExactly(EQUALIZER, UP_NEXT).inOrder()
    }

    @Test
    fun `the cast button sits right after the output switcher`() {
        assertThat(bottomSlots(lyrics = true, output = true, fileInfo = true, cast = true))
            .containsExactly(EQUALIZER, LYRICS, UP_NEXT, OUTPUT, CAST, FILE_INFO).inOrder()
    }

    @Test
    fun `without an output switcher the cast button follows up next`() {
        assertThat(bottomSlots(lyrics = false, output = false, fileInfo = false, cast = true))
            .containsExactly(EQUALIZER, UP_NEXT, CAST).inOrder()
    }

    @Test
    fun `a device that cannot cast shows no cast button`() {
        assertThat(bottomSlots(lyrics = true, output = true, fileInfo = true, cast = false)).doesNotContain(CAST)
    }
}
