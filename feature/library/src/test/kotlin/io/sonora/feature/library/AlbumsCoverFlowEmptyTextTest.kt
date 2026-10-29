package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlbumsCoverFlowEmptyTextTest {

    private val loaded = LibraryUiState(loading = false, hasFolders = true)

    @Test
    fun `nothing is said while the library loads or when it has albums`() {
        assertThat(albumsCoverFlowEmptyText(LibraryUiState())).isNull()
        assertThat(albumsCoverFlowEmptyText(loaded.copy(albums = listOf(album(1, artist = 1))))).isNull()
    }

    @Test
    fun `an empty library says why, as the Library's lists do`() {
        assertThat(albumsCoverFlowEmptyText(loaded.copy(hasFolders = false))).isEqualTo(R.string.library_folders_empty)
        assertThat(albumsCoverFlowEmptyText(loaded.copy(scanning = true))).isEqualTo(R.string.library_scanning)
        assertThat(albumsCoverFlowEmptyText(loaded)).isEqualTo(R.string.library_no_music)
    }

    @Test
    fun `hiding albums without artwork when none has any says so`() {
        val artless = loaded.copy(albums = listOf(album(1, artist = 1)), hideAlbumsWithoutArtwork = true)

        assertThat(albumsCoverFlowEmptyText(artless)).isEqualTo(R.string.coverflow_no_albums_with_artwork)
    }

    @Test
    fun `hiding albums without artwork says nothing while one has artwork`() {
        val albums = listOf(album(1, artist = 1), album(2, artist = 1).copy(artworkUri = "art:2"))

        assertThat(albumsCoverFlowEmptyText(loaded.copy(albums = albums, hideAlbumsWithoutArtwork = true))).isNull()
    }
}
