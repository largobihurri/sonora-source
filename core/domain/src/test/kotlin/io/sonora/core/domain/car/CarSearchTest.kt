package io.sonora.core.domain.car

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.FakeLibraryMarks
import io.sonora.core.domain.model.LibraryQuery
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CarSearchTest {

    private val tracks = listOf(
        song(1, "Blue", album = 10, artist = 1, artistName = "Joni Mitchell", albumTitle = "Blue", track = 10),
        song(2, "A Case of You", album = 10, artist = 1, artistName = "Joni Mitchell", albumTitle = "Blue", track = 9),
        song(3, "Blue in Green", album = 20, artist = 2, artistName = "Miles Davis", albumTitle = "Kind of Blue", track = 3),
        song(4, "So What", album = 20, artist = 2, artistName = "Miles Davis", albumTitle = "Kind of Blue", track = 1),
        song(5, "Canción Azul", album = 30, artist = 3, artistName = "Café Tacvba", albumTitle = "Re", track = 1),
        song(6, "Something", album = 40, artist = 4, artistName = "The Beatles", albumTitle = "Abbey Road", track = 2),
        song(7, "Come Together", album = 40, artist = 4, artistName = "The Beatles", albumTitle = "Abbey Road", track = 1),
    )
    private val albums = LibraryQuery.albums(tracks)
    private val artists = LibraryQuery.artists(tracks)
    private val tree = CarLibraryTree(FakeCarLibrary(tracks), FakeLibraryMarks(), { flowOf(emptyList()) })

    private fun ids(found: List<Track>) = found.map { it.id }

    @Test
    fun `songs rank exact titles, then title starts, then word starts, then title contains, then artist or album`() {
        val found = CarSearch.rank("blue", tracks, albums, artists)

        // "Blue" exact, "Blue in Green" starts, "Kind of Blue" songs match by album only.
        assertThat(ids(found.songs)).containsExactly(1L, 3L, 2L, 4L).inOrder()
    }

    @Test
    fun `matching ignores case and accents`() {
        assertThat(ids(CarSearch.rank("CANCION azul", tracks, albums, artists).songs)).containsExactly(5L)
        assertThat(CarSearch.rank("cafe", tracks, albums, artists).artists.map { it.name }).containsExactly("Café Tacvba")
    }

    @Test
    fun `albums and artists are found by their names`() {
        val found = CarSearch.rank("blue", tracks, albums, artists)

        assertThat(found.albums.map { it.title }).containsExactly("Blue", "Kind of Blue").inOrder()
        assertThat(found.artists).isEmpty()
        assertThat(CarSearch.rank("beatles", tracks, albums, artists).artists.map { it.name }).containsExactly("The Beatles")
    }

    @Test
    fun `a blank query finds nothing`() {
        assertThat(CarSearch.rank("  ", tracks, albums, artists).isEmpty).isTrue()
    }

    @Test
    fun `search results list songs, then albums, then artists, and their songs play the results`() = runBlocking<Unit> {
        val nodes = tree.children(CarMediaId.Search("joni"))!!

        // Both songs match by their artist only, so they keep title order.
        assertThat(nodes.map { it.id }).containsExactly(
            CarMediaId.Song(2, 0, CarMediaId.Search("joni")),
            CarMediaId.Song(1, 1, CarMediaId.Search("joni")),
            CarMediaId.Artist(1),
        ).inOrder()
        assertThat(nodes.map { it.group }).containsExactly(CarGroup.SONGS, CarGroup.SONGS, CarGroup.ARTISTS).inOrder()
        val queue = tree.queue(CarMediaId.Song(1, 1, CarMediaId.Search("joni")))!!
        assertThat(ids(queue.tracks)).containsExactly(2L, 1L).inOrder()
        assertThat(queue.startIndex).isEqualTo(1)
    }

    @Test
    fun `voice plays an artist's songs when asked for the artist`() = runBlocking<Unit> {
        val plan = tree.voice(CarVoiceRequest("the beatles", focus = CarVoiceFocus.ARTIST, artist = "The Beatles"))

        assertThat(plan).isEqualTo(CarVoicePlan.Play(CarQueue(listOf(tracks[6], tracks[5]), 0)))
    }

    @Test
    fun `voice plays an album in album order when asked for the album`() = runBlocking<Unit> {
        val plan = tree.voice(CarVoiceRequest("kind of blue", focus = CarVoiceFocus.ALBUM, album = "Kind of Blue"))

        assertThat(plan).isEqualTo(CarVoicePlan.Play(CarQueue(listOf(tracks[3], tracks[2]), 0)))
    }

    @Test
    fun `voice plays the best song first, then the other matches, when asked for a song`() = runBlocking<Unit> {
        val plan = tree.voice(CarVoiceRequest("blue in green", focus = CarVoiceFocus.SONG, title = "Blue in Green"))

        assertThat((plan as CarVoicePlan.Play).queue.tracks.first()).isEqualTo(tracks[2])
        assertThat(plan.queue.startIndex).isEqualTo(0)
    }

    @Test
    fun `an open voice query plays the closest of an artist, an album or a song`() = runBlocking<Unit> {
        assertThat(tree.voice(CarVoiceRequest("abbey road"))).isEqualTo(
            CarVoicePlan.Play(CarQueue(listOf(tracks[6], tracks[5]), 0)),
        )
        assertThat(tree.voice(CarVoiceRequest("miles davis"))).isEqualTo(
            CarVoicePlan.Play(CarQueue(listOf(tracks[2], tracks[3]), 0)),
        )
        assertThat((tree.voice(CarVoiceRequest("something")) as CarVoicePlan.Play).queue.tracks.first()).isEqualTo(tracks[5])
    }

    @Test
    fun `an empty voice query plays anything, and an unknown one nothing`() = runBlocking<Unit> {
        assertThat(tree.voice(CarVoiceRequest(""))).isEqualTo(CarVoicePlan.Anything(LibraryQuery.sort(tracks, TrackSort.TITLE)))
        assertThat(tree.voice(CarVoiceRequest("zzz top"))).isEqualTo(CarVoicePlan.NoMatch)
    }
}
