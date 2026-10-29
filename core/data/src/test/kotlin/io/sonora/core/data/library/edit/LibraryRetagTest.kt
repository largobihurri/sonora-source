package io.sonora.core.data.library.edit

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.LibraryFolderEntity
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.data.library.tags.ArtworkCache
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.FileIdentity
import io.sonora.core.domain.library.ScannedTrack
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.tags.Picture
import io.sonora.core.domain.tags.SongTags
import io.sonora.core.domain.tags.TagField
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
class LibraryRetagTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var retag: LibraryRetag
    private val folder = LibraryFolderEntity("content://tree/music", "Music", addedAtMs = 1)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        dao = db.libraryDao()
        retag = LibraryRetag(dao, ArtworkCache(context))
    }

    @After
    fun tearDown() = db.close()

    private fun scanned(name: String, album: String, number: Int) = ScannedTrack(
        documentUri = "content://tree/music/document/$name", folderPath = "Music/Record", fileName = name,
        tags = TrackTags(
            title = name, artist = "Band", album = album, trackNumber = number, durationMs = 1_000, sampleRateHz = 44_100,
            bitsPerSample = 16, channelCount = 2,
        ),
    )

    private suspend fun index(vararg tracks: ScannedTrack) {
        dao.insertFolder(folder)
        val resolution = AlbumGrouping.resolve(tracks.toList())
        dao.upsertTracks(
            tracks.map { TrackEntity.of(it, folder.treeUri, "audio/flac", FileIdentity(it.documentUri, 10, 20), 5, resolution.getValue(it.documentUri)) },
        )
    }

    @Test
    fun `the row takes the new tags, identity and picture, and the library regroups`() = runTest {
        val a = scanned("a.flac", "Record", 1)
        val b = scanned("b.flac", "Record", 2)
        index(a, b)
        val track = dao.trackByUri(a.documentUri)!!.toDomain()
        val written = SongTags(
            fields = mapOf(
                TagField.TITLE to "Intro", TagField.ARTIST to "Band", TagField.ALBUM to "Other Record",
                TagField.YEAR to "2024-05-03", TagField.TRACK_NUMBER to "7", TagField.GENRE to "House",
            ),
            artwork = Picture(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2), "image/jpeg"),
        )

        val updated = retag.store(track, written, sizeBytes = 99, lastModifiedMs = 1234)

        val row = dao.trackByUri(a.documentUri)!!
        assertThat(updated).isEqualTo(row.toDomain())
        assertThat(row.title).isEqualTo("Intro")
        assertThat(row.album).isEqualTo("Other Record")
        assertThat(row.year).isEqualTo(2024)
        assertThat(row.trackNumber).isEqualTo(7)
        assertThat(row.genre).isEqualTo("House")
        assertThat(row.sizeBytes).isEqualTo(99)
        assertThat(row.lastModifiedMs).isEqualTo(1234)
        assertThat(row.dateAddedSec).isEqualTo(5)
        assertThat(row.durationMs).isEqualTo(1_000) // stream details are not tags
        assertThat(row.embeddedArtworkUri).isNotNull()
        assertThat(row.artworkUri).isEqualTo(row.embeddedArtworkUri)
        assertThat(row.albumId).isNotEqualTo(dao.trackByUri(b.documentUri)!!.albumId) // moved to its own album
    }

    @Test
    fun `cleared tags fall back like a scan would and an unknown identity is left for the next scan`() = runTest {
        val a = scanned("a.flac", "Record", 1)
        index(a)
        val track = dao.trackByUri(a.documentUri)!!.toDomain()

        val updated = retag.store(track, SongTags(), sizeBytes = null, lastModifiedMs = null)

        val row = dao.trackByUri(a.documentUri)!!
        assertThat(updated.title).isEqualTo("a") // the file name without its extension
        assertThat(row.tagAlbum).isNull()
        assertThat(row.embeddedArtworkUri).isNull()
        assertThat(row.lastModifiedMs).isEqualTo(0) // differs from the file, so the next scan reads it again
    }

    @Test
    fun `a file outside the library only gets a retagged copy`() = runTest {
        val outside = scanned("x.flac", "Record", 1)
        index(outside)
        val track = dao.trackByUri(outside.documentUri)!!.toDomain().copy(uri = "content://elsewhere/x.flac")

        val updated = retag.store(track, SongTags(mapOf(TagField.TITLE to "New", TagField.ALBUM_ARTIST to "VA")), 1, 1)

        assertThat(updated.title).isEqualTo("New")
        assertThat(updated.albumArtist).isEqualTo("VA")
        assertThat(updated.artist).isEqualTo(track.artist) // not tagged: unchanged rather than blank
        assertThat(dao.trackByUri(outside.documentUri)!!.title).isEqualTo("x.flac")
    }

    @Test
    fun `a row removed from the library meanwhile is not brought back`() = runTest {
        val a = scanned("a.flac", "Record", 1)
        index(a)
        val row = dao.trackByUri(a.documentUri)!!
        dao.deleteTracks(listOf(a.documentUri)) // e.g. its folder was removed while the file was being saved

        assertThat(dao.storeRetagged(row.copy(tagTitle = "New"))).isFalse()
        assertThat(dao.trackByUri(a.documentUri)).isNull()
    }
}
