package io.sonora.core.domain.car

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.FakeLibraryMarks
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.ResolvedPlaylist
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class CarLibraryTreeTest {

    private val abbey = listOf(
        song(1, "Come Together", album = 10, artist = 1, artistName = "The Beatles", albumTitle = "Abbey Road", track = 1),
        song(2, "Something", album = 10, artist = 1, artistName = "The Beatles", albumTitle = "Abbey Road", track = 2),
        song(3, "Octopus's Garden", album = 10, artist = 1, artistName = "The Beatles", albumTitle = "Abbey Road", track = 5),
    )
    private val blue = listOf(
        song(4, "All I Want", album = 20, artist = 2, artistName = "Joni Mitchell", albumTitle = "Blue", track = 1, added = 50),
        song(5, "Ébano", album = 20, artist = 2, artistName = "Joni Mitchell", albumTitle = "Blue", track = 2, added = 50),
    )
    private val library = FakeCarLibrary(abbey + blue)
    private val marks = FakeLibraryMarks()
    private val playlists = MutableStateFlow(
        listOf(
            playlist(1, "Drive", blue[0], abbey[1], null, blue[0]),
            playlist(2, "Secret", abbey[0]),
        ),
    )
    private val tree = CarLibraryTree(library, marks, { playlists }, bucketThreshold = 500)

    private fun ids(nodes: List<CarNode>?) = nodes!!.map { it.id }
    private fun titles(nodes: List<CarNode>?) = nodes!!.map { (it.title as CarTitle.Text).text }

    @Test
    fun `the root holds the four tabs, playlists first`() = runBlocking<Unit> {
        val root = tree.children(CarMediaId.Root)!!

        assertThat(ids(root)).containsExactly(
            CarMediaId.Tab(CarTab.PLAYLISTS),
            CarMediaId.Tab(CarTab.ALBUMS),
            CarMediaId.Tab(CarTab.ARTISTS),
            CarMediaId.Tab(CarTab.SONGS),
        ).inOrder()
        assertThat(root.map { it.title }).containsExactly(
            CarTitle.Label(CarLabel.PLAYLISTS),
            CarTitle.Label(CarLabel.ALBUMS),
            CarTitle.Label(CarLabel.ARTISTS),
            CarTitle.Label(CarLabel.SONGS),
        ).inOrder()
    }

    @Test
    fun `playlists start with favourites and recently added, then the playlists not removed from Sonora`() = runBlocking<Unit> {
        marks.hide(HiddenItem(ItemRef.playlist(2), "Secret", "", 0))

        val nodes = tree.children(CarMediaId.Tab(CarTab.PLAYLISTS))!!

        assertThat(ids(nodes)).containsExactly(CarMediaId.Favourites, CarMediaId.RecentlyAdded, CarMediaId.Playlist(1))
            .inOrder()
        assertThat(nodes.last().title).isEqualTo(CarTitle.Text("Drive"))
        assertThat(nodes.last().artworkUri).isEqualTo(blue[0].artworkUri)
        assertThat(tree.children(CarMediaId.Playlist(2))).isNull()
    }

    @Test
    fun `a playlist lists its available songs, each playing the playlist from its own entry`() = runBlocking<Unit> {
        val nodes = tree.children(CarMediaId.Playlist(1))!!

        assertThat(ids(nodes)).containsExactly(
            CarMediaId.Song(4, 0, CarMediaId.Playlist(1)),
            CarMediaId.Song(2, 1, CarMediaId.Playlist(1)),
            CarMediaId.Song(4, 2, CarMediaId.Playlist(1)),
        ).inOrder()
        assertThat(nodes.first().track).isEqualTo(blue[0])
        assertThat(nodes.first().subtitle).isEqualTo("Joni Mitchell")
    }

    @Test
    fun `albums show with their artist and artwork, and list their songs in album order`() = runBlocking<Unit> {
        val albums = tree.children(CarMediaId.Tab(CarTab.ALBUMS))!!

        assertThat(ids(albums)).containsExactly(CarMediaId.Album(10), CarMediaId.Album(20)).inOrder()
        assertThat(albums.first().subtitle).isEqualTo("The Beatles")
        assertThat(albums.first().artworkUri).isEqualTo("file:///cache/artwork/10.jpg")
        assertThat(titles(tree.children(CarMediaId.Album(10)))).containsExactly(
            "Come Together", "Something", "Octopus's Garden",
        ).inOrder()
    }

    @Test
    fun `an artist opens on all their songs, then their albums`() = runBlocking<Unit> {
        val artists = tree.children(CarMediaId.Tab(CarTab.ARTISTS))!!
        assertThat(ids(artists)).containsExactly(CarMediaId.Artist(2), CarMediaId.Artist(1)).inOrder()

        val artist = tree.children(CarMediaId.Artist(1))!!

        assertThat(ids(artist)).containsExactly(CarMediaId.ArtistSongs(1), CarMediaId.Album(10)).inOrder()
        assertThat(artist.first().title).isEqualTo(CarTitle.Label(CarLabel.ALL_SONGS))
        assertThat(ids(tree.children(CarMediaId.ArtistSongs(2)))).containsExactly(
            CarMediaId.Song(4, 0, CarMediaId.ArtistSongs(2)),
            CarMediaId.Song(5, 1, CarMediaId.ArtistSongs(2)),
        )
    }

    @Test
    fun `a small library lists every song in the library's title order`() = runBlocking<Unit> {
        val songs = tree.children(CarMediaId.Tab(CarTab.SONGS))!!

        assertThat(titles(songs)).containsExactly(
            "All I Want", "Come Together", "Octopus's Garden", "Something", "Ébano",
        ).inOrder()
        assertThat(songs[4].id).isEqualTo(CarMediaId.Song(5, 4, CarMediaId.Tab(CarTab.SONGS)))
    }

    @Test
    fun `a large library splits songs, albums and artists into letters, accents folded and digits last`() = runBlocking<Unit> {
        library.tracks.value = abbey + blue + song(6, "99 Luftballons", album = 30, artist = 3, albumTitle = "99")
        val small = CarLibraryTree(library, marks, { playlists }, bucketThreshold = 2)

        val buckets = small.children(CarMediaId.Tab(CarTab.SONGS))!!

        assertThat(ids(buckets)).containsExactly(
            CarMediaId.Bucket(CarTab.SONGS, "A"),
            CarMediaId.Bucket(CarTab.SONGS, "C"),
            CarMediaId.Bucket(CarTab.SONGS, "E"),
            CarMediaId.Bucket(CarTab.SONGS, "O"),
            CarMediaId.Bucket(CarTab.SONGS, "S"),
            CarMediaId.Bucket(CarTab.SONGS, "#"),
        ).inOrder()
        assertThat(buckets.first().title).isEqualTo(CarTitle.Text("A"))
        // A song in a letter still plays the whole song list from itself.
        assertThat(ids(small.children(CarMediaId.Bucket(CarTab.SONGS, "E"))))
            .containsExactly(CarMediaId.Song(5, 5, CarMediaId.Tab(CarTab.SONGS)))
        assertThat(ids(small.children(CarMediaId.Tab(CarTab.ARTISTS)))).contains(CarMediaId.Bucket(CarTab.ARTISTS, "T"))
        assertThat(ids(small.children(CarMediaId.Bucket(CarTab.ALBUMS, "#")))).containsExactly(CarMediaId.Album(30))
        assertThat(small.children(CarMediaId.Bucket(CarTab.ALBUMS, "Q"))).isEmpty()
    }

    @Test
    fun `favourites list the favourite songs, albums and playlists in groups`() = runBlocking<Unit> {
        marks.setFavourite(ItemRef.song(abbey[1]), true)
        marks.setFavourite(ItemRef.song(blue[1]), true)
        marks.setFavourite(ItemRef.album(20), true)
        marks.setFavourite(ItemRef.playlist(1), true)

        val nodes = tree.children(CarMediaId.Favourites)!!

        assertThat(ids(nodes)).containsExactly(
            CarMediaId.Song(5, 0, CarMediaId.Favourites),
            CarMediaId.Song(2, 1, CarMediaId.Favourites),
            CarMediaId.Album(20),
            CarMediaId.Playlist(1),
        ).inOrder()
        assertThat(nodes.map { it.group }).containsExactly(CarGroup.SONGS, CarGroup.SONGS, CarGroup.ALBUMS, CarGroup.PLAYLISTS)
            .inOrder()
    }

    @Test
    fun `recently added lists the newest albums first`() = runBlocking<Unit> {
        assertThat(ids(tree.children(CarMediaId.RecentlyAdded))).containsExactly(CarMediaId.Album(20), CarMediaId.Album(10))
            .inOrder()
    }

    @Test
    fun `a song plays its whole context from itself`() = runBlocking<Unit> {
        val queue = tree.queue(CarMediaId.Song(2, 1, CarMediaId.Album(10)))!!

        assertThat(queue.tracks).isEqualTo(abbey)
        assertThat(queue.startIndex).isEqualTo(1)
    }

    @Test
    fun `a repeated playlist song starts at the entry that was tapped`() = runBlocking<Unit> {
        val queue = tree.queue(CarMediaId.Song(4, 2, CarMediaId.Playlist(1)))!!

        assertThat(queue.tracks.map(Track::id)).containsExactly(4L, 2L, 4L).inOrder()
        assertThat(queue.startIndex).isEqualTo(2)
    }

    @Test
    fun `a song whose position moved since browsing starts at its first entry, a song gone plays nothing`() = runBlocking<Unit> {
        assertThat(tree.queue(CarMediaId.Song(3, 0, CarMediaId.Album(10)))!!.startIndex).isEqualTo(2)
        assertThat(tree.queue(CarMediaId.Song(99, 0, CarMediaId.Album(10)))).isNull()
        assertThat(tree.queue(CarMediaId.Song(1, 0, CarMediaId.Playlist(42)))).isNull()
    }

    @Test
    fun `ids that name nothing any more have no children and no item`() = runBlocking<Unit> {
        assertThat(tree.children(CarMediaId.Album(999))).isNull()
        assertThat(tree.children(CarMediaId.Artist(999))).isNull()
        assertThat(tree.children(CarMediaId.Playlist(999))).isNull()
        assertThat(tree.children(CarMediaId.Song(1, 0, CarMediaId.Album(10)))).isNull()
        assertThat(tree.item(CarMediaId.Album(999))).isNull()
        assertThat(tree.item(CarMediaId.Song(99, 0, CarMediaId.Album(10)))).isNull()
    }

    @Test
    fun `items are found by id with the same details they browse with`() = runBlocking<Unit> {
        assertThat(tree.item(CarMediaId.Album(20))).isEqualTo(tree.children(CarMediaId.Tab(CarTab.ALBUMS))!![1])
        assertThat(tree.item(CarMediaId.Tab(CarTab.SONGS))?.title).isEqualTo(CarTitle.Label(CarLabel.SONGS))
        val song = tree.item(CarMediaId.Song(2, 1, CarMediaId.Album(10)))!!
        assertThat(song.track).isEqualTo(abbey[1])
        assertThat(song.title).isEqualTo(CarTitle.Text("Something"))
    }

    @Test
    fun `an empty library still shows the tabs, with nothing in them`() = runBlocking<Unit> {
        library.tracks.value = emptyList()
        playlists.value = emptyList<ResolvedPlaylist>()

        assertThat(tree.children(CarMediaId.Root)).hasSize(4)
        assertThat(tree.children(CarMediaId.Tab(CarTab.ALBUMS))).isEmpty()
        assertThat(tree.children(CarMediaId.Tab(CarTab.SONGS))).isEmpty()
        assertThat(ids(tree.children(CarMediaId.Tab(CarTab.PLAYLISTS))))
            .containsExactly(CarMediaId.Favourites, CarMediaId.RecentlyAdded)
        assertThat(tree.children(CarMediaId.Favourites)).isEmpty()
    }

    @Test
    fun `changes fire when songs, playlists, favourites or removed items change, not when first read`() = runBlocking<Unit> {
        val changes = async { tree.changes().take(3).toList() }
        delay(100)
        assertThat(changes.isCompleted).isFalse()

        library.tracks.value = abbey
        delay(50)
        playlists.value = playlists.value.take(1)
        delay(50)
        marks.setFavourite(ItemRef.song(abbey[0]), true)

        assertThat(withTimeout(1_000) { changes.await() }).hasSize(3)
    }

    @Test
    fun `the library is ready, empty, or out of reach when no song can be read`() = runBlocking<Unit> {
        assertThat(tree.status { it.id != 1L }).isEqualTo(CarLibraryStatus.READY)
        assertThat(tree.status { false }).isEqualTo(CarLibraryStatus.NO_ACCESS)
        library.tracks.value = emptyList()
        assertThat(tree.status { true }).isEqualTo(CarLibraryStatus.EMPTY)
    }

    @Test
    fun `a queue drops songs that cannot be read, and plays nothing when the tapped one cannot`() {
        val queue = CarQueue(abbey, startIndex = 2)

        assertThat(queue.readable { it.id != 1L }).isEqualTo(CarQueue(abbey.drop(1), startIndex = 1))
        assertThat(queue.readable { it.id != 3L }).isNull()
        assertThat(queue.readable { true }).isEqualTo(queue)
    }
}
