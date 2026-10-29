package io.sonora.core.data.export

import android.net.Uri
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.domain.library.AlbumGrouping
import io.sonora.core.domain.library.FileIdentity
import io.sonora.core.domain.library.ScannedTrack
import io.sonora.core.domain.library.TrackTags
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The picture the library shows for an exported track: its cached embedded picture, else its folder's image. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryExportArtworkTest {

    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var artwork: LibraryExportArtwork
    private val dir: File = Files.createTempDirectory("library-art").toFile()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.libraryDao()
        artwork = LibraryExportArtwork(RuntimeEnvironment.getApplication(), dao)
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun picture(name: String, bytes: ByteArray): String =
        Uri.fromFile(File(dir, name).apply { writeBytes(bytes) }).toString()

    private suspend fun index(uri: String, embedded: String?, folderImage: String?) {
        val song = ScannedTrack(
            documentUri = uri,
            folderPath = "Music/Album",
            fileName = uri.substringAfterLast('/'),
            tags = TrackTags(title = "Song", artist = "Band", album = "Album"),
            embeddedArtworkUri = embedded,
            folderImageUri = folderImage,
        )
        val resolution = AlbumGrouping.resolve(listOf(song))
        dao.upsertTracks(
            listOf(TrackEntity.of(song, "content://tree/music", "audio/flac", FileIdentity(uri, 10, 20), 0, resolution.getValue(uri))),
        )
    }

    @Test
    fun `the embedded picture comes first, the folder image stands in when it cannot be read`() = runTest {
        val embedded = picture("embedded.jpg", byteArrayOf(1, 2, 3))
        val folder = picture("cover.jpg", byteArrayOf(4, 5, 6))
        index("content://tree/music/document/a.flac", embedded, folder)
        index("content://tree/music/document/b.flac", "file:///gone/embedded.jpg", folder)
        index("content://tree/music/document/c.flac", null, folder)

        assertThat(artwork.libraryPicture("content://tree/music/document/a.flac")).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(artwork.libraryPicture("content://tree/music/document/b.flac")).isEqualTo(byteArrayOf(4, 5, 6))
        assertThat(artwork.libraryPicture("content://tree/music/document/c.flac")).isEqualTo(byteArrayOf(4, 5, 6))
    }

    @Test
    fun `a track the library does not hold, or holds without a usable picture, has none`() = runTest {
        index("content://tree/music/document/a.flac", null, null)
        index("content://tree/music/document/b.flac", picture("empty.jpg", ByteArray(0)), "file:///gone/cover.jpg")

        assertThat(artwork.libraryPicture("content://picked/elsewhere.flac")).isNull()
        assertThat(artwork.libraryPicture("content://tree/music/document/a.flac")).isNull()
        assertThat(artwork.libraryPicture("content://tree/music/document/b.flac")).isNull()
    }

    @Test
    fun `a picture larger than the bound is skipped for the next one`() = runTest {
        val huge = File(dir, "huge.png").apply { outputStream().use { out -> out.write(ByteArray((32 shl 20) + 1)) } }
        index("content://tree/music/document/a.flac", Uri.fromFile(huge).toString(), picture("cover.jpg", byteArrayOf(7)))

        assertThat(artwork.libraryPicture("content://tree/music/document/a.flac")).isEqualTo(byteArrayOf(7))
    }

    @Test
    fun `a library that cannot be read gives no picture, and its cancellation is not the export's`() = runTest {
        // Room cancels the queries of a closed database: that is the library failing, the export goes on.
        db.close()

        assertThat(artwork.libraryPicture("content://tree/music/document/a.flac")).isNull()
    }

    @Test
    fun `bytes that are no picture give no device images`() {
        assertThat(artwork.deviceImages(byteArrayOf(1, 2, 3))).isNull()
    }
}
