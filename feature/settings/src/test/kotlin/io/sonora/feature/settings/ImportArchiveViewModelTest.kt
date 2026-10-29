package io.sonora.feature.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveEntry
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveImporter
import io.sonora.core.domain.archive.ArchiveJobScheduler
import io.sonora.core.domain.archive.ArchiveJobState
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveManifest
import io.sonora.core.domain.archive.ArchiveOutcome
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.archive.ArchiveProgressListener
import io.sonora.core.domain.archive.ArchiveSummary
import io.sonora.core.domain.archive.CompressionRequest
import io.sonora.core.domain.archive.DestinationCoverage
import io.sonora.core.domain.archive.ExtractedArchive
import io.sonora.core.domain.archive.ImportFinisher
import io.sonora.core.domain.archive.ImportOutcome
import io.sonora.core.domain.archive.ImportRecorder
import io.sonora.core.domain.archive.ImportedSong
import io.sonora.core.domain.archive.RecordedImport
import io.sonora.core.domain.model.AddFolderResult
import io.sonora.core.domain.model.LibraryFolder
import io.sonora.core.domain.port.ConversionOutputRootStore
import io.sonora.core.domain.port.LibraryFolderRepository
import io.sonora.core.domain.port.OutputRootFolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain, UnconfinedTestDispatcher
class ImportArchiveViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val provider = "content://com.android.externalstorage.documents"
    private val musicTree = "$provider/tree/primary%3AMusic"
    private val archiveUri = "content://downloads/mix.zip"
    private val manifest = ArchiveManifest(ArchiveKind.PLAYLIST, "Road Trip", listOf(ArchiveEntry("01 - A - One.flac", "One", "A", "Road Trip")))

    private val importer = FakeImporter(ArchiveOutcome.Done(ArchiveSummary(encrypted = false, manifest = manifest)))
    private val scheduler = FakeScheduler()
    private val outputRoot = FakeOutputRoot(OutputRootFolder(musicTree, "Music"))
    private val folders = FakeLibraryFolders(listOf(LibraryFolder(musicTree, "Music", 0)))
    private val finisher = FakeFinisher()
    private val documents = FakeDocuments()
    private val recorder = FakeRecorder()
    private lateinit var jobs: ImportJobScope
    private lateinit var viewModel: ImportArchiveViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        jobs = ImportJobScope(SupervisorJob() + dispatcher)
        viewModel = newViewModel()
    }

    @After
    fun tearDown() {
        jobs.cancel()
        Dispatchers.resetMain()
    }

    private fun newViewModel() = ImportArchiveViewModel(importer, scheduler, outputRoot, folders, finisher, documents, jobs, recorder)

    private fun ready(
        summary: ArchiveSummary = ArchiveSummary(false, manifest),
        destination: ImportDestination? = ImportDestination(musicTree, "Music"),
        coverage: DestinationCoverage? = DestinationCoverage.IN_LIBRARY,
        passwordError: PasswordError? = null,
    ) = ImportStep.Ready(archiveUri, "mix.zip", summary, destination, coverage, passwordError)

    @Test
    fun `opening an archive inspects it and offers the converter's output folder`() = runTest(dispatcher) {
        viewModel.open(archiveUri, "mix.zip")

        assertThat(viewModel.step.value).isEqualTo(ready())
    }

    @Test
    fun `a damaged archive says so at once`() = runTest(dispatcher) {
        importer.inspection = ArchiveOutcome.Failed(ArchiveFailure.Damaged)

        viewModel.open(archiveUri, "mix.zip")

        assertThat(viewModel.step.value).isEqualTo(ImportStep.Failed(ArchiveFailure.Damaged))
    }

    @Test
    fun `without an output folder one is asked for and kept as the converter's`() = runTest(dispatcher) {
        outputRoot.root.value = null
        viewModel.open(archiveUri, "mix.zip")
        assertThat(viewModel.step.value).isEqualTo(ready(destination = null, coverage = null))

        viewModel.chooseDestination(musicTree)

        assertThat(outputRoot.set).containsExactly(musicTree)
        assertThat(viewModel.step.value).isEqualTo(ready())
    }

    @Test
    fun `Change picks another folder for this import only`() = runTest(dispatcher) {
        viewModel.open(archiveUri, "mix.zip")
        val other = "$provider/tree/primary%3ADownload%2FShared"

        viewModel.chooseDestination(other)

        assertThat(outputRoot.set).isEmpty()
        assertThat(viewModel.step.value)
            .isEqualTo(ready(destination = ImportDestination(other, "Download/Shared"), coverage = DestinationCoverage.JOINS_LIBRARY))
    }

    @Test
    fun `a folder that holds a library folder can take the import, its new folder then joins`() = runTest(dispatcher) {
        viewModel.open(archiveUri, "mix.zip")

        viewModel.chooseDestination("$provider/tree/primary%3A")

        val step = viewModel.step.value as ImportStep.Ready
        assertThat(step.coverage).isEqualTo(DestinationCoverage.JOINS_LIBRARY)
        assertThat(step.canImport(passwordTyped = false)).isTrue()
    }

    private val shared = "$provider/tree/primary%3ADownload%2FShared"
    private val extracted = "$shared/document/primary%3ADownload%2FShared%2FRoad%20Trip"
    private val extractedTree = "$provider/tree/primary%3ADownload%2FShared%2FRoad%20Trip"

    /** An import into [shared], outside the library, whose extraction just ended. */
    private fun extractedOutsideTheLibrary() {
        viewModel.open(archiveUri, "mix.zip")
        viewModel.chooseDestination(shared)
        viewModel.importArchive(null)
        scheduler.states.getValue("job-1").value = ArchiveJobState.Extracted(extracted, 3, 0)
    }

    @Test
    fun `outside the library, the extracted folder is offered to join it`() = runTest(dispatcher) {
        extractedOutsideTheLibrary()

        assertThat(viewModel.step.value).isEqualTo(
            ImportStep.ChoosingLibraryFolder("job-1", "Road Trip", extracted, fileCount = 3, skipped = 0, record = recorder.recorded),
        )
        assertThat(finisher.calls).isEmpty()

        viewModel.libraryFolderPickerOpened()
        assertThat((viewModel.step.value as ImportStep.ChoosingLibraryFolder).pickerOpened).isTrue()
    }

    @Test
    fun `allowing the extracted folder adds it and brings the playlist back`() = runTest(dispatcher) {
        extractedOutsideTheLibrary()

        viewModel.libraryFolderPicked(extractedTree)

        assertThat(finisher.added).containsExactly(extractedTree to extracted)
        assertThat(viewModel.step.value)
            .isEqualTo(ImportStep.Done("Road Trip", fileCount = 3, skipped = 0, outcome = finisher.joined, songs = recorder.songs))
        // The playlist the allowed folder brought back is kept with the import's record.
        assertThat(recorder.playlists).containsExactly(1L to 7L)
    }

    @Test
    fun `declining keeps the music outside the library, with Add to Library offered again`() = runTest(dispatcher) {
        extractedOutsideTheLibrary()

        viewModel.libraryFolderDeclined()

        val outside = ImportOutcome(DestinationCoverage.OUTSIDE_LIBRARY, playlistName = null, playlistSongs = 0, missing = 0)
        val addable = AddableFolder("job-1", extracted, recorder.recorded)
        assertThat(viewModel.step.value).isEqualTo(ImportStep.Done("Road Trip", 3, 0, outside, addable = addable, songs = recorder.songs))

        viewModel.addToLibrary()
        assertThat(viewModel.step.value).isEqualTo(ImportStep.ChoosingLibraryFolder("job-1", "Road Trip", extracted, 3, 0, record = recorder.recorded))
        viewModel.libraryFolderPicked(extractedTree)
        assertThat(finisher.added).containsExactly(extractedTree to extracted)
    }

    @Test
    fun `another folder picked instead is not added, and says so`() = runTest(dispatcher) {
        extractedOutsideTheLibrary()

        viewModel.libraryFolderPicked(shared)

        assertThat(finisher.added).isEmpty()
        val done = viewModel.step.value as ImportStep.Done
        assertThat(done.wrongFolder).isTrue()
        assertThat(done.addable).isEqualTo(AddableFolder("job-1", extracted, recorder.recorded))
        assertThat(done.outcome.coverage).isEqualTo(DestinationCoverage.OUTSIDE_LIBRARY)
    }

    @Test
    fun `a failed add says the music is outside the library and offers it again`() = runTest(dispatcher) {
        finisher.addFails = true
        extractedOutsideTheLibrary()

        viewModel.libraryFolderPicked(extractedTree)

        val done = viewModel.step.value as ImportStep.Done
        assertThat(done.outcome.coverage).isEqualTo(DestinationCoverage.OUTSIDE_LIBRARY)
        assertThat(done.addable).isEqualTo(AddableFolder("job-1", extracted, recorder.recorded))
    }

    @Test
    fun `an encrypted archive needs a typed password`() = runTest(dispatcher) {
        importer.inspection = ArchiveOutcome.Done(ArchiveSummary(encrypted = true, manifest = null))
        viewModel.open(archiveUri, "mix.zip")
        val step = viewModel.step.value as ImportStep.Ready
        assertThat(step.canImport(passwordTyped = false)).isFalse()
        assertThat(step.canImport(passwordTyped = true)).isTrue()

        viewModel.importArchive(null)

        assertThat(scheduler.extractions).isEmpty()
    }

    @Test
    fun `importing extracts, then finishes in the library and reports the playlist`() = runTest(dispatcher) {
        viewModel.open(archiveUri, "mix.zip")

        viewModel.importArchive("Strong-Password-1".toCharArray())

        assertThat(scheduler.extractions).containsExactly(archiveUri to musicTree)
        assertThat(scheduler.passwords.map { it?.let(::String) }).containsExactly("Strong-Password-1")
        assertThat(viewModel.step.value).isEqualTo(ImportStep.Extracting("job-1", "Road Trip", null))

        val progress = ArchiveProgress(1, 3, 10, 30)
        scheduler.states.getValue("job-1").value = ArchiveJobState.Running(progress)
        assertThat(viewModel.step.value).isEqualTo(ImportStep.Extracting("job-1", "Road Trip", progress))

        scheduler.states.getValue("job-1").value = ArchiveJobState.Extracted("$musicTree/document/primary%3AMusic%2FRoad%20Trip", 3, 1)

        assertThat(finisher.calls).containsExactly(musicTree to "$musicTree/document/primary%3AMusic%2FRoad%20Trip")
        assertThat(viewModel.step.value)
            .isEqualTo(ImportStep.Done("Road Trip", fileCount = 3, skipped = 1, outcome = finisher.outcome, songs = recorder.songs))
    }

    @Test
    fun `a finished extraction is recorded with its songs, and the playlist it recreated`() = runTest(dispatcher) {
        viewModel.open(archiveUri, "mix.zip")
        viewModel.importArchive(null)

        scheduler.states.getValue("job-1").value = ArchiveJobState.Extracted("$musicTree/document/x", 3, 0)

        assertThat(recorder.records).containsExactly(Triple("mix.zip", musicTree, "$musicTree/document/x"))
        assertThat(recorder.playlists).containsExactly(1L to 7L)
        assertThat((viewModel.step.value as ImportStep.Done).songs).isEqualTo(recorder.songs)
    }

    @Test
    fun `an import whose sheet another archive replaced is still recorded`() = runTest(dispatcher) {
        viewModel.open(archiveUri, "mix.zip")
        viewModel.importArchive(null)
        viewModel.hide()
        viewModel.open("content://downloads/other.zip", "other.zip")

        scheduler.states.getValue("job-1").value = ArchiveJobState.Extracted("$musicTree/document/x", 1, 0)

        assertThat(recorder.records.map { it.first }).containsExactly("mix.zip")
        assertThat(recorder.playlists).containsExactly(1L to 7L)
    }

    @Test
    fun `an import whose record cannot be written still finishes, without its song list`() = runTest(dispatcher) {
        recorder.fails = true
        viewModel.open(archiveUri, "mix.zip")
        viewModel.importArchive(null)

        scheduler.states.getValue("job-1").value = ArchiveJobState.Extracted("$musicTree/document/x", 3, 0)

        assertThat(viewModel.step.value).isEqualTo(ImportStep.Done("Road Trip", fileCount = 3, skipped = 0, outcome = finisher.outcome))
        assertThat(recorder.playlists).isEmpty()
    }

    @Test
    fun `a wrong password goes back to the password, asking again`() = runTest(dispatcher) {
        importer.inspection = ArchiveOutcome.Done(ArchiveSummary(encrypted = true, manifest = null))
        viewModel.open(archiveUri, "mix.zip")
        viewModel.importArchive("guess".toCharArray())

        scheduler.states.getValue("job-1").value = ArchiveJobState.Failed(ArchiveFailure.WrongPassword)

        assertThat(viewModel.step.value).isEqualTo(ready(summary = ArchiveSummary(true, null), passwordError = PasswordError.WRONG))
        assertThat(finisher.calls).isEmpty()
    }

    @Test
    fun `a password lost with the app asks for it again`() = runTest(dispatcher) {
        importer.inspection = ArchiveOutcome.Done(ArchiveSummary(encrypted = true, manifest = null))
        viewModel.open(archiveUri, "mix.zip")
        viewModel.importArchive("secret".toCharArray())

        scheduler.states.getValue("job-1").value = ArchiveJobState.Failed(ArchiveFailure.PasswordNeededAgain)

        assertThat((viewModel.step.value as ImportStep.Ready).passwordError).isEqualTo(PasswordError.NEEDED_AGAIN)
    }

    @Test
    fun `other failures end the import with their reason`() = runTest(dispatcher) {
        viewModel.open(archiveUri, "mix.zip")
        viewModel.importArchive(null)

        scheduler.states.getValue("job-1").value = ArchiveJobState.Failed(ArchiveFailure.NotEnoughSpace(10, 5))

        assertThat(viewModel.step.value).isEqualTo(ImportStep.Failed(ArchiveFailure.NotEnoughSpace(10, 5)))
    }

    @Test
    fun `stopping cancels the job, hiding keeps following it`() = runTest(dispatcher) {
        viewModel.open(archiveUri, "mix.zip")
        viewModel.importArchive(null)

        viewModel.hide()
        assertThat(viewModel.step.value).isEqualTo(ImportStep.Extracting("job-1", "Road Trip", null, hidden = true))

        viewModel.stop()
        assertThat(scheduler.cancelled).containsExactly("job-1")
        assertThat(viewModel.step.value).isNull()
    }

    @Test
    fun `a kept read grant is released once its import ends, or when it is closed before importing`() = runTest(dispatcher) {
        val released = documents.archivesReleased

        viewModel.open(archiveUri, "mix.zip", keptGrant = true)
        viewModel.importArchive(null)
        assertThat(released).isEmpty()
        scheduler.states.getValue("job-1").value = ArchiveJobState.Extracted("$musicTree/document/x", 1, 0)
        assertThat(released).containsExactly(archiveUri)

        viewModel.open("content://downloads/other.zip", "other.zip", keptGrant = true)
        viewModel.dismiss()
        viewModel.open("content://downloads/temporary.zip", "temporary.zip")
        viewModel.dismiss()
        assertThat(released).containsExactly(archiveUri, "content://downloads/other.zip").inOrder()
    }

    @Test
    fun `an import hidden behind another archive still finishes in the library`() = runTest(dispatcher) {
        viewModel.open(archiveUri, "mix.zip")
        viewModel.importArchive(null)
        viewModel.hide()

        viewModel.open("content://downloads/other.zip", "other.zip")
        scheduler.states.getValue("job-1").value = ArchiveJobState.Extracted("$musicTree/document/x", 1, 0)

        assertThat(finisher.calls).hasSize(1)
        assertThat(viewModel.step.value).isInstanceOf(ImportStep.Ready::class.java)
    }

    @Test
    fun `a double tap on Import extracts the archive once`() = runTest(dispatcher) {
        scheduler.gate = CompletableDeferred()
        viewModel.open(archiveUri, "mix.zip")
        val second = "Strong-Password-1".toCharArray()

        viewModel.importArchive("Strong-Password-1".toCharArray())
        viewModel.importArchive(second)
        checkNotNull(scheduler.gate).complete(Unit)

        assertThat(scheduler.extractions).hasSize(1)
        assertThat(viewModel.step.value).isEqualTo(ImportStep.Extracting("job-1", "Road Trip", null))
        assertThat(second.all { it == '\u0000' }).isTrue()
    }

    @Test
    fun `an import outlives its screen, finishing in the library and releasing its grants`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val owned = ViewModelProvider.create(store, viewModelFactory { initializer { newViewModel() } })[ImportArchiveViewModel::class]
        val imports = "$provider/tree/primary%3AMusic%2FImports"
        owned.open(archiveUri, "mix.zip", keptGrant = true)
        owned.chooseDestination(imports, keptGrant = true)
        owned.importArchive(null)

        store.clear() // the activity is gone for good
        scheduler.states.getValue("job-1").value = ArchiveJobState.Extracted("$imports/document/x", 1, 0)

        assertThat(finisher.calls).containsExactly(imports to "$imports/document/x")
        assertThat(documents.archivesReleased).containsExactly(archiveUri)
        assertThat(documents.foldersReleased).containsExactly(imports)
    }

    @Test
    fun `a picked folder's grant is given back when it is not used, and kept when it is`() = runTest(dispatcher) {
        val first = "$provider/tree/primary%3AMusic%2FFirst"
        val second = "$provider/tree/primary%3AMusic%2FSecond"
        viewModel.open(archiveUri, "mix.zip")

        viewModel.chooseDestination(first, keptGrant = true)
        viewModel.chooseDestination(second, keptGrant = true)
        assertThat(documents.foldersReleased).containsExactly(first)

        viewModel.dismiss()
        assertThat(documents.foldersReleased).containsExactly(first, second).inOrder()

        // A library folder's own grant and the converter's output folder are never given back.
        viewModel.open(archiveUri, "mix.zip")
        viewModel.chooseDestination(musicTree, keptGrant = true)
        viewModel.dismiss()
        outputRoot.root.value = null
        viewModel.open(archiveUri, "mix.zip")
        viewModel.chooseDestination(first, keptGrant = true)
        viewModel.dismiss()
        assertThat(documents.foldersReleased).containsExactly(first, second).inOrder()
    }

    @Test
    fun `a folder another running import writes to keeps its grant`() = runTest(dispatcher) {
        val imports = "$provider/tree/primary%3AMusic%2FImports"
        viewModel.open(archiveUri, "mix.zip")
        viewModel.chooseDestination(imports, keptGrant = true)
        viewModel.importArchive(null)
        viewModel.hide()

        viewModel.open("content://downloads/other.zip", "other.zip")
        viewModel.chooseDestination(imports, keptGrant = true)
        viewModel.dismiss()
        assertThat(documents.foldersReleased).isEmpty()

        scheduler.states.getValue("job-1").value = ArchiveJobState.Extracted("$imports/document/x", 1, 0)
        assertThat(documents.foldersReleased).containsExactly(imports)
    }

    @Test
    fun `a job that is no longer known ends the import as unexpected`() = runTest(dispatcher) {
        viewModel.open(archiveUri, "mix.zip")
        viewModel.importArchive(null)

        scheduler.states.getValue("job-1").value = ArchiveJobState.Unknown

        assertThat(viewModel.step.value).isEqualTo(ImportStep.Failed(ArchiveFailure.Unexpected))
    }

    @Test
    fun `an archive found to need a password asks for one`() = runTest(dispatcher) {
        importer.inspection = ArchiveOutcome.Done(ArchiveSummary(encrypted = false, manifest = null))
        viewModel.open(archiveUri, "mix.zip")
        viewModel.importArchive(null)

        scheduler.states.getValue("job-1").value = ArchiveJobState.Failed(ArchiveFailure.PasswordRequired)

        val step = viewModel.step.value as ImportStep.Ready
        assertThat(step.summary.encrypted).isTrue()
        assertThat(step.canImport(passwordTyped = false)).isFalse()
    }

    @Test
    fun `without a name the archive's own is read off the main thread`() = runTest(dispatcher) {
        documents.names[archiveUri] = "Road Trip.zip"
        viewModel.open(archiveUri)
        assertThat((viewModel.step.value as ImportStep.Ready).fileName).isEqualTo("Road Trip.zip")

        viewModel.open("content://downloads/document/42")
        assertThat((viewModel.step.value as ImportStep.Ready).fileName).isEqualTo("42")
    }

    @Test
    fun `the name shown is the manifest's, or the file's for another ZIP`() {
        assertThat(ready().title).isEqualTo("Road Trip")
        assertThat(ready(summary = ArchiveSummary(true, null)).title).isEqualTo("mix.zip")
    }

    @Test
    fun `a picked folder is named after its path inside the storage`() {
        assertThat(destinationName("$provider/tree/primary%3AMusic%2FImports")).isEqualTo("Music/Imports")
        assertThat(destinationName("$provider/tree/1A2B-3C4D%3ASonora")).isEqualTo("Sonora")
        assertThat(destinationName("$provider/tree/primary%3A")).isEqualTo("primary")
    }

    private class FakeImporter(var inspection: ArchiveOutcome<ArchiveSummary>) : ArchiveImporter {
        override suspend fun inspect(archiveUri: String) = inspection
        override suspend fun extract(
            archiveUri: String,
            destinationFolderUri: String,
            password: CharArray?,
            onProgress: ArchiveProgressListener,
        ): ArchiveOutcome<ExtractedArchive> = error("extraction runs as a job")
    }

    private class FakeScheduler : ArchiveJobScheduler {
        val extractions = mutableListOf<Pair<String, String>>()
        val passwords = mutableListOf<CharArray?>()
        val cancelled = mutableListOf<String>()
        val states = mutableMapOf<String, MutableStateFlow<ArchiveJobState>>()

        override suspend fun compress(request: CompressionRequest, password: CharArray?): String = error("unused")

        var gate: CompletableDeferred<Unit>? = null

        override suspend fun extract(archiveUri: String, destinationFolderUri: String, password: CharArray?): String {
            gate?.await()
            extractions += archiveUri to destinationFolderUri
            passwords += password
            return "job-${states.size + 1}".also { states[it] = MutableStateFlow(ArchiveJobState.Queued) }
        }

        override suspend fun cancel(jobId: String) {
            cancelled += jobId
        }

        override fun state(jobId: String): Flow<ArchiveJobState> = states.getValue(jobId)
    }

    private class FakeOutputRoot(initial: OutputRootFolder?) : ConversionOutputRootStore {
        override val root = MutableStateFlow(initial)
        val set = mutableListOf<String>()
        override suspend fun set(treeUri: String): OutputRootFolder {
            set += treeUri
            return OutputRootFolder(treeUri, "Music").also { root.value = it }
        }
    }

    private class FakeLibraryFolders(private val existing: List<LibraryFolder>) : LibraryFolderRepository {
        override fun folders(): Flow<List<LibraryFolder>> = flowOf(existing)
        override suspend fun add(treeUri: String): AddFolderResult = error("the finisher adds folders")
        override suspend fun remove(treeUri: String) = Unit
    }

    private class FakeDocuments : ImportDocuments {
        val names = mutableMapOf<String, String>()
        val archivesReleased = mutableListOf<String>()
        val foldersReleased = mutableListOf<String>()
        override suspend fun displayName(uri: String): String? = names[uri]
        override fun releaseArchive(uri: String) {
            archivesReleased += uri
        }
        override fun releaseFolder(treeUri: String) {
            foldersReleased += treeUri
        }
    }

    private class FakeRecorder : ImportRecorder {
        val songs = listOf(ImportedSong("p/Music/Road Trip/1.flac", "One", "A"), ImportedSong("p/Music/Road Trip/2.flac", "Two", "B"))
        val recorded = RecordedImport(1, songs)
        val records = mutableListOf<Triple<String, String, String>>()
        val playlists = mutableListOf<Pair<Long, Long>>()
        var fails = false
        override suspend fun record(archiveName: String, destinationTreeUri: String, folderUri: String): RecordedImport {
            if (fails) throw IllegalStateException("The database is full")
            records += Triple(archiveName, destinationTreeUri, folderUri)
            return recorded
        }
        override suspend fun playlistMade(importId: Long, playlistId: Long) {
            playlists += importId to playlistId
        }
    }

    private class FakeFinisher : ImportFinisher {
        val outcome = ImportOutcome(DestinationCoverage.IN_LIBRARY, "Road Trip", playlistSongs = 3, missing = 0, playlistId = 7)
        val joined = outcome.copy(coverage = DestinationCoverage.JOINS_LIBRARY)
        val calls = mutableListOf<Pair<String, String>>()
        val added = mutableListOf<Pair<String, String>>()
        var addFails = false
        override suspend fun finish(destinationTreeUri: String, extractedFolderUri: String): ImportOutcome {
            calls += destinationTreeUri to extractedFolderUri
            return outcome
        }
        override suspend fun addAndFinish(folderTreeUri: String, extractedFolderUri: String): ImportOutcome {
            if (addFails) throw IllegalStateException("The library is not writable")
            added += folderTreeUri to extractedFolderUri
            return joined
        }
    }
}
