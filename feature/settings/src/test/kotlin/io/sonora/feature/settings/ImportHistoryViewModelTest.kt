package io.sonora.feature.settings

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveImportEntry
import io.sonora.core.domain.archive.ForgetArchiveImport
import io.sonora.core.domain.archive.ImportDeleter
import io.sonora.core.domain.archive.ImportDeletion
import io.sonora.core.domain.archive.ImportedSong
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.port.ArchiveImportRepository
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain, UnconfinedTestDispatcher
class ImportHistoryViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val imports = FakeImports()
    private val deleter = FakeDeleter()
    private val files = FakeFiles()
    private lateinit var viewModel: ImportHistoryViewModel

    private val older = ArchiveImportEntry(1, "older.zip", 1, "tree", "tree/document/older", songCount = 1)
    private val newer = ArchiveImportEntry(2, "newer.zip", 5, "tree", "tree/document/newer", playlistId = 4, songCount = 2)
    private val songs = listOf(ImportedSong("p/newer/a.flac", "A", "Band"), ImportedSong("p/newer/b.flac", "B", ""))
    private val request = WriteAccessRequest("content://p/tree/music", "Music", "content://p/tree/music/document/music")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        imports.entries.value = listOf(older to listOf(ImportedSong("p/older/c.flac", "C", "Band")), newer to songs)
        viewModel = ImportHistoryViewModel(imports, deleter, ForgetArchiveImport(imports), files)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the history lists imports newest first, and an entry opens to its songs`() = runTest(dispatcher) {
        backgroundScope.launchCollecting()

        assertThat(viewModel.imports.value.map { it.archiveName }).containsExactly("newer.zip", "older.zip").inOrder()

        viewModel.open(newer.id)
        assertThat(viewModel.opened.value).isEqualTo(OpenedImport(newer, songs))

        viewModel.close()
        assertThat(viewModel.opened.value).isNull()
    }

    @Test
    fun `Delete from Phone asks first, then deletes and closes the entry`() = runTest(dispatcher) {
        backgroundScope.launchCollecting()
        viewModel.open(newer.id)

        viewModel.chooseDelete()
        assertThat(viewModel.deletion.value).isEqualTo(HistoryDeletion.Confirm(newer))
        assertThat(deleter.deleted).isEmpty()

        viewModel.confirmDelete()

        assertThat(deleter.deleted).containsExactly(newer.id)
        assertThat(viewModel.deletion.value).isNull()
        assertThat(viewModel.notice.value).isEqualTo(HistoryNotice.Deleted(2))
        assertThat(viewModel.opened.value).isNull()
    }

    @Test
    fun `cancelling the confirmation deletes nothing`() = runTest(dispatcher) {
        viewModel.open(newer.id)
        viewModel.chooseDelete()

        viewModel.cancelDelete()

        assertThat(viewModel.deletion.value).isNull()
        assertThat(deleter.deleted).isEmpty()
    }

    @Test
    fun `a read-only folder is picked again to allow deleting, then the import is deleted`() = runTest(dispatcher) {
        backgroundScope.launchCollecting()
        deleter.results += ImportDeletion.NeedsWriteAccess(request)
        viewModel.open(newer.id)
        viewModel.chooseDelete()
        viewModel.confirmDelete()
        assertThat(viewModel.deletion.value).isEqualTo(HistoryDeletion.NeedsAccess(newer, request))

        files.grant = WriteGrant.WRONG_FOLDER
        viewModel.accessPicked("content://p/tree/other")
        assertThat(viewModel.deletion.value).isEqualTo(HistoryDeletion.NeedsAccess(newer, request, wrongFolder = true))

        files.grant = WriteGrant.GRANTED
        viewModel.accessPicked(request.treeUri)

        assertThat(files.picked).containsExactly("content://p/tree/other", request.treeUri).inOrder()
        assertThat(deleter.deleted).containsExactly(newer.id, newer.id)
        assertThat(viewModel.notice.value).isEqualTo(HistoryNotice.Deleted(2))
    }

    @Test
    fun `storage that cannot be written says so and deletes nothing more`() = runTest(dispatcher) {
        deleter.results += ImportDeletion.NeedsWriteAccess(request)
        viewModel.open(newer.id)
        viewModel.chooseDelete()
        viewModel.confirmDelete()
        files.grant = WriteGrant.REFUSED

        viewModel.accessPicked(request.treeUri)

        assertThat(viewModel.deletion.value).isNull()
        assertThat(viewModel.notice.value).isEqualTo(HistoryNotice.StorageRefused)
        assertThat(deleter.deleted).containsExactly(newer.id)
    }

    @Test
    fun `what could not be deleted is reported and the entry stays open`() = runTest(dispatcher) {
        backgroundScope.launchCollecting()
        deleter.results += ImportDeletion.Incomplete(deleted = 1, failed = 1, folderKept = true)
        viewModel.open(newer.id)
        viewModel.chooseDelete()

        viewModel.confirmDelete()

        assertThat(viewModel.notice.value).isEqualTo(HistoryNotice.Incomplete(deleted = 1, failed = 1))
        assertThat(viewModel.opened.value?.entry).isEqualTo(newer)
    }

    @Test
    fun `a failed deletion says so`() = runTest(dispatcher) {
        deleter.fails = true
        viewModel.open(newer.id)
        viewModel.chooseDelete()

        viewModel.confirmDelete()

        assertThat(viewModel.deletion.value).isNull()
        assertThat(viewModel.notice.value).isEqualTo(HistoryNotice.Failed)
        viewModel.dismissNotice()
        assertThat(viewModel.notice.value).isNull()
    }

    @Test
    fun `Remove from History forgets the record only and closes the entry`() = runTest(dispatcher) {
        backgroundScope.launchCollecting()
        viewModel.open(newer.id)

        viewModel.removeFromHistory()

        assertThat(imports.entries.value.map { it.first.id }).containsExactly(older.id)
        assertThat(deleter.deleted).isEmpty()
        assertThat(viewModel.opened.value).isNull()
        assertThat(viewModel.imports.value.map { it.id }).containsExactly(older.id)
    }

    /** Keeps the view model's flows hot, as the screen does. */
    private fun CoroutineScope.launchCollecting() {
        launch(dispatcher) { viewModel.imports.collect {} }
        launch(dispatcher) { viewModel.opened.collect {} }
    }

    private class FakeImports : ArchiveImportRepository {
        val entries = MutableStateFlow<List<Pair<ArchiveImportEntry, List<ImportedSong>>>>(emptyList())
        override suspend fun add(entry: ArchiveImportEntry, songs: List<ImportedSong>): Long = error("unused")
        override fun imports(): Flow<List<ArchiveImportEntry>> = entries.map { all -> all.map { it.first }.sortedByDescending { it.importedAtMs } }
        override fun import(id: Long): Flow<ArchiveImportEntry?> = entries.map { all -> all.firstOrNull { it.first.id == id }?.first }
        override fun songs(importId: Long): Flow<List<ImportedSong>> = entries.map { all -> all.firstOrNull { it.first.id == importId }?.second.orEmpty() }
        override suspend fun get(id: Long): ArchiveImportEntry? = entries.value.firstOrNull { it.first.id == id }?.first
        override suspend fun getSongs(importId: Long): List<ImportedSong> = entries.value.firstOrNull { it.first.id == importId }?.second.orEmpty()
        override suspend fun setPlaylist(id: Long, playlistId: Long?) = Unit
        override suspend fun remove(id: Long) {
            entries.value = entries.value.filterNot { it.first.id == id }
        }
    }

    /** Answers with [results] in turn, then deletes for good. */
    private inner class FakeDeleter : ImportDeleter {
        val deleted = mutableListOf<Long>()
        val results = ArrayDeque<ImportDeletion>()
        var fails = false
        override suspend fun delete(importId: Long): ImportDeletion {
            if (fails) throw IllegalStateException("The provider died")
            deleted += importId
            return results.removeFirstOrNull() ?: ImportDeletion.Deleted(imports.getSongs(importId).size).also { imports.remove(importId) }
        }
    }

    private class FakeFiles : LibraryFileDeleter {
        var grant = WriteGrant.GRANTED
        val picked = mutableListOf<String>()
        override suspend fun writeAccessFor(uri: String): WriteAccessRequest? = error("unused")
        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String): WriteGrant {
            picked += pickedTreeUri
            return grant
        }
        override suspend fun delete(uri: String): Boolean = error("unused")
    }
}
