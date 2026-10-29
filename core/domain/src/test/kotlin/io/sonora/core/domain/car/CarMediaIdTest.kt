package io.sonora.core.domain.car

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CarMediaIdTest {

    private val every = listOf(
        CarMediaId.Root,
        CarMediaId.Tab(CarTab.PLAYLISTS),
        CarMediaId.Tab(CarTab.ALBUMS),
        CarMediaId.Tab(CarTab.ARTISTS),
        CarMediaId.Tab(CarTab.SONGS),
        CarMediaId.Favourites,
        CarMediaId.RecentlyAdded,
        CarMediaId.Playlist(7),
        CarMediaId.Album(12),
        CarMediaId.Artist(3),
        CarMediaId.ArtistSongs(3),
        CarMediaId.Bucket(CarTab.SONGS, "B"),
        CarMediaId.Bucket(CarTab.ARTISTS, "#"),
        CarMediaId.Search("AC/DC: Back @ 11"),
        CarMediaId.Song(41, 2, CarMediaId.Album(12)),
        CarMediaId.Song(41, 0, CarMediaId.Tab(CarTab.SONGS)),
        CarMediaId.Song(5, 9, CarMediaId.Search("a/b@c:d")),
        CarMediaId.Song(5, 1, CarMediaId.Favourites),
        CarMediaId.Song(5, 1, CarMediaId.Playlist(7)),
        CarMediaId.Song(5, 1, CarMediaId.ArtistSongs(3)),
    )

    @Test
    fun `every id survives the round trip through its string`() {
        every.forEach { id -> assertThat(CarMediaId.parse(id.value)).isEqualTo(id) }
    }

    @Test
    fun `no id holds a colon, so a queue id parse never mistakes one for a track`() {
        every.forEach { id -> assertThat(id.value).doesNotContain(":") }
    }

    @Test
    fun `ids read as paths with the song context after the at sign`() {
        assertThat(CarMediaId.Tab(CarTab.ALBUMS).value).isEqualTo("tab/albums")
        assertThat(CarMediaId.ArtistSongs(3).value).isEqualTo("artist/3/songs")
        assertThat(CarMediaId.Song(41, 2, CarMediaId.Album(12)).value).isEqualTo("song/41/2@album/12")
    }

    @Test
    fun `strings that name nothing parse to null`() {
        listOf(
            "", "root/x", "tab/folders", "album/x", "album/", "album/1/2", "playlist/-", "artist/1/albums",
            "albums/AB", "playlists/A", "song/1@album/2", "song/x/0@album/2", "song/1/0@root", "song/1/0@tab/albums",
            "song/1/0@song/2/0@album/1", "song/1/-1@album/2", "12:34", "content://media/1",
        ).forEach { assertThat(CarMediaId.parse(it)).isNull() }
    }

    @Test
    fun `songs play from favourites, playlists, albums, an artist's songs, the song list and search results`() {
        val contexts = every.filter { CarMediaId.isSongContext(it) }

        assertThat(contexts).containsExactly(
            CarMediaId.Tab(CarTab.SONGS),
            CarMediaId.Favourites,
            CarMediaId.Playlist(7),
            CarMediaId.Album(12),
            CarMediaId.ArtistSongs(3),
            CarMediaId.Search("AC/DC: Back @ 11"),
        )
    }

    @Test
    fun `albums and artists show as grids, everything else as lists`() {
        assertThat(CarStyle.childrenOf(CarMediaId.Tab(CarTab.ALBUMS))).isEqualTo(CarStyle.GRID)
        assertThat(CarStyle.childrenOf(CarMediaId.Tab(CarTab.ARTISTS))).isEqualTo(CarStyle.GRID)
        assertThat(CarStyle.childrenOf(CarMediaId.Bucket(CarTab.ALBUMS, "A"))).isEqualTo(CarStyle.GRID)
        assertThat(CarStyle.childrenOf(CarMediaId.RecentlyAdded)).isEqualTo(CarStyle.GRID)
        assertThat(CarStyle.childrenOf(CarMediaId.Artist(1))).isEqualTo(CarStyle.GRID)
        assertThat(CarStyle.childrenOf(CarMediaId.Tab(CarTab.SONGS))).isEqualTo(CarStyle.LIST)
        assertThat(CarStyle.childrenOf(CarMediaId.Tab(CarTab.PLAYLISTS))).isEqualTo(CarStyle.LIST)
        assertThat(CarStyle.childrenOf(CarMediaId.Bucket(CarTab.SONGS, "A"))).isEqualTo(CarStyle.LIST)
        assertThat(CarStyle.childrenOf(CarMediaId.Album(1))).isEqualTo(CarStyle.LIST)
    }
}
