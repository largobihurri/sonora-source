package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoverFlowPlacesTest {

    private val albumsList = LibraryCoverFlowView(LibraryDest.Albums, target = null)
    private val albumPage = LibraryCoverFlowView(LibraryDest.Albums, CoverFlowTarget.Album(7))
    private val items = CoverFlowItems.albums(listOf(album(5, artist = 1), album(6, artist = 1), album(7, artist = 1)))

    @Test
    fun `a list's Cover Flow opens on the first cover the first time`() {
        assertThat(CoverFlowPlaces().opening(albumsList, items)).isEqualTo(0)
    }

    @Test
    fun `a list's Cover Flow reopens where the user left it`() {
        val places = CoverFlowPlaces().left(albumsList, 2)

        assertThat(places.opening(albumsList, items)).isEqualTo(2)
    }

    @Test
    fun `each list keeps its own place`() {
        val songsList = LibraryCoverFlowView(LibraryDest.Songs, target = null)
        val places = CoverFlowPlaces().left(albumsList, 2).left(songsList, 1)

        assertThat(places.opening(albumsList, items)).isEqualTo(2)
        assertThat(places.opening(songsList, items)).isEqualTo(1)
        assertThat(places.opening(LibraryCoverFlowView(LibraryDest.Artists, target = null), items)).isEqualTo(0)
    }

    @Test
    fun `a page's Cover Flow always opens centred on that page, whatever its list kept`() {
        val places = CoverFlowPlaces().left(albumsList, 0)

        assertThat(places.opening(albumPage, items)).isEqualTo(2)
    }

    @Test
    fun `a page's Cover Flow leaves no place behind for its list`() {
        val places = CoverFlowPlaces().left(albumsList, 1).left(albumPage, 0)

        assertThat(places.opening(albumsList, items)).isEqualTo(1)
    }

    @Test
    fun `the places survive being saved and restored`() {
        val places = CoverFlowPlaces()
            .left(albumsList, 2)
            .left(LibraryCoverFlowView(LibraryDest.Playlists, target = null), 4)

        assertThat(CoverFlowPlaces.decode(places.encode())).isEqualTo(places)
    }

    @Test
    fun `unreadable saved entries are dropped`() {
        val restored = CoverFlowPlaces.decode(listOf("albums=3", "nowhere=1", "songs=x", "artists"))

        assertThat(restored).isEqualTo(CoverFlowPlaces().left(albumsList, 3))
    }
}
