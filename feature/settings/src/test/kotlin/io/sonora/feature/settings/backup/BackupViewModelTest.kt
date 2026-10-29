package io.sonora.feature.settings.backup

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.backup.ApplyRestore
import io.sonora.core.domain.backup.BackupError
import io.sonora.core.domain.backup.BackupFavourite
import io.sonora.core.domain.backup.BackupPin
import io.sonora.core.domain.backup.BackupPlaylist
import io.sonora.core.domain.backup.BackupPlays
import io.sonora.core.domain.backup.BackupReading
import io.sonora.core.domain.backup.BackupSettingsFile
import io.sonora.core.domain.backup.CreateBackup
import io.sonora.core.domain.backup.LibraryBackup
import io.sonora.core.domain.backup.ReadBackup
import io.sonora.core.domain.backup.RestorePreview
import io.sonora.core.domain.backup.RestoredMarks
import io.sonora.core.domain.backup.SongIdentity
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.port.BackupCodec
import io.sonora.core.domain.port.BackupDocuments
import io.sonora.core.domain.port.BackupHistory
import io.sonora.core.domain.port.LibraryBackupStore
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.domain.port.SettingsBackupStore
import io.sonora.feature.settings.ImportJobScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val song = SongIdentity("content://a/1", "One", "Band", "Record", 100_000)

    private class FakeStore(private val songs: List<SongIdentity>) : LibraryBackupStore {
        var failMerges = false
        override suspend fun librarySongs() = songs
        override suspend fun favourites() = emptyList<BackupFavourite>()
        override suspend fun pins() = emptyList<BackupPin>()
        override suspend fun hidden() = emptyList<HiddenItem>()
        override suspend fun plays() = emptyList<BackupPlays>()
        override suspend fun mergeMarks(marks: RestoredMarks) {
            if (failMerges) throw IllegalStateException("database closed")
        }
        override suspend fun mergePlays(plays: List<BackupPlays>) = Unit
    }

    private class FakeSettings : SettingsBackupStore {
        override suspend fun read() = emptyList<BackupSettingsFile>()
        override suspend fun merge(files: List<BackupSettingsFile>) = Unit
    }

    private class FakePlaylists : PlaylistRepository {
        val state = MutableStateFlow<List<PlaylistWithEntries>>(emptyList())
        override fun playlists(): Flow<List<PlaylistWithEntries>> = state
        override fun playlist(id: Long): Flow<PlaylistWithEntries?> = state.map { all -> all.firstOrNull { it.playlist.id == id } }
        override suspend fun create(name: String, description: String, entries: List<PlaylistEntry>): Long {
            val id = state.value.size + 1L
            state.value += PlaylistWithEntries(Playlist(id, name, description), entries)
            return id
        }
        override suspend fun updateDetails(id: Long, name: String, description: String) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun editEntries(id: Long, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>) = Unit
    }

    /** "backup" decodes to [backup]; "newer" to a newer version's refusal; anything else is malformed. */
    private class FakeCodec(private val backup: LibraryBackup) : BackupCodec {
        override fun encode(backup: LibraryBackup) = "backup"
        override fun decode(text: String): BackupReading = when (text) {
            "backup" -> BackupReading.Read(backup)
            "newer" -> BackupReading.Rejected(BackupError.NewerVersion(2))
            else -> BackupReading.Rejected(BackupError.Malformed("?"))
        }
    }

    private class FakeDocuments : BackupDocuments {
        val files = mutableMapOf("content://backup" to "backup", "content://newer" to "newer", "content://junk" to "junk")
        override suspend fun write(uri: String, text: String) {
            if (uri == "content://read-only") throw IOException("read-only")
            files[uri] = text
        }
        override suspend fun read(uri: String) = files[uri] ?: throw IOException("gone")
    }

    private class FakeHistory : BackupHistory {
        override val lastBackupMs = MutableStateFlow<Long?>(null)
        override suspend fun recordBackup(atMs: Long) {
            lastBackupMs.value = atMs
        }
    }

    private val store = FakeStore(listOf(song))
    private val playlists = FakePlaylists()
    private val documents = FakeDocuments()
    private val history = FakeHistory()
    private val codec = FakeCodec(
        LibraryBackup(createdAtMs = 1, songs = listOf(song), playlists = listOf(BackupPlaylist(4, "Mix", "", listOf(song.key, "content://gone")))),
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): BackupViewModel {
        val settings = FakeSettings()
        return BackupViewModel(
            createBackup = CreateBackup(store, settings, playlists, codec, documents, history, now = { 7_000 }),
            readBackup = ReadBackup(store, playlists, codec, documents),
            applyRestore = ApplyRestore(store, settings, playlists),
            jobs = ImportJobScope(SupervisorJob() + Dispatchers.Main),
            history = history,
        )
    }

    @Test
    fun `backing up writes the file, says so and shows the new date`() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.lastBackupMs.collect {} }

        viewModel.backUp("content://new")

        assertThat(documents.files["content://new"]).isEqualTo("backup")
        assertThat(viewModel.state.value).isEqualTo(BackupUiState(message = BackupMessage.SAVED))
        assertThat(viewModel.lastBackupMs.value).isEqualTo(7_000)
    }

    @Test
    fun `a backup that cannot be written says so`() {
        val viewModel = viewModel()

        viewModel.backUp("content://read-only")

        assertThat(viewModel.state.value).isEqualTo(BackupUiState(message = BackupMessage.WRITE_FAILED))
    }

    @Test
    fun `reading a backup previews it, and restoring reports what it did`() {
        val viewModel = viewModel()

        viewModel.read("content://backup")
        val preview = viewModel.state.value.preview
        assertThat(preview?.preview).isEqualTo(RestorePreview(playlists = 1, songsMatched = 1, songsNotFound = 1))
        assertThat(playlists.state.value).isEmpty()

        viewModel.confirmRestore()

        assertThat(playlists.state.value.single().playlist.name).isEqualTo("Mix")
        val state = viewModel.state.value
        assertThat(state.preview).isNull()
        assertThat(state.busy).isFalse()
        assertThat(state.report?.playlistsAdded).isEqualTo(1)
        assertThat(state.report?.unmatched?.map { it.key }).containsExactly("content://gone")

        viewModel.dismiss()
        assertThat(viewModel.state.value).isEqualTo(BackupUiState())
    }

    @Test
    fun `cancelling the preview restores nothing`() {
        val viewModel = viewModel()
        viewModel.read("content://backup")

        viewModel.cancelRestore()
        viewModel.confirmRestore()

        assertThat(playlists.state.value).isEmpty()
        assertThat(viewModel.state.value).isEqualTo(BackupUiState())
    }

    @Test
    fun `files that cannot be restored say why`() {
        val viewModel = viewModel()

        viewModel.read("content://missing")
        assertThat(viewModel.state.value.message).isEqualTo(BackupMessage.UNREADABLE)
        viewModel.read("content://newer")
        assertThat(viewModel.state.value.message).isEqualTo(BackupMessage.NEWER_VERSION)
        viewModel.read("content://junk")
        assertThat(viewModel.state.value.message).isEqualTo(BackupMessage.MALFORMED)
        assertThat(viewModel.state.value.preview).isNull()
    }

    @Test
    fun `a restore that fails partway says so`() {
        val viewModel = viewModel()
        store.failMerges = true
        viewModel.read("content://backup")

        viewModel.confirmRestore()

        assertThat(viewModel.state.value).isEqualTo(BackupUiState(message = BackupMessage.RESTORE_FAILED))
    }

    @Test
    fun `a missing picker is a notice`() {
        val viewModel = viewModel()

        viewModel.pickerMissing()

        assertThat(viewModel.state.value.message).isEqualTo(BackupMessage.NO_PICKER)
    }
}
