package io.sonora.core.data.library.edit

import android.content.Intent
import androidx.core.net.toUri
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.LibraryFolderEntity
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
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
class SafWriteAccessTest {

    private val context = RuntimeEnvironment.getApplication()
    private val resolver = context.contentResolver
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var access: SafWriteAccess

    private fun tree(documentId: String) =
        "content://com.android.externalstorage.documents/tree/" + documentId.replace(":", "%3A").replace("/", "%2F")

    private val music = tree("primary:Music")
    private val song = "$music/document/primary%3AMusic%2FRock%2Fa.flac"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        dao = db.libraryDao()
        access = SafWriteAccess(context, dao)
    }

    @After
    fun tearDown() = db.close()

    private fun writeGrants() = resolver.persistedUriPermissions.filter { it.isWritePermission }.map { it.uri.toString() }

    @Test
    fun `a library folder held read-only asks to be picked again, opening the picker on it`() = runTest {
        dao.insertFolder(LibraryFolderEntity(music, "Music", 1))
        resolver.takePersistableUriPermission(music.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val request = access.requestFor(song)

        assertThat(request).isEqualTo(WriteAccessRequest(music, "Music", "$music/document/primary%3AMusic"))
    }

    @Test
    fun `a folder held with write access needs nothing, nor does a file outside any tree`() = runTest {
        resolver.takePersistableUriPermission(
            music.toUri(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        assertThat(access.requestFor(song)).isNull()
        assertThat(access.requestFor("content://media/external/audio/media/7")).isNull()
    }

    @Test
    fun `a folder outside the library is named after its path`() = runTest {
        val request = access.requestFor("${tree("primary:Converted")}/document/primary%3AConverted%2Fb.mp3")

        assertThat(request?.folderName).isEqualTo("Converted")
    }

    @Test
    fun `picking the same folder keeps write access, another folder is refused without a grant`() = runTest {
        val request = WriteAccessRequest(music, "Music", "$music/document/primary%3AMusic")

        assertThat(access.grant(request, tree("primary:Download"))).isEqualTo(WriteGrant.WRONG_FOLDER)
        assertThat(writeGrants()).isEmpty()

        assertThat(access.grant(request, music)).isEqualTo(WriteGrant.GRANTED)
        assertThat(writeGrants()).containsExactly(music)
        assertThat(access.requestFor(song)).isNull()
    }
}
