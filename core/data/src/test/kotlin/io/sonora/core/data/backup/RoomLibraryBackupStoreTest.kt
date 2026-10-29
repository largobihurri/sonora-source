package io.sonora.core.data.backup

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.LibraryFolderEntity
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.data.library.marks.RoomLibraryMarks
import io.sonora.core.domain.backup.BackupFavourite
import io.sonora.core.domain.backup.BackupPin
import io.sonora.core.domain.backup.BackupPlays
import io.sonora.core.domain.backup.RestoredMarks
import io.sonora.core.domain.backup.SongIdentity
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.FileIdentity
import io.sonora.core.domain.library.ScannedTrack
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
class RoomLibraryBackupStoreTest {

    private lateinit var db: LibraryDatabase
    private lateinit var store: RoomLibraryBackupStore
    private lateinit var marks: RoomLibraryMarks

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomLibraryBackupStore(db.backupDao())
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = Instant.ofEpochMilli(500)
        }
        marks = RoomLibraryMarks(db.libraryMarksDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `library songs include hidden ones, with their tags and duration`() = runTest {
        val folder = LibraryFolderEntity("content://tree/music", "Music", addedAtMs = 1)
        val track = ScannedTrack(
            documentUri = "content://tree/music/document/a.flac",
            folderPath = "Music",
            fileName = "a.flac",
            tags = TrackTags(title = "Halo", artist = "Beyoncé", album = "Wembley", durationMs = 261_000),
            embeddedArtworkUri = null,
        )
        db.libraryDao().insertFolder(folder)
        val resolved = AlbumGrouping.resolve(listOf(track)).getValue(track.documentUri)
        db.libraryDao().upsertTracks(
            listOf(TrackEntity.of(track, folder.treeUri, "audio/flac", FileIdentity(track.documentUri, 10, 20), 0, resolved)),
        )
        marks.hide(HiddenItem(ItemRef.song(track.documentUri), "Halo", "Beyoncé", 1))

        assertThat(store.librarySongs()).containsExactly(SongIdentity(track.documentUri, "Halo", "Beyoncé", "Wembley", 261_000))
    }

    @Test
    fun `marks read back whole, every kind and list`() = runTest {
        marks.setFavourite(ItemRef.song("s"), true)
        marks.setFavourite(ItemRef.album(4), true)
        marks.setPinned(PinContext.playlist(3), ItemRef.song("s"), true)
        marks.hide(HiddenItem(ItemRef.artist(9), "Artist", "", 7))

        assertThat(store.favourites()).containsExactly(BackupFavourite(ItemRef.song("s"), 500), BackupFavourite(ItemRef.album(4), 500))
        assertThat(store.pins()).containsExactly(BackupPin(PinContext.playlist(3), ItemRef.song("s"), 500))
        assertThat(store.hidden()).containsExactly(HiddenItem(ItemRef.artist(9), "Artist", "", 7))
    }

    @Test
    fun `merging marks adds new ones and keeps every current one as it is`() = runTest {
        marks.setFavourite(ItemRef.song("s"), true)
        marks.hide(HiddenItem(ItemRef.album(1), "Current Name", "", 7))

        store.mergeMarks(
            RestoredMarks(
                favourites = listOf(BackupFavourite(ItemRef.song("s"), 1), BackupFavourite(ItemRef.song("t"), 2)),
                pins = listOf(BackupPin(PinContext.SONGS, ItemRef.song("t"), 3)),
                hidden = listOf(HiddenItem(ItemRef.album(1), "Backed-up Name", "", 4), HiddenItem(ItemRef.album(2), "Other", "", 5)),
            ),
        )

        assertThat(store.favourites()).containsExactly(BackupFavourite(ItemRef.song("t"), 2), BackupFavourite(ItemRef.song("s"), 500))
        assertThat(store.pins()).containsExactly(BackupPin(PinContext.SONGS, ItemRef.song("t"), 3))
        assertThat(store.hidden()).containsExactly(
            HiddenItem(ItemRef.album(2), "Other", "", 5),
            HiddenItem(ItemRef.album(1), "Current Name", "", 7),
        )
    }

    @Test
    fun `merging plays only ever raises a count or a last play`() = runTest {
        db.playCountDao().recordPlay("a", 100)
        db.playCountDao().recordPlay("a", 200)
        db.playCountDao().recordPlay("b", 900)

        store.mergePlays(listOf(BackupPlays("a", 1, 300), BackupPlays("b", 5, 50), BackupPlays("c", 3, 70)))

        assertThat(store.plays()).containsExactly(BackupPlays("a", 2, 300), BackupPlays("b", 5, 900), BackupPlays("c", 3, 70)).inOrder()
    }
}
