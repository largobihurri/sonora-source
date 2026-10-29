package io.sonora.core.data.library.edit

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
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
import java.io.FileNotFoundException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafLibraryFileDeleterTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var deleter: SafLibraryFileDeleter
    private val folder = LibraryFolderEntity("content://$AUTHORITY/tree/root", "Music", addedAtMs = 1)
    private val song = "content://$AUTHORITY/tree/root/document/a.flac"

    @Before
    fun setUp() {
        StubDocuments.present = mutableSetOf("root", "a.flac")
        StubDocuments.deleteFails = null
        Robolectric.buildContentProvider(StubDocuments::class.java).create(ProviderInfo().apply { authority = AUTHORITY })
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        dao = db.libraryDao()
        deleter = SafLibraryFileDeleter(context, dao, SafWriteAccess(context, dao))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun index() {
        val scanned = ScannedTrack(
            documentUri = song, folderPath = "Music", fileName = "a.flac",
            tags = TrackTags(title = "A", artist = "Band", album = "Record", durationMs = 1_000),
        )
        dao.insertFolder(folder)
        val resolution = AlbumGrouping.resolve(listOf(scanned))
        val identity = FileIdentity(song, 10, 20)
        dao.upsertTracks(listOf(TrackEntity.of(scanned, folder.treeUri, "audio/flac", identity, 5, resolution.getValue(song))))
    }

    @Test
    fun `a deleted file leaves the library at once`() = runTest {
        index()

        assertThat(deleter.delete(song)).isTrue()

        assertThat(StubDocuments.present).containsExactly("root")
        assertThat(dao.trackByUri(song)).isNull()
    }

    @Test
    fun `a file already gone counts as deleted and leaves the library`() = runTest {
        index()
        StubDocuments.present.remove("a.flac")
        StubDocuments.deleteFails = FileNotFoundException("Missing file for a.flac")

        assertThat(deleter.delete(song)).isTrue()

        assertThat(dao.trackByUri(song)).isNull()
    }

    @Test
    fun `a delete the provider rejects counts as done when the file is no longer there`() = runTest {
        index()
        StubDocuments.present.remove("a.flac")
        StubDocuments.deleteFails = IllegalArgumentException("Unknown document a.flac")

        assertThat(deleter.delete(song)).isTrue()

        assertThat(dao.trackByUri(song)).isNull()
    }

    @Test
    fun `a file the storage refuses stays listed`() = runTest {
        index()
        StubDocuments.deleteFails = SecurityException("read-only")

        assertThat(deleter.delete(song)).isFalse()

        assertThat(dao.trackByUri(song)).isNotNull()
    }

    @Test
    fun `a file whose whole folder is out of reach is not taken for gone`() = runTest {
        index()
        // An unmounted card: the provider knows neither the file nor its folder.
        StubDocuments.present.clear()
        StubDocuments.deleteFails = FileNotFoundException("No root for root")

        assertThat(deleter.delete(song)).isFalse()

        assertThat(dao.trackByUri(song)).isNotNull()
    }

    /** A documents provider holding the [present] document ids; deleting throws [deleteFails] when set. */
    class StubDocuments : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
            // Like DocumentsProvider: a document that does not exist reads as no cursor at all.
            val id = DocumentsContract.getDocumentId(uri)
            if (id !in present) return null
            return MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID)).apply { addRow(arrayOf<Any?>(id)) }
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            deleteFails?.let { throw it }
            // The only song these tests delete.
            present.remove("a.flac")
            return Bundle()
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        companion object {
            var present = mutableSetOf<String>()
            var deleteFails: Exception? = null
        }
    }

    private companion object {
        const val AUTHORITY = "io.sonora.test.deletes"
    }
}
