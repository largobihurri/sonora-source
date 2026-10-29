package io.sonora.core.data.library.edit

import android.content.Intent
import androidx.core.net.toUri
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.LibraryFolderEntity
import io.sonora.core.data.library.tags.ArtworkCache
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.TagEditFailure
import io.sonora.core.domain.port.TagReadOutcome
import io.sonora.core.domain.port.TagWriteOutcome
import io.sonora.core.domain.tags.TagChanges
import io.sonora.core.domain.tags.TagField
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What the editor decides before touching a file: the format, the folder's write access, a missing document. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafSongTagEditorTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var editor: SafSongTagEditor

    private val music = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    private val retitle = TagChanges(mapOf(TagField.TITLE to "New"))

    private fun track(name: String, mimeType: String) = Track(
        id = 1, uri = "$music/document/primary%3AMusic%2F$name", title = "T", artist = "A", album = "B",
        albumArtist = null, durationMs = 1_000, trackNumber = null, discNumber = null, year = null, albumId = 1,
        artworkUri = null, mimeType = mimeType, sizeBytes = 1,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        dao = db.libraryDao()
        editor = SafSongTagEditor(context, dao, SafWriteAccess(context, dao), LibraryRetag(dao, ArtworkCache(context)))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `formats without a tag writer are refused up front`() = runTest {
        for (track in listOf(track("a.ogg", "audio/ogg"), track("a.opus", "audio/opus"), track("a.aac", "audio/aac"))) {
            assertThat(editor.read(track)).isEqualTo(TagReadOutcome.Failed(TagEditFailure.UNSUPPORTED_FORMAT))
            assertThat(editor.write(track, retitle)).isEqualTo(TagWriteOutcome.Failed(TagEditFailure.UNSUPPORTED_FORMAT))
        }
    }

    @Test
    fun `a song in a folder held read-only asks for write access before anything is written`() = runTest {
        dao.insertFolder(LibraryFolderEntity(music, "Music", 1))
        context.contentResolver.takePersistableUriPermission(music.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val outcome = editor.write(track("a.flac", "audio/flac"), retitle)

        assertThat((outcome as TagWriteOutcome.NeedsWriteAccess).request.folderName).isEqualTo("Music")
    }

    @Test
    fun `a document that cannot be opened is unreadable`() = runTest {
        context.contentResolver.takePersistableUriPermission(
            music.toUri(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        assertThat(editor.read(track("gone.flac", "audio/flac"))).isEqualTo(TagReadOutcome.Failed(TagEditFailure.UNREADABLE))
        assertThat(editor.write(track("gone.flac", "audio/flac"), retitle))
            .isEqualTo(TagWriteOutcome.Failed(TagEditFailure.UNREADABLE))
    }
}
