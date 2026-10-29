package io.sonora.feature.settings.duplicates

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.duplicates.DeleteDuplicateCopies
import io.sonora.core.domain.library.duplicates.DuplicateScan
import io.sonora.core.domain.library.duplicates.DuplicateScanState
import io.sonora.core.domain.library.duplicates.FindDuplicates
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain, UnconfinedTestDispatcher
class DuplicatesViewModelTest {

    private class Files(private val library: TestLibrary) : LibraryFileDeleter {
        var readOnly = false
        var grant = WriteGrant.GRANTED
        val deleted = mutableListOf<String>()

        override suspend fun writeAccessFor(uri: String) = if (readOnly) REQUEST else null

        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String): WriteGrant {
            if (grant == WriteGrant.GRANTED) readOnly = false
            return grant
        }

        override suspend fun delete(uri: String): Boolean {
            deleted += uri
            library.files = library.files.filterNot { it.uri == uri }
            return true
        }
    }

    private object NoPlaylists : PlaylistRepository {
        override fun playlists(): Flow<List<PlaylistWithEntries>> = flowOf(emptyList())
        override fun playlist(id: Long): Flow<PlaylistWithEntries?> = flowOf(null)
        override suspend fun create(name: String, description: String, entries: List<PlaylistEntry>) = 0L
        override suspend fun updateDetails(id: Long, name: String, description: String) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun editEntries(id: Long, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>) = Unit
    }

    private object NoMarks : LibraryMarksRepository {
        override fun favourites(kind: MarkKind): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setFavourite(ref: ItemRef, favourite: Boolean) = Unit
        override fun pins(context: PinContext): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setPinned(context: PinContext, ref: ItemRef, pinned: Boolean) = Unit
        override fun hidden(): Flow<List<HiddenItem>> = flowOf(emptyList())
        override suspend fun hide(item: HiddenItem) = Unit
        override suspend fun restore(ref: ItemRef) = Unit
        override suspend fun forgetSongs(uris: Collection<String>) = Unit
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private val best = copy("best", bitsPerSample = 24)
    private val cd = copy("cd")
    private val lossy = copy("lossy", format = "MP3", bitsPerSample = null, bitrateKbps = 320)
    private val other1 = copy("o1", title = "Other")
    private val other2 = copy("o2", title = "Other", format = "MP3", bitsPerSample = null)
    private val library = TestLibrary(listOf(best, cd, lossy, other1, other2))
    private val files = Files(library)
    private val dismissals = TestDismissals()
    private val alerts = TestAlerts()
    private lateinit var scan: DuplicateScan
    private lateinit var viewModel: DuplicatesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scan = DuplicateScan(
            FindDuplicates(library, NoHashes, TestFingerprints(), dismissals),
            library,
            dismissals,
            DeleteDuplicateCopies(DeleteFromPhone(files, NoPlaylists, NoMarks)),
            CoroutineScope(dispatcher),
        )
        viewModel = DuplicatesViewModel(scan, files, alerts)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun groups() = (viewModel.state.value as DuplicateScanState.Found).groups

    private fun song() = groups().first { it.copies.size == 3 }

    @Test
    fun `opening the screen scans once, and a group opens with every copy but the suggested one selected`() = runTest(dispatcher) {
        assertThat(groups()).hasSize(2)
        viewModel.open(song())
        assertThat(viewModel.opened.value).isEqualTo(OpenedGroup(song(), setOf(cd.uri, lossy.uri)))
        viewModel.close()
        assertThat(viewModel.opened.value).isNull()
    }

    @Test
    fun `the last copy left cannot be selected`() = runTest(dispatcher) {
        viewModel.open(song())
        viewModel.toggle(best.uri)
        assertThat(viewModel.opened.value!!.selected).containsExactly(cd.uri, lossy.uri)
        viewModel.toggle(cd.uri)
        viewModel.toggle(best.uri)
        assertThat(viewModel.opened.value!!.selected).containsExactly(best.uri, lossy.uri)
    }

    @Test
    fun `deleting asks first, then deletes the selected copies and closes the group`() = runTest(dispatcher) {
        viewModel.open(song())
        viewModel.chooseDelete()
        assertThat(viewModel.deletion.value).isEqualTo(CopiesDeletion.Confirm(song(), setOf(cd.uri, lossy.uri)))
        assertThat(files.deleted).isEmpty()
        viewModel.confirmDelete()
        assertThat(files.deleted).containsExactly(cd.uri, lossy.uri)
        assertThat(viewModel.deletion.value).isNull()
        assertThat(viewModel.notice.value).isEqualTo(DuplicatesNotice.Deleted(2))
        assertThat(viewModel.opened.value).isNull()
        assertThat(groups()).hasSize(1)
    }

    @Test
    fun `a read-only folder is picked again, then deleting goes on`() = runTest(dispatcher) {
        files.readOnly = true
        viewModel.open(song())
        viewModel.chooseDelete()
        viewModel.confirmDelete()
        val step = viewModel.deletion.value as CopiesDeletion.NeedsAccess
        assertThat(step.request).isEqualTo(REQUEST)
        files.grant = WriteGrant.WRONG_FOLDER
        viewModel.accessPicked("content://other")
        assertThat((viewModel.deletion.value as CopiesDeletion.NeedsAccess).wrongFolder).isTrue()
        files.grant = WriteGrant.GRANTED
        viewModel.accessPicked("content://tree/music")
        assertThat(files.deleted).containsExactly(cd.uri, lossy.uri)
        assertThat(viewModel.notice.value).isEqualTo(DuplicatesNotice.Deleted(2))
    }

    @Test
    fun `storage that refuses says so`() = runTest(dispatcher) {
        files.readOnly = true
        files.grant = WriteGrant.REFUSED
        viewModel.open(song())
        viewModel.chooseDelete()
        viewModel.confirmDelete()
        viewModel.accessPicked("content://tree/music")
        assertThat(viewModel.deletion.value).isNull()
        assertThat(viewModel.notice.value).isEqualTo(DuplicatesNotice.StorageRefused)
        assertThat(files.deleted).isEmpty()
    }

    @Test
    fun `Not a Duplicate hides the group for good`() = runTest(dispatcher) {
        val group = song()
        viewModel.open(group)
        viewModel.notADuplicate()
        assertThat(viewModel.opened.value).isNull()
        assertThat(groups().map { it.key }).doesNotContain(group.key)
        assertThat(dismissals.sets).containsExactly(setOf(best.uri, cd.uri, lossy.uri))
    }

    @Test
    fun `the notification switch follows the setting, and a new scan can be asked for`() = runTest(dispatcher) {
        assertThat(viewModel.notifications.value).isTrue()
        viewModel.setNotifications(false)
        assertThat(alerts.enabled.value).isFalse()
        assertThat(viewModel.notifications.value).isFalse()
        assertThat(viewModel.notificationsWanted()).isFalse()
        library.files = listOf(best, cd)
        viewModel.scanAgain()
        assertThat(groups()).hasSize(1)
    }

    private companion object {
        val REQUEST = WriteAccessRequest("content://tree/music", "Music", "content://tree/music/document/music")
    }
}
