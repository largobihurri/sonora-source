package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.playlist.PlaylistItem
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.feature.library.ui.newestFirst
import org.junit.Test

class VisualCollectionItemsTest {

    private fun coveredAlbum(id: Long, artwork: String?) = album(id, artist = 1).copy(artworkUri = artwork)

    @Test
    fun `albums give one cover each, in the list's order, with title and artist`() {
        val albums = listOf(coveredAlbum(3, "art:3"), coveredAlbum(1, null))

        val items = VisualCollectionItems.albums(albums)

        assertThat(items.map { it.key }).containsExactly(VisualCollectionTarget.Album(3), VisualCollectionTarget.Album(1)).inOrder()
        assertThat(items[0].covers).containsExactly("art:3")
        assertThat(items[1].covers).isEmpty() // the placeholder
        assertThat(items[0].title).isEqualTo("Album 3")
        assertThat(items[0].subtitle).isEqualTo("Artist 1")
    }

    @Test
    fun `songs give the distinct albums of the songs, in the order the songs list them`() {
        val albums = listOf(coveredAlbum(1, "art:1"), coveredAlbum(2, "art:2"), coveredAlbum(3, "art:3"))
        val songs = listOf(libraryTrack(10, album = 2), libraryTrack(11, album = 1), libraryTrack(12, album = 2), libraryTrack(13, album = 3))

        val items = VisualCollectionItems.songs(songs, albums)

        assertThat(items.map { it.key })
            .containsExactly(VisualCollectionTarget.Album(2), VisualCollectionTarget.Album(1), VisualCollectionTarget.Album(3)).inOrder()
    }

    @Test
    fun `songs whose album is not listed (hidden or not yet loaded) are skipped`() {
        val items = VisualCollectionItems.songs(listOf(libraryTrack(10, album = 9), libraryTrack(11, album = 1)), listOf(coveredAlbum(1, null)))

        assertThat(items.map { it.key }).containsExactly(VisualCollectionTarget.Album(1))
    }

    @Test
    fun `playlists give their own cover (mosaic or single) and song count`() {
        val tracks = (1L..4L).map { libraryTrack(it, album = it) }
        val mosaic = ResolvedPlaylist(
            Playlist(7, "Road"),
            tracks.map { PlaylistItem(PlaylistEntry.of(it), it) },
        )
        val empty = ResolvedPlaylist(Playlist(8, "Empty"), emptyList())

        val items = VisualCollectionItems.playlists(listOf(mosaic, empty)) { count -> "$count songs" }

        assertThat(items.map { it.key }).containsExactly(VisualCollectionTarget.Playlist(7), VisualCollectionTarget.Playlist(8)).inOrder()
        assertThat(items[0].covers).isEqualTo(mosaic.coverArtworkUris)
        assertThat(items[0].covers).hasSize(4)
        assertThat(items[0].title).isEqualTo("Road")
        assertThat(items[0].subtitle).isEqualTo("4 songs")
        assertThat(items[1].covers).isEmpty()
        assertThat(items[1].subtitle).isEqualTo("0 songs")
    }

    @Test
    fun `the play button shows only with something loaded, as play or pause`() {
        val track = libraryTrack(1)

        assertThat(visualCollectionPlayButton(PlaybackState())).isNull()
        assertThat(visualCollectionPlayButton(PlaybackState(currentTrack = track, isPlaying = true))).isEqualTo(VisualCollectionPlayButton.PAUSE)
        assertThat(visualCollectionPlayButton(PlaybackState(currentTrack = track, isPlaying = false))).isEqualTo(VisualCollectionPlayButton.PLAY)
    }

    @Test
    fun `the Now Playing capsule shows only with a track loaded, with that track, its artwork and album`() {
        val track = libraryTrack(7, album = 4, artist = 2)

        assertThat(visualCollectionNowPlaying(PlaybackState())).isNull()
        assertThat(visualCollectionNowPlaying(PlaybackState(currentTrack = track, isPlaying = true))).isEqualTo(
            VisualCollectionNowPlaying(trackId = 7, title = "Song 7", artist = "Artist 2", artworkUri = "art:4", albumId = 4, isPlaying = true),
        )
        assertThat(visualCollectionNowPlaying(PlaybackState(currentTrack = track, isPlaying = false))?.isPlaying).isFalse()
        assertThat(visualCollectionNowPlaying(PlaybackState(currentTrack = track.copy(artworkUri = null)))?.artworkUri).isNull()
    }

    @Test
    fun `the capsule reads Song then Artist, the song alone without an artist`() {
        val playing = visualCollectionNowPlaying(PlaybackState(currentTrack = libraryTrack(7, artist = 2)))!!

        assertThat(playing.caption).isEqualTo("Song 7 · Artist 2")
        assertThat(playing.copy(artist = " ").caption).isEqualTo("Song 7")
    }

    @Test
    fun `a back row shows its number, or the bars when its track is loaded, moving only while it plays`() {
        val playing = visualCollectionNowPlaying(PlaybackState(currentTrack = libraryTrack(7), isPlaying = true))
        val paused = playing?.copy(isPlaying = false)

        assertThat(visualCollectionRowState(trackId = 7, nowPlaying = playing)).isEqualTo(VisualCollectionRowState.PLAYING)
        assertThat(visualCollectionRowState(trackId = 7, nowPlaying = paused)).isEqualTo(VisualCollectionRowState.PAUSED)
        assertThat(visualCollectionRowState(trackId = 8, nowPlaying = playing)).isEqualTo(VisualCollectionRowState.NUMBER)
        assertThat(visualCollectionRowState(trackId = 7, nowPlaying = null)).isEqualTo(VisualCollectionRowState.NUMBER)
    }

    @Test
    fun `a visual collection opens on the cover of a target, or on the first cover when it is not listed`() {
        val items = VisualCollectionItems.albums(listOf(coveredAlbum(3, null), coveredAlbum(1, null), coveredAlbum(9, null)))

        assertThat(VisualCollectionItems.indexOf(items, VisualCollectionTarget.Album(9))).isEqualTo(2)
        assertThat(VisualCollectionItems.indexOf(items, VisualCollectionTarget.Album(5))).isEqualTo(0)
        assertThat(VisualCollectionItems.indexOf(items, null)).isEqualTo(0)
        assertThat(VisualCollectionItems.indexOf(emptyList(), VisualCollectionTarget.Album(1))).isEqualTo(0)
    }

    @Test
    fun `an artist's visual collection opens on that artist by id, whatever name the page was opened with`() {
        val artists = listOf(Artist(id = 4, name = "Nina", albumCount = 1, trackCount = 1), Artist(id = 9, name = "AC/DC", albumCount = 1, trackCount = 1))
        val items = VisualCollectionItems.artists(artists) { "$it" }

        assertThat(VisualCollectionItems.indexOf(items, VisualCollectionTarget.Artist(9, "AC/DC"))).isEqualTo(1)
        assertThat(VisualCollectionItems.indexOf(items, VisualCollectionTarget.Artist(9, "ACDC"))).isEqualTo(1)
        assertThat(VisualCollectionItems.indexOf(items, VisualCollectionTarget.Artist(5, "Nina"))).isEqualTo(0)
    }

    @Test
    fun `artists give one cover each, their front cover, with their album count`() {
        val artists = listOf(
            Artist(id = 4, name = "Nina", albumCount = 3, trackCount = 30, artworkUris = listOf("art:most", "art:2", "art:3")),
            Artist(id = 9, name = "Unknown", albumCount = 1, trackCount = 2),
        )

        val items = VisualCollectionItems.artists(artists) { count -> "$count albums" }

        assertThat(items.map { it.key })
            .containsExactly(VisualCollectionTarget.Artist(4, "Nina"), VisualCollectionTarget.Artist(9, "Unknown")).inOrder()
        assertThat(items[0].covers).containsExactly("art:most") // the stack's front cover only
        assertThat(items[0].title).isEqualTo("Nina")
        assertThat(items[0].subtitle).isEqualTo("3 albums")
        assertThat(items[1].covers).isEmpty() // the placeholder
    }

    @Test
    fun `an artist's back lists their albums newest first, as their page does`() {
        val old = album(1, artist = 4).copy(year = 1999, dateAddedSec = 10)
        val new = album(2, artist = 4).copy(year = 2021, dateAddedSec = 5)
        val undated = album(3, artist = 4).copy(year = null, dateAddedSec = 1)

        assertThat(VisualCollectionItems.artistBackAlbums(listOf(old, undated, new))).isEqualTo(newestFirst(listOf(old, undated, new)))
        assertThat(VisualCollectionItems.artistBackAlbums(listOf(old, new)).map { it.id }).containsExactly(2L, 1L).inOrder()
    }
}
