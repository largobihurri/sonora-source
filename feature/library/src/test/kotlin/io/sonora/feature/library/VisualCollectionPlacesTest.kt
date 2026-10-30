package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisualCollectionPlacesTest {

    private val albumsList = LibraryVisualCollectionView(LibraryDest.Albums, target = null)
    private val albumPage = LibraryVisualCollectionView(LibraryDest.Albums, VisualCollectionTarget.Album(7))
    private val items = VisualCollectionItems.albums(listOf(album(5, artist = 1), album(6, artist = 1), album(7, artist = 1)))

    @Test
    fun `a list's visual collection opens on the first cover the first time`() {
        assertThat(VisualCollectionPlaces().opening(albumsList, items)).isEqualTo(0)
    }

    @Test
    fun `a list's visual collection reopens where the user left it`() {
        val places = VisualCollectionPlaces().left(albumsList, 2)

        assertThat(places.opening(albumsList, items)).isEqualTo(2)
    }

    @Test
    fun `each list keeps its own place`() {
        val songsList = LibraryVisualCollectionView(LibraryDest.Songs, target = null)
        val places = VisualCollectionPlaces().left(albumsList, 2).left(songsList, 1)

        assertThat(places.opening(albumsList, items)).isEqualTo(2)
        assertThat(places.opening(songsList, items)).isEqualTo(1)
        assertThat(places.opening(LibraryVisualCollectionView(LibraryDest.Artists, target = null), items)).isEqualTo(0)
    }

    @Test
    fun `a page's visual collection always opens centred on that page, whatever its list kept`() {
        val places = VisualCollectionPlaces().left(albumsList, 0)

        assertThat(places.opening(albumPage, items)).isEqualTo(2)
    }

    @Test
    fun `a page's visual collection leaves no place behind for its list`() {
        val places = VisualCollectionPlaces().left(albumsList, 1).left(albumPage, 0)

        assertThat(places.opening(albumsList, items)).isEqualTo(1)
    }

    @Test
    fun `the places survive being saved and restored`() {
        val places = VisualCollectionPlaces()
            .left(albumsList, 2)
            .left(LibraryVisualCollectionView(LibraryDest.Playlists, target = null), 4)

        assertThat(VisualCollectionPlaces.decode(places.encode())).isEqualTo(places)
    }

    @Test
    fun `unreadable saved entries are dropped`() {
        val restored = VisualCollectionPlaces.decode(listOf("albums=3", "nowhere=1", "songs=x", "artists"))

        assertThat(restored).isEqualTo(VisualCollectionPlaces().left(albumsList, 3))
    }
}
