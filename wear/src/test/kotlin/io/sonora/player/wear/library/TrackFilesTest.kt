package io.sonora.player.wear.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackFilesTest {

    @Test
    fun `the formats the watch plays are songs whatever their case`() {
        for (name in listOf("a.opus", "a.OGG", "a.oga", "a.mp3", "a.M4A", "a.aac", "a.flac", "a.wav")) {
            assertThat(TrackFiles.isPlayable(name)).isTrue()
        }
    }

    @Test
    fun `a transfer in progress or a non audio file is not a song`() {
        for (name in listOf("a.opus.part", "a.tmp", "cover.jpg", "notes.txt", "opus", "")) {
            assertThat(TrackFiles.isPlayable(name)).isFalse()
        }
    }

    @Test
    fun `a hidden file is not a song`() {
        assertThat(TrackFiles.isPlayable(".a.opus")).isFalse()
    }

    @Test
    fun `the title of a plain file name is the name without its extension`() {
        assertThat(TrackFiles.titleFromFileName("Midnight City.opus")).isEqualTo("Midnight City")
    }

    @Test
    fun `a leading track number is not part of the title`() {
        assertThat(TrackFiles.titleFromFileName("03 - Midnight City.opus")).isEqualTo("Midnight City")
        assertThat(TrackFiles.titleFromFileName("3. Midnight City.opus")).isEqualTo("Midnight City")
        assertThat(TrackFiles.titleFromFileName("1-05 Midnight City.opus")).isEqualTo("Midnight City")
        assertThat(TrackFiles.titleFromFileName("07 Midnight City.opus")).isEqualTo("Midnight City")
    }

    @Test
    fun `a title that is only a number stays as it is`() {
        assertThat(TrackFiles.titleFromFileName("1999.opus")).isEqualTo("1999")
        assertThat(TrackFiles.titleFromFileName("42.opus")).isEqualTo("42")
    }

    @Test
    fun `a name that is only numbers is the title`() {
        assertThat(TrackFiles.titleFromFileName("1-05.opus")).isEqualTo("1-05")
        assertThat(TrackFiles.titleFromFileName("03 - 1999.opus")).isEqualTo("03 - 1999")
    }

    @Test
    fun `underscores read as spaces`() {
        assertThat(TrackFiles.titleFromFileName("Midnight_City.opus")).isEqualTo("Midnight City")
    }

    @Test
    fun `an unknown extension is kept in the title`() {
        assertThat(TrackFiles.titleFromFileName("Mix v2.5")).isEqualTo("Mix v2.5")
    }
}
