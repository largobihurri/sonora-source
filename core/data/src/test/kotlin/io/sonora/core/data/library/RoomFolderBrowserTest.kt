package io.sonora.core.data.library

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.LibraryFolderEntity
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.FileIdentity
import io.sonora.core.domain.library.ScannedTrack
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
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
class RoomFolderBrowserTest {

    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var browser: RoomFolderBrowser

    /** Two library folders with the same display name (internal storage and an SD card). */
    private val internal = LibraryFolderEntity("content://tree/primary%3AMusic", "Music", addedAtMs = 1)
    private val sdCard = LibraryFolderEntity("content://tree/1234-ABCD%3AMusic", "Music", addedAtMs = 2)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.libraryDao()
        browser = RoomFolderBrowser(dao)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun index(folder: LibraryFolderEntity, vararg files: Pair<String, Int>) {
        dao.insertFolder(folder)
        val scanned = files.map { (path, number) ->
            val name = "${path.substringAfterLast('/')} $number.flac"
            ScannedTrack(
                documentUri = "${folder.treeUri}/document/$path/$name",
                folderPath = path,
                fileName = name,
                tags = TrackTags(title = name, artist = "Band", album = path, trackNumber = number),
            )
        }
        val resolution = AlbumGrouping.resolve(scanned)
        dao.upsertTracks(
            scanned.map {
                TrackEntity.of(it, folder.treeUri, "audio/flac", FileIdentity(it.documentUri, 1, 1), 0, resolution.getValue(it.documentUri))
            },
        )
    }

    private val Iterable<io.sonora.core.domain.model.Track>.names get() = map { it.title }

    @Test
    fun `roots are the library folders with all their tracks counted`() = runTest {
        index(internal, "Music" to 1, "Music/Rock" to 1, "Music/Rock/Live" to 1)
        index(sdCard, "Music/Rock" to 1)

        assertThat(browser.roots().first()).containsExactly(
            FolderEntry(FolderRef(internal.treeUri, "Music"), 3),
            FolderEntry(FolderRef(sdCard.treeUri, "Music"), 1),
        ).inOrder()
    }

    @Test
    fun `contents list one library folder's subfolders and own tracks only`() = runTest {
        index(internal, "Music" to 2, "Music" to 1, "Music/Rock" to 1, "Music/Rock/Live" to 1, "Music/Rock Classics" to 1)
        index(sdCard, "Music/Rock" to 1, "Music/Jazz" to 1)

        val contents = browser.contents(FolderRef(internal.treeUri, "Music")).first()

        assertThat(contents.subfolders).containsExactly(
            FolderEntry(FolderRef(internal.treeUri, "Music/Rock"), 2),
            FolderEntry(FolderRef(internal.treeUri, "Music/Rock Classics"), 1),
        ).inOrder()
        assertThat(contents.tracks.names).containsExactly("Music 1.flac", "Music 2.flac").inOrder()
        assertThat(contents.trackCount).isEqualTo(5)
    }

    @Test
    fun `tracks under a folder include every subfolder in path order and nothing beside it`() = runTest {
        index(
            internal,
            "Music/Rock Classics" to 1, "Music/Rock/Live" to 2, "Music/Rock/Live" to 1, "Music/Rock" to 1,
            "Music/rock" to 1, "Music/Rock_" to 1, "Music/Rock%" to 1,
        )
        index(sdCard, "Music/Rock" to 1)

        val tracks = browser.tracksUnder(FolderRef(internal.treeUri, "Music/Rock"))

        assertThat(tracks.names).containsExactly("Rock 1.flac", "Live 1.flac", "Live 2.flac").inOrder()
    }

    @Test
    fun `a library folder whose tracks were stored under another name shows, counts and plays the same tracks`() = runTest {
        // Indexed while the library folder was named "Old Music"; it is called "Music" now.
        index(internal, "Old Music" to 1, "Old Music/Rock" to 1, "Old Music/Rock/Live" to 1)
        index(sdCard, "Music/Rock" to 1)
        val root = FolderRef(internal.treeUri, "Music")
        val rock = root.child("Rock")

        assertThat(browser.roots().first().first()).isEqualTo(FolderEntry(root, 3))
        val contents = browser.contents(root).first()
        assertThat(contents.subfolders).containsExactly(FolderEntry(rock, 2))
        assertThat(contents.tracks.names).containsExactly("Old Music 1.flac")
        assertThat(contents.trackCount).isEqualTo(3)
        assertThat(browser.contents(rock).first().subfolders).containsExactly(FolderEntry(rock.child("Live"), 1))
        val all = browser.tracksUnder(root)
        assertThat(all.names).containsExactly("Old Music 1.flac", "Rock 1.flac", "Live 1.flac").inOrder()
        assertThat(all.map { it.folderPath }).containsExactly("Music", "Music/Rock", "Music/Rock/Live").inOrder()
        assertThat(browser.tracksUnder(rock).names).containsExactly("Rock 1.flac", "Live 1.flac").inOrder()
    }

    @Test
    fun `contents follow the index as it changes`() = runTest {
        index(internal, "Music/Rock" to 1)
        val rock = FolderRef(internal.treeUri, "Music/Rock")
        assertThat(browser.contents(rock).first().tracks).hasSize(1)

        index(internal, "Music/Rock" to 2, "Music/Rock/Live" to 1)

        val contents = browser.contents(rock).first()
        assertThat(contents.tracks.names).containsExactly("Rock 1.flac", "Rock 2.flac").inOrder()
        assertThat(contents.subfolders.map { it.folder.name }).containsExactly("Live")
    }
}
