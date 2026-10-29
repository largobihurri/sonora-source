package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.Pinned
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Track
import org.junit.Test

class ArtistSectionsTest {

    private fun release(id: Long, tracks: Int, year: Int? = null, title: String = "Album $id") =
        album(id, artist = 1).copy(title = title, trackCount = tracks, year = year)

    private fun song(id: Long, album: Long, title: String = "Song $id", disc: Int? = null, number: Int? = null, year: Int? = null) =
        libraryTrack(id, album = album).copy(title = title, discNumber = disc, trackNumber = number, year = year)

    private fun of(albums: List<Album>, songs: List<Track>, pinned: Set<String> = emptySet()) =
        ArtistSections.of(albums, Pinned(songs, pinned))

    private fun ArtistSongs.ids() = sections.map { section -> section.tracks.map { it.id } }

    @Test
    fun `one-track releases gather into Singles first, in the carousel's order, then one section per album`() {
        val albums = listOf(release(1, tracks = 1), release(2, tracks = 3), release(3, tracks = 1), release(4, tracks = 2))
        val songs = listOf(song(10, 2), song(11, 1), song(12, 4), song(13, 2), song(14, 3), song(15, 4))

        val result = of(albums, songs)

        assertThat(result.sections[0]).isInstanceOf(ArtistSection.Singles::class.java)
        assertThat((result.sections[1] as ArtistSection.AlbumSongs).album.id).isEqualTo(2L)
        assertThat((result.sections[2] as ArtistSection.AlbumSongs).album.id).isEqualTo(4L)
        assertThat(result.ids()).containsExactly(listOf(11L, 14L), listOf(10L, 13L), listOf(12L, 15L)).inOrder()
    }

    @Test
    fun `album songs run in album order, disc then track number, across discs`() {
        val albums = listOf(release(1, tracks = 4))
        val songs = listOf(
            song(10, 1, title = "a", disc = 2, number = 1),
            song(11, 1, title = "b", disc = 1, number = 2),
            song(12, 1, title = "c", disc = 2, number = 2),
            song(13, 1, title = "d", disc = 1, number = 1),
        )

        assertThat(of(albums, songs).ids()).containsExactly(listOf(13L, 11L, 10L, 12L))
    }

    @Test
    fun `songs without a track number follow the numbered ones, by title`() {
        val albums = listOf(release(1, tracks = 4))
        val songs = listOf(
            song(10, 1, title = "zeta"),
            song(11, 1, title = "Alpha"),
            song(12, 1, title = "x", number = 2),
            song(13, 1, title = "y", number = 1),
        )

        assertThat(of(albums, songs).ids()).containsExactly(listOf(13L, 12L, 11L, 10L))
    }

    @Test
    fun `a song without a disc number counts as disc 0, like the album page`() {
        val albums = listOf(release(1, tracks = 2))
        val songs = listOf(song(10, 1, disc = 1, number = 1), song(11, 1, number = 5))

        assertThat(of(albums, songs).ids()).containsExactly(listOf(11L, 10L))
    }

    @Test
    fun `a single row carries its release's year, else the song's own`() {
        val albums = listOf(release(1, tracks = 1, year = 2019), release(2, tracks = 1))
        val songs = listOf(song(10, 1, year = 2018), song(11, 2, year = 2021))

        val singles = of(albums, songs).sections.single() as ArtistSection.Singles

        assertThat(singles.entries.map { it.year }).containsExactly(2019, 2021).inOrder()
    }

    @Test
    fun `a longer release where the artist sings once (a compilation) keeps its own section`() {
        val albums = listOf(release(1, tracks = 20))

        val section = of(albums, listOf(song(10, 1))).sections.single()

        assertThat((section as ArtistSection.AlbumSongs).album.id).isEqualTo(1L)
    }

    @Test
    fun `releases without any of the artist's songs give no section`() {
        val albums = listOf(release(1, tracks = 1), release(2, tracks = 5), release(3, tracks = 2))

        val result = of(albums, listOf(song(10, 3), song(11, 3)))

        assertThat(result.sections.map { it::class }).containsExactly(ArtistSection.AlbumSongs::class)
    }

    @Test
    fun `a lone song with an unlisted or blank album joins the Singles, at their end`() {
        val albums = listOf(release(1, tracks = 1), release(2, tracks = 3, title = " "))

        val result = of(albums, listOf(song(10, 2), song(11, 1)))

        assertThat(result.ids()).containsExactly(listOf(11L, 10L))
        assertThat(result.sections.single()).isInstanceOf(ArtistSection.Singles::class.java)
    }

    @Test
    fun `several songs with unlisted or blank albums close the page in one Other songs section, in the incoming order`() {
        val albums = listOf(release(1, tracks = 2), release(2, tracks = 2, title = ""))
        val songs = listOf(song(10, 9), song(11, 1), song(12, 2), song(13, 1))

        val result = of(albums, songs)

        assertThat(result.sections.last()).isInstanceOf(ArtistSection.OtherSongs::class.java)
        assertThat(result.ids()).containsExactly(listOf(11L, 13L), listOf(10L, 12L)).inOrder()
    }

    @Test
    fun `pinned songs sit first within their own section, in pin order`() {
        val albums = listOf(release(1, tracks = 1), release(2, tracks = 1), release(3, tracks = 3))
        val a = song(10, 1)
        val b = song(11, 2)
        val c = song(12, 3, number = 1)
        val d = song(13, 3, number = 2)
        val e = song(14, 3, number = 3)
        // The repository lists the pinned songs first, in pin order.
        val songs = listOf(e, b, d, a, c)

        val result = of(albums, songs, pinned = setOf(e.uri, b.uri, d.uri))

        assertThat(result.ids()).containsExactly(listOf(11L, 10L), listOf(14L, 13L, 12L)).inOrder()
    }

    @Test
    fun `the queue is the sections top to bottom and a row maps to its place in it`() {
        val albums = listOf(release(1, tracks = 1), release(2, tracks = 2), release(3, tracks = 1))
        val songs = listOf(song(10, 2, number = 2), song(11, 1), song(12, 2, number = 1), song(13, 3))

        val result = of(albums, songs)

        assertThat(result.queue.map { it.id }).containsExactly(11L, 13L, 12L, 10L).inOrder()
        assertThat(result.queueIndex(section = 0, row = 1)).isEqualTo(1)
        assertThat(result.queueIndex(section = 1, row = 0)).isEqualTo(2)
        assertThat(result.queueIndex(section = 1, row = 1)).isEqualTo(3)
    }

    @Test
    fun `no songs give no sections`() {
        val result = of(listOf(release(1, tracks = 3)), emptyList())

        assertThat(result.sections).isEmpty()
        assertThat(result.queue).isEmpty()
    }
}
