package io.sonora.core.data.library

import android.content.ContentResolver
import android.content.Intent
import androidx.core.net.toUri
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.domain.library.removal.FolderAccessUsers
import io.sonora.core.domain.model.AddFolderResult
import io.sonora.core.domain.model.FolderConflict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafLibraryFolderRepositoryTest {

    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var resolver: ContentResolver
    private lateinit var repository: SafLibraryFolderRepository

    /** Trees another feature (the converter) still needs; [usersFail] makes the check break. */
    private val neededElsewhere = mutableSetOf<String>()
    private var usersFail = false
    private val users = FolderAccessUsers { tree ->
        check(!usersFail) { "database closed" }
        tree in neededElsewhere
    }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        dao = db.libraryDao()
        resolver = context.contentResolver
        repository = SafLibraryFolderRepository(context, dao, users)
    }

    @After
    fun tearDown() = db.close()

    private fun tree(documentId: String) =
        "content://com.android.externalstorage.documents/tree/" + documentId.replace(":", "%3A").replace("/", "%2F")

    private fun grants() = resolver.persistedUriPermissions.map { it.uri.toString() }

    @Test
    fun `adding a folder stores it with its path and keeps read access`() = runTest {
        val result = repository.add(tree("primary:Music"))

        assertThat((result as AddFolderResult.Added).folder.displayName).isEqualTo("Music")
        assertThat(repository.folders().first().map { it.treeUri }).containsExactly(tree("primary:Music"))
        assertThat(grants()).containsExactly(tree("primary:Music"))
    }

    @Test
    fun `a new folder keeps write access too, so its songs can be retagged`() = runTest {
        repository.add(tree("primary:Music"))

        val grant = resolver.persistedUriPermissions.single()
        assertThat(grant.isReadPermission).isTrue()
        assertThat(grant.isWritePermission).isTrue()
    }

    @Test
    fun `removing a folder releases its read and write access`() = runTest {
        repository.add(tree("primary:Music"))

        repository.remove(tree("primary:Music"))

        assertThat(resolver.persistedUriPermissions).isEmpty()
    }

    @Test
    fun `a uri that is not a tree is refused without keeping access`() {
        val document = "content://com.android.externalstorage.documents/document/primary%3AMusic"

        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.add(document) } }

        assertThat(grants()).isEmpty()
        assertThat(runBlocking { dao.folderList() }).isEmpty()
    }

    @Test
    fun `nested and parent folders are rejected without keeping access`() = runTest {
        repository.add(tree("primary:Music"))
        val music = repository.folders().first().single()

        assertThat(repository.add(tree("primary:Music/Rock")))
            .isEqualTo(AddFolderResult.Rejected(FolderConflict.InsideExisting(music)))
        assertThat(repository.add(tree("primary:")))
            .isEqualTo(AddFolderResult.Rejected(FolderConflict.ContainsExisting(music)))
        assertThat(repository.add(tree("primary:Music")))
            .isEqualTo(AddFolderResult.Rejected(FolderConflict.AlreadyAdded(music)))
        assertThat(grants()).containsExactly(tree("primary:Music"))
    }

    @Test
    fun `removing a folder releases its access`() = runTest {
        repository.add(tree("primary:Music"))

        repository.remove(tree("primary:Music"))

        assertThat(grants()).isEmpty()
        assertThat(repository.folders().first()).isEmpty()
    }

    /** Makes every folder insert fail like a full disk. */
    private fun failFolderInserts() = db.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER fail_insert BEFORE INSERT ON library_folders BEGIN SELECT RAISE(ABORT, 'disk full'); END",
    )

    @Test
    fun `a folder that cannot be stored releases the access the add took`() {
        failFolderInserts()

        assertThrows(Exception::class.java) { runBlocking { repository.add(tree("primary:Music")) } }

        assertThat(grants()).isEmpty()
        assertThat(runBlocking { dao.folderList() }).isEmpty()
    }

    @Test
    fun `a folder that cannot be stored keeps access granted before the add`() {
        resolver.takePersistableUriPermission(tree("primary:Music").toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        failFolderInserts()

        assertThrows(Exception::class.java) { runBlocking { repository.add(tree("primary:Music")) } }

        assertThat(grants()).containsExactly(tree("primary:Music"))
    }

    @Test
    fun `access granted outside the repository is left alone when a folder is rejected`() = runTest {
        repository.add(tree("primary:Music"))
        resolver.takePersistableUriPermission(tree("primary:Podcasts").toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)

        repository.add(tree("primary:Music/Rock"))

        assertThat(grants()).containsExactly(tree("primary:Music"), tree("primary:Podcasts"))
    }

    @Test
    fun `removing a folder keeps the access another feature still needs`() = runTest {
        repository.add(tree("primary:Music"))
        neededElsewhere += tree("primary:Music")

        repository.remove(tree("primary:Music"))

        val grant = resolver.persistedUriPermissions.single()
        assertThat(grant.uri.toString()).isEqualTo(tree("primary:Music"))
        assertThat(grant.isWritePermission).isTrue()
        assertThat(repository.folders().first()).isEmpty()
    }

    @Test
    fun `removing a folder keeps its access when the other users cannot be asked`() = runTest {
        repository.add(tree("primary:Music"))
        usersFail = true

        repository.remove(tree("primary:Music"))

        assertThat(grants()).containsExactly(tree("primary:Music"))
        assertThat(repository.folders().first()).isEmpty()
    }

    @Test
    fun `removing a folder leaves other folders' access alone`() = runTest {
        repository.add(tree("primary:Music"))
        neededElsewhere += tree("primary:DJ")
        resolver.takePersistableUriPermission(tree("primary:DJ").toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)

        repository.remove(tree("primary:Music"))

        assertThat(grants()).containsExactly(tree("primary:DJ"))
    }
}
