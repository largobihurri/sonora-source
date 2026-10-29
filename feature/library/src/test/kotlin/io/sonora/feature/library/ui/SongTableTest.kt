package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SongTableTest {

    @Test
    fun `a phone has no table`() {
        assertThat(SongTable.of(widthDp = null, artistAndAlbum = true)).isEqualTo(SongTable.None)
        assertThat(SongTable.of(widthDp = null, artistAndAlbum = false)).isEqualTo(SongTable.None)
        assertThat(SongTable.None.isTable).isFalse()
    }

    @Test
    fun `a narrow song list keeps the phone row`() {
        assertThat(SongTable.of(widthDp = 559f, artistAndAlbum = true)).isEqualTo(SongTable.None)
    }

    @Test
    fun `a medium song list adds the artist and the length`() {
        assertThat(SongTable.of(widthDp = 560f, artistAndAlbum = true))
            .isEqualTo(SongTable(artist = true, album = false, duration = true))
        assertThat(SongTable.of(widthDp = 759f, artistAndAlbum = true))
            .isEqualTo(SongTable(artist = true, album = false, duration = true))
    }

    @Test
    fun `a wide song list shows title, artist, album and length`() {
        assertThat(SongTable.of(widthDp = 760f, artistAndAlbum = true))
            .isEqualTo(SongTable(artist = true, album = true, duration = true))
        // The table cap (840 dp) is wide enough.
        assertThat(SongTable.of(widthDp = 840f, artistAndAlbum = true).album).isTrue()
    }

    @Test
    fun `numbered album rows show only the length`() {
        val durationOnly = SongTable(artist = false, album = false, duration = true)
        assertThat(SongTable.of(widthDp = 360f, artistAndAlbum = false)).isEqualTo(durationOnly)
        assertThat(SongTable.of(widthDp = 840f, artistAndAlbum = false)).isEqualTo(durationOnly)
        assertThat(SongTable.of(widthDp = 359f, artistAndAlbum = false)).isEqualTo(SongTable.None)
    }

    @Test
    fun `the Pad 6 gets a table in either orientation without a sidebar`() {
        // Capped at 840 dp in landscape (1152 dp window) and 720 dp wide upright.
        assertThat(SongTable.of(widthDp = 840f, artistAndAlbum = true).isTable).isTrue()
        assertThat(SongTable.of(widthDp = 720f, artistAndAlbum = true).album).isFalse()
        assertThat(SongTable.of(widthDp = 720f, artistAndAlbum = true).artist).isTrue()
    }
}
