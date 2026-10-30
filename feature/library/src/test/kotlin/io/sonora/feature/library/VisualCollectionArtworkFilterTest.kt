package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Artist
import org.junit.Test

class VisualCollectionArtworkFilterTest {

    private fun coveredAlbum(id: Long, artwork: String?) = album(id, artist = 1).copy(artworkUri = artwork)

    private val albums = listOf(coveredAlbum(1, "art:1"), coveredAlbum(2, null), coveredAlbum(3, "art:3"))

    @Test
    fun `the visual collection albums are the list's albums until the setting hides those without artwork`() {
        val state = LibraryUiState(loading = false, albums = albums)

        assertThat(visualCollectionAlbums(state)).isEqualTo(albums)
        assertThat(visualCollectionAlbums(state.copy(hideAlbumsWithoutArtwork = true)).map { it.id }).containsExactly(1L, 3L).inOrder()
    }

    @Test
    fun `hiding albums without artwork drops their album covers and nothing else`() {
        val songs = VisualCollectionItems.songs(listOf(libraryTrack(10, album = 2), libraryTrack(11, album = 3)), albums)

        assertThat(VisualCollectionItems.albums(albums).withArtwork(albums, hide = true).map { it.key })
            .containsExactly(VisualCollectionTarget.Album(1), VisualCollectionTarget.Album(3)).inOrder()
        assertThat(songs.withArtwork(albums, hide = true).map { it.key }).containsExactly(VisualCollectionTarget.Album(3))
        assertThat(VisualCollectionItems.albums(albums).withArtwork(albums, hide = false)).hasSize(3)
    }

    @Test
    fun `artists and playlists are never filtered`() {
        val artists = VisualCollectionItems.artists(listOf(Artist(1, "Queen", 1, 1)), albumCount = { "" })

        assertThat(artists.withArtwork(albums, hide = true)).isEqualTo(artists)
    }

    @Test
    fun `a place in the full list maps to the same cover among those shown, else its nearest neighbour`() {
        val shown = VisualCollectionShown(all = listOf(1, 2, 3, 4), shown = listOf(1, 3, 4))

        assertThat(shown.shownIndex(2)).isEqualTo(1) // 3 is still listed
        assertThat(shown.shownIndex(1)).isEqualTo(1) // 2 is hidden: 3 follows it
        assertThat(shown.shownIndex(0)).isEqualTo(0)
    }

    @Test
    fun `a shown centre is reported as its place in the full list`() {
        val shown = VisualCollectionShown(all = listOf(1, 2, 3, 4), shown = listOf(1, 3, 4))

        assertThat(shown.allIndex(1)).isEqualTo(2)
        assertThat(shown.allIndex(2)).isEqualTo(3)
        assertThat(shown.allIndex(9)).isEqualTo(9) // outside the shown list, left as it is
    }

    @Test
    fun `places survive turning the setting on and off`() {
        val all = listOf(1, 2, 3, 4)
        val hidden = VisualCollectionShown(all, shown = listOf(1, 3, 4))
        val every = VisualCollectionShown(all, shown = all)

        // Centred on 4 while every album shows; hiding keeps 4, and showing them again keeps it too.
        val placeWhileHidden = hidden.allIndex(hidden.shownIndex(3))
        assertThat(every.shownIndex(placeWhileHidden)).isEqualTo(3)
    }

    @Test
    fun `the centre stays on its cover when the list changes before the setting is turned`() {
        val centre = VisualCollectionCentre<String>()
        assertThat(centre.place(listOf("a", "b", "c"), opening = 1)).isEqualTo(1)

        centre.report(2, "c")
        // A rescan adds a cover before it: the centre follows "c" rather than its old place.
        assertThat(centre.place(listOf("new", "a", "b", "c"), opening = 0)).isEqualTo(3)
        // Its cover gone, it keeps the place it had.
        assertThat(centre.place(listOf("a", "b", "d"), opening = 0)).isEqualTo(2)
    }
}
