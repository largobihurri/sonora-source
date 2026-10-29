package io.sonora.core.data.library.marks

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.RoomFolderBrowser
import io.sonora.core.data.library.RoomLibraryRepository
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.LibraryFolderEntity
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.data.library.plays.RoomPlayHistory
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.FileIdentity
import io.sonora.core.domain.library.ScannedTrack
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.plays.TrackPlays
import io.sonora.core.domain.model.FolderRef
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomLibraryMarksTest {

    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var marks: RoomLibraryMarks
    private lateinit var library: RoomLibraryRepository
    private var now = 1_000L

    private val folder = LibraryFolderEntity("content://tree/music", "Music", addedAtMs = 1)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.libraryDao()
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = Instant.ofEpochMilli(now++)
        }
        marks = RoomLibraryMarks(db.libraryMarksDao(), clock)
        library = RoomLibraryRepository(dao)
    }

    @After
    fun tearDown() = db.close()

    private fun scanned(name: String, path: String, artist: String, album: String) = ScannedTrack(
        documentUri = "content://tree/music/document/$path/$name",
        folderPath = path,
        fileName = name,
        tags = TrackTags(title = name.substringBeforeLast('.'), artist = artist, album = album),
        embeddedArtworkUri = null,
    )

    private val rock1 = scanned("r1.flac", "Music/Rock", "Band", "Record")
    private val rock2 = scanned("r2.flac", "Music/Rock", "Band", "Record")
    private val pop = scanned("p.flac", "Music/Pop", "Singer", "Single")

    private suspend fun index() {
        dao.insertFolder(folder)
        val all = listOf(rock1, rock2, pop)
        val resolution = AlbumGrouping.resolve(all)
        dao.upsertTracks(
            all.mapIndexed { i, t ->
                TrackEntity.of(t, folder.treeUri, "audio/flac", FileIdentity(t.documentUri, 10, 20), i.toLong(), resolution.getValue(t.documentUri))
            },
        )
    }

    @Test
    fun `favourites persist per kind, newest first, and can be removed`() = runTest {
        marks.setFavourite(ItemRef.song("a"), true)
        marks.setFavourite(ItemRef.album(7), true)
        marks.setFavourite(ItemRef.song("b"), true)
        marks.setFavourite(ItemRef.song("a"), true) // already one: keeps its place
        assertThat(marks.favourites(MarkKind.SONG).first()).containsExactly("b", "a").inOrder()
        assertThat(marks.favourites(MarkKind.ALBUM).first()).containsExactly("7")

        marks.setFavourite(ItemRef.song("b"), false)
        assertThat(marks.favourites(MarkKind.SONG).first()).containsExactly("a")
    }

    @Test
    fun `pins belong to their list and keep pin order`() = runTest {
        marks.setPinned(PinContext.SONGS, ItemRef.song("x"), true)
        marks.setPinned(PinContext.SONGS, ItemRef.song("y"), true)
        marks.setPinned(PinContext.playlist(3), ItemRef.song("y"), true)
        assertThat(marks.pins(PinContext.SONGS).first()).containsExactly("x", "y").inOrder()
        assertThat(marks.pins(PinContext.playlist(3)).first()).containsExactly("y")

        marks.setPinned(PinContext.SONGS, ItemRef.song("x"), false)
        assertThat(marks.pins(PinContext.SONGS).first()).containsExactly("y")
        assertThat(marks.pins(PinContext.ALBUMS).first()).isEmpty()
    }

    @Test
    fun `hidden items are listed newest first and restore`() = runTest {
        marks.hide(HiddenItem(ItemRef.song("a"), "A", "Band", 5))
        marks.hide(HiddenItem(ItemRef.playlist(2), "Mix", "", 9))
        assertThat(marks.hidden().first().map { it.title }).containsExactly("Mix", "A").inOrder()

        marks.restore(ItemRef.song("a"))
        assertThat(marks.hidden().first().map { it.ref }).containsExactly(ItemRef.playlist(2))
    }

    @Test
    fun `forgetting deleted songs drops their marks only`() = runTest {
        marks.setFavourite(ItemRef.song("a"), true)
        marks.setFavourite(ItemRef.song("b"), true)
        marks.setFavourite(ItemRef.album(1), true)
        marks.setPinned(PinContext.SONGS, ItemRef.song("a"), true)
        marks.hide(HiddenItem(ItemRef.song("a"), "A", "", 1))

        marks.forgetSongs(listOf("a"))

        assertThat(marks.favourites(MarkKind.SONG).first()).containsExactly("b")
        assertThat(marks.favourites(MarkKind.ALBUM).first()).containsExactly("1")
        assertThat(marks.pins(PinContext.SONGS).first()).isEmpty()
        assertThat(marks.hidden().first()).isEmpty()
    }

    @Test
    fun `a moved song gives the kept copy its favourite and pins and loses its hidden mark`() = runTest {
        marks.setFavourite(ItemRef.song("gone"), true)
        marks.setPinned(PinContext.SONGS, ItemRef.song("gone"), true)
        marks.setPinned(PinContext.album(1), ItemRef.song("gone"), true)
        marks.hide(HiddenItem(ItemRef.song("gone"), "Gone", "", 1))
        marks.setPinned(PinContext.SONGS, ItemRef.song("other"), true)

        marks.moveSongs(mapOf("gone" to "kept"))

        assertThat(marks.favourites(MarkKind.SONG).first()).containsExactly("kept")
        // The kept copy takes the moved pin's place in each list.
        assertThat(marks.pins(PinContext.SONGS).first()).containsExactly("kept", "other").inOrder()
        assertThat(marks.pins(PinContext.album(1)).first()).containsExactly("kept")
        assertThat(marks.hidden().first()).isEmpty()
    }

    @Test
    fun `a mark both copies had stays once, with the kept copy's own place, and the kept copy's hidden mark stays`() = runTest {
        marks.setFavourite(ItemRef.song("kept"), true)
        marks.setFavourite(ItemRef.song("other"), true)
        marks.setFavourite(ItemRef.song("gone"), true)
        marks.setPinned(PinContext.SONGS, ItemRef.song("kept"), true)
        marks.setPinned(PinContext.SONGS, ItemRef.song("gone"), true)
        marks.hide(HiddenItem(ItemRef.song("kept"), "Kept", "", 1))

        marks.moveSongs(mapOf("gone" to "kept"))

        assertThat(marks.favourites(MarkKind.SONG).first()).containsExactly("other", "kept").inOrder()
        assertThat(marks.pins(PinContext.SONGS).first()).containsExactly("kept")
        assertThat(marks.hidden().first().map { it.ref }).containsExactly(ItemRef.song("kept"))
    }

    @Test
    fun `moved songs add their plays to the kept copy's, which keeps the latest time played`() = runTest {
        val history = RoomPlayHistory(db.playCountDao())
        history.recordPlay("gone", playedAtMs = 10)
        history.recordPlay("gone", playedAtMs = 50)
        history.recordPlay("also gone", playedAtMs = 5)
        history.recordPlay("kept", playedAtMs = 30)
        history.recordPlay("lone", playedAtMs = 7)
        history.recordPlay("untouched", playedAtMs = 1)

        marks.moveSongs(mapOf("gone" to "kept", "also gone" to "kept", "lone" to "unplayed"))

        assertThat(history.plays().first()).containsExactly(
            "kept", TrackPlays(count = 4, lastPlayedMs = 50),
            "unplayed", TrackPlays(count = 1, lastPlayedMs = 7),
            "untouched", TrackPlays(count = 1, lastPlayedMs = 1),
        )
    }

    @Test
    fun `a hidden song leaves songs, albums, artists, folders and search but stays indexed`() = runTest {
        index()
        marks.hide(HiddenItem(ItemRef.song(pop.documentUri), "p", "Singer", 1))

        assertThat(library.tracks().first().map { it.title }).containsExactly("r1", "r2")
        assertThat(library.albums().first().map { it.title }).containsExactly("Record")
        assertThat(library.artists().first().map { it.name }).containsExactly("Band")
        assertThat(library.search("p").first()).isEmpty()
        assertThat(library.recentlyAddedAlbums().first().map { it.title }).containsExactly("Record")
        val browser = RoomFolderBrowser(dao)
        assertThat(browser.roots().first().single().trackCount).isEqualTo(2)
        assertThat(browser.tracksUnder(FolderRef(folder.treeUri, "Music")).map { it.title }).containsExactly("r1", "r2")
        assertThat(dao.allTracks()).hasSize(3)

        marks.restore(ItemRef.song(pop.documentUri))
        assertThat(library.tracks().first()).hasSize(3)
    }

    @Test
    fun `a hidden album hides its songs, and its artist when nothing else is left`() = runTest {
        index()
        val record = library.albums().first().first { it.title == "Record" }
        marks.hide(HiddenItem(ItemRef.album(record), "Record", "Band", 1))

        assertThat(library.albums().first().map { it.title }).containsExactly("Single")
        assertThat(library.tracksForAlbum(record.id).first()).isEmpty()
        assertThat(library.artists().first().map { it.name }).containsExactly("Singer")
        assertThat(library.tracks().first().map { it.title }).containsExactly("p")
    }

    @Test
    fun `a hidden artist hides their songs and albums`() = runTest {
        index()
        val band = library.artists().first().first { it.name == "Band" }
        marks.hide(HiddenItem(ItemRef.artist(band.id), "Band", "", 1))

        assertThat(library.artists().first().map { it.name }).containsExactly("Singer")
        assertThat(library.albumsForArtist(band.id).first()).isEmpty()
        assertThat(library.tracksForArtist(band.id).first()).isEmpty()
        assertThat(library.tracks().first().map { it.title }).containsExactly("p")
    }
}
