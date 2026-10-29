package io.sonora.core.data.library.duplicates

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.domain.library.duplicates.DuplicateCopy
import io.sonora.core.domain.library.duplicates.DuplicateFingerprint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A track row: an untagged MP3 unless told otherwise. */
internal fun trackRow(
    name: String,
    tagTitle: String? = null,
    tagArtist: String? = null,
    codec: String? = null,
    mimeType: String = "audio/mpeg",
    embeddedArtwork: String? = null,
    sizeBytes: Long = 10,
) = TrackEntity(
    id = name.hashCode().toLong(), documentUri = "content://tree/music/document/$name", folderTreeUri = "content://tree/music",
    folderPath = "Music/Rock", fileName = "$name.mp3", mimeType = mimeType, sizeBytes = sizeBytes, lastModifiedMs = 20,
    dateAddedSec = 30, tagTitle = tagTitle, tagArtist = tagArtist, tagAlbum = "Record", tagAlbumArtist = null,
    tagCompilation = false, genre = null, year = 1999, trackNumber = null, discNumber = null, durationMs = 200_000,
    sampleRateHz = 44_100, bitsPerSample = 16, channelCount = 2, embeddedArtworkUri = embeddedArtwork,
    folderImageUri = "content://tree/music/document/cover.jpg", title = tagTitle ?: name, artist = tagArtist ?: "Unknown Artist",
    artistId = 1, album = "Record", albumId = 2, albumArtist = "Band", albumArtistId = 1, isCompilation = false,
    artworkUri = "content://tree/music/document/cover.jpg", codec = codec, bitrateKbps = 320,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomDuplicateStoreTest {

    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.duplicateDao()

    @After
    fun tearDown() = db.close()

    @Test
    fun `library files come with their quality, own artwork and tag completeness`() = runTest {
        db.libraryDao().upsertTracks(
            listOf(
                trackRow("a", tagTitle = "Song", tagArtist = "Band", codec = "FLAC", embeddedArtwork = "art:a"),
                trackRow("b", mimeType = "audio/flac"),
            ),
        )
        val copies = RoomDuplicateLibrary(dao).copies().associateBy { it.fileName }
        assertThat(copies.getValue("a.mp3")).isEqualTo(
            DuplicateCopy(
                uri = "content://tree/music/document/a", title = "Song", artist = "Band", tagTitle = "Song", tagArtist = "Band",
                durationMs = 200_000, sizeBytes = 10, lastModifiedMs = 20, format = "FLAC", bitrateKbps = 320,
                sampleRateHz = 44_100, bitsPerSample = 16, channelCount = 2, folderPath = "Music/Rock", fileName = "a.mp3",
                hasArtwork = true, tagsFilled = 4, dateAddedSec = 30,
            ),
        )
        // No codec read: the MIME type tells; the folder image is not the file's own artwork.
        assertThat(copies.getValue("b.mp3").format).isEqualTo("FLAC")
        assertThat(copies.getValue("b.mp3").hasArtwork).isFalse()
        assertThat(copies.getValue("b.mp3").tagsFilled).isEqualTo(2)
    }

    @Test
    fun `dismissed groups come back as the sets of their files`() = runTest {
        val dismissals = RoomDuplicateDismissals(dao)
        dismissals.dismiss(setOf("a", "b"))
        dismissals.dismiss(setOf("c", "b"))
        dismissals.dismiss(setOf("b", "a"))
        assertThat(dismissals.dismissed()).containsExactly(setOf("a", "b"), setOf("b", "c"))
    }

    @Test
    fun `fingerprints are stored, replaced and forgotten`() = runTest {
        val fingerprints = RoomDuplicateFingerprints(dao)
        val a = DuplicateFingerprint("a", 1, 2, "hash", "key", 1)
        val b = DuplicateFingerprint("b", 1, 2, null, null, 1)
        fingerprints.update(listOf(a, b), gone = emptyList())
        fingerprints.update(listOf(a.copy(lastModifiedMs = 3)), gone = listOf("b"))
        assertThat(fingerprints.all()).containsExactly(a.copy(lastModifiedMs = 3))
    }

    @Test
    fun `the notification is on until turned off, and its count and the first analysis are remembered`() = runTest {
        val settings = RoomDuplicateSettings(dao)
        assertThat(settings.enabled.first()).isTrue()
        assertThat(settings.lastNotifiedCount()).isEqualTo(0)
        assertThat(settings.done()).isFalse()
        settings.setLastNotifiedCount(21)
        settings.setEnabled(false)
        settings.markDone()
        assertThat(settings.enabled.first()).isFalse()
        assertThat(settings.lastNotifiedCount()).isEqualTo(21)
        assertThat(settings.done()).isTrue()
    }
}
