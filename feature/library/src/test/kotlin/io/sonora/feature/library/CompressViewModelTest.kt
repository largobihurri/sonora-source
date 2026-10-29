package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveFiles
import io.sonora.core.domain.archive.ArchiveJobScheduler
import io.sonora.core.domain.archive.ArchiveJobState
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.archive.CompressedArchive
import io.sonora.core.domain.archive.CompressionRequest
import io.sonora.core.domain.model.FolderContents
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.FolderBrowser
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain, UnconfinedTestDispatcher
class CompressViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val tracks = listOf(libraryTrack(1, album = 10), libraryTrack(2, album = 10))
    private val scheduler = FakeArchiveScheduler()
    private val files = FakeArchiveFiles()
    private val archive = CompressedArchive("/cache/archives/job-1/Album 10.zip", "Album 10.zip", 2_048, 2, encrypted = false)
    private val albumTarget = CompressTarget(ArchiveKind.ALBUM, "Album 10", PlaylistSource.Album(10))
    private lateinit var viewModel: CompressViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = CompressViewModel(FakeTrackLibrary(tracks), FakeFolderBrowser(emptyList()), scheduler, files)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collectEvents(): MutableList<CompressEvent> {
        val events = mutableListOf<CompressEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.toList(events) }
        return events
    }

    @Test
    fun `Compress asks for a password with the target's songs`() = runTest(dispatcher) {
        viewModel.start(albumTarget)

        assertThat(viewModel.step.value).isEqualTo(CompressStep.Password(albumTarget, tracks))
    }

    @Test
    fun `a target without songs says so and asks nothing`() = runTest(dispatcher) {
        val events = collectEvents()

        viewModel.start(CompressTarget(ArchiveKind.ALBUM, "Empty", PlaylistSource.Album(99)))

        assertThat(viewModel.step.value).isNull()
        assertThat(events).containsExactly(CompressEvent.NoSongs)
    }

    @Test
    fun `songs that cannot be read are reported`() = runTest(dispatcher) {
        viewModel = CompressViewModel(FakeTrackLibrary(tracks), FailingFolderBrowser, scheduler, files)
        val events = collectEvents()

        viewModel.start(CompressTarget(ArchiveKind.PLAYLIST, "Rock", PlaylistSource.Folder(FolderRef("t", "Rock"))))

        assertThat(viewModel.step.value).isNull()
        assertThat(events).containsExactly(CompressEvent.ReadFailed)
    }

    @Test
    fun `without a password the job is scheduled with none, and its progress shows`() = runTest(dispatcher) {
        viewModel.start(albumTarget)

        viewModel.compress(null)

        assertThat(scheduler.requests).containsExactly(CompressionRequest(ArchiveKind.ALBUM, "Album 10", tracks))
        assertThat(scheduler.passwords).containsExactly(null)
        assertThat(viewModel.step.value).isEqualTo(CompressStep.Working("job-1", "Album 10", null))

        val progress = ArchiveProgress(1, 2, 10, 20, "01 - Song 1.flac")
        scheduler.states.getValue("job-1").value = ArchiveJobState.Running(progress)
        assertThat(viewModel.step.value).isEqualTo(CompressStep.Working("job-1", "Album 10", progress))
    }

    @Test
    fun `a typed password goes to the job, an empty one counts as none`() = runTest(dispatcher) {
        viewModel.start(albumTarget)
        viewModel.compress("Strong-Password-1".toCharArray())
        viewModel.start(albumTarget)
        viewModel.compress(CharArray(0))

        assertThat(scheduler.passwords.map { it?.let(::String) }).containsExactly("Strong-Password-1", null).inOrder()
    }

    @Test
    fun `a finished job shows the archive, a failed one its failure`() = runTest(dispatcher) {
        viewModel.start(albumTarget)
        viewModel.compress(null)
        scheduler.states.getValue("job-1").value = ArchiveJobState.Compressed(archive)
        assertThat(viewModel.step.value).isEqualTo(CompressStep.Finished(archive))

        viewModel.start(albumTarget)
        viewModel.compress(null)
        scheduler.states.getValue("job-2").value = ArchiveJobState.Failed(ArchiveFailure.UnreadableSource("Song 2"))
        assertThat(viewModel.step.value).isEqualTo(CompressStep.Failed(ArchiveFailure.UnreadableSource("Song 2")))
    }

    @Test
    fun `hiding the progress keeps following the job, which shows its result when done`() = runTest(dispatcher) {
        viewModel.start(albumTarget)
        viewModel.compress(null)

        viewModel.hide()
        assertThat(viewModel.step.value).isEqualTo(CompressStep.Working("job-1", "Album 10", null, hidden = true))

        scheduler.states.getValue("job-1").value = ArchiveJobState.Compressed(archive)
        assertThat(viewModel.step.value).isEqualTo(CompressStep.Finished(archive))
    }

    @Test
    fun `stopping a running job cancels it and closes the sheet`() = runTest(dispatcher) {
        viewModel.start(albumTarget)
        viewModel.compress(null)

        viewModel.stop()

        assertThat(scheduler.cancelled).containsExactly("job-1")
        assertThat(viewModel.step.value).isNull()
        scheduler.states.getValue("job-1").value = ArchiveJobState.Cancelled
        assertThat(viewModel.step.value).isNull()
    }

    @Test
    fun `a job cancelled from its notification closes the sheet`() = runTest(dispatcher) {
        viewModel.start(albumTarget)
        viewModel.compress(null)

        scheduler.states.getValue("job-1").value = ArchiveJobState.Cancelled

        assertThat(viewModel.step.value).isNull()
    }

    @Test
    fun `cancelling the password sheet schedules nothing`() = runTest(dispatcher) {
        viewModel.start(albumTarget)

        viewModel.dismiss()

        assertThat(viewModel.step.value).isNull()
        assertThat(scheduler.requests).isEmpty()
    }

    @Test
    fun `Save copies the archive and says whether it worked`() = runTest(dispatcher) {
        val events = collectEvents()
        viewModel.start(albumTarget)
        viewModel.compress(null)
        scheduler.states.getValue("job-1").value = ArchiveJobState.Compressed(archive)

        viewModel.save("content://docs/new.zip")
        files.works = false
        viewModel.save("content://docs/other.zip")

        assertThat(files.saved).containsExactly(archive.path to "content://docs/new.zip", archive.path to "content://docs/other.zip").inOrder()
        assertThat(events).containsExactly(CompressEvent.Saved, CompressEvent.SaveFailed).inOrder()
    }

    @Test
    fun `Share hands out the archive's shareable address`() = runTest(dispatcher) {
        viewModel.start(albumTarget)
        viewModel.compress(null)
        scheduler.states.getValue("job-1").value = ArchiveJobState.Compressed(archive)

        assertThat(viewModel.shareUri()).isEqualTo("content://share${archive.path}")
    }

    @Test
    fun `a double tap on Compress schedules one job`() = runTest(dispatcher) {
        scheduler.gate = CompletableDeferred()
        viewModel.start(albumTarget)
        val second = "Strong-Password-1".toCharArray()

        viewModel.compress("Strong-Password-1".toCharArray())
        viewModel.compress(second)
        checkNotNull(scheduler.gate).complete(Unit)

        assertThat(scheduler.requests).hasSize(1)
        assertThat(viewModel.step.value).isEqualTo(CompressStep.Working("job-1", "Album 10", null))
        assertThat(second.all { it == '\u0000' }).isTrue()
    }

    @Test
    fun `a job hidden behind a new Compress still shows its result once that sheet closes`() = runTest(dispatcher) {
        viewModel.start(albumTarget)
        viewModel.compress(null)
        viewModel.hide()

        val playlist = CompressTarget(ArchiveKind.PLAYLIST, "Rock", PlaylistSource.Album(10))
        viewModel.start(playlist)
        scheduler.states.getValue("job-1").value = ArchiveJobState.Compressed(archive)
        assertThat(viewModel.step.value).isEqualTo(CompressStep.Password(playlist, tracks))

        viewModel.dismiss()

        assertThat(viewModel.step.value).isEqualTo(CompressStep.Finished(archive))
    }

    @Test
    fun `two jobs both end in their own sheet, one after the other`() = runTest(dispatcher) {
        viewModel.start(albumTarget)
        viewModel.compress(null)
        viewModel.hide()
        viewModel.start(albumTarget)
        viewModel.compress(null)

        scheduler.states.getValue("job-1").value = ArchiveJobState.Failed(ArchiveFailure.NoMusic)
        assertThat(viewModel.step.value).isEqualTo(CompressStep.Working("job-2", "Album 10", null))
        scheduler.states.getValue("job-2").value = ArchiveJobState.Compressed(archive)
        assertThat(viewModel.step.value).isEqualTo(CompressStep.Finished(archive))

        viewModel.dismiss()
        assertThat(viewModel.step.value).isEqualTo(CompressStep.Failed(ArchiveFailure.NoMusic))
        viewModel.dismiss()
        assertThat(viewModel.step.value).isNull()
    }

    @Test
    fun `a job that is no longer known ends as unexpected instead of spinning`() = runTest(dispatcher) {
        viewModel.start(albumTarget)
        viewModel.compress(null)

        scheduler.states.getValue("job-1").value = ArchiveJobState.Unknown

        assertThat(viewModel.step.value).isEqualTo(CompressStep.Failed(ArchiveFailure.Unexpected))
    }

    @Test
    fun `an archive that cannot be shared gives no address`() = runTest(dispatcher) {
        files.shareFails = true
        viewModel.start(albumTarget)
        viewModel.compress(null)
        scheduler.states.getValue("job-1").value = ArchiveJobState.Compressed(archive)

        assertThat(viewModel.shareUri()).isNull()
        viewModel.dismiss()
        assertThat(viewModel.shareUri()).isNull()
    }

    private object FailingFolderBrowser : FolderBrowser {
        override fun roots(): Flow<List<FolderEntry>> = emptyFlow()
        override fun contents(folder: FolderRef): Flow<FolderContents> = emptyFlow()
        override suspend fun tracksUnder(folder: FolderRef): List<Track> = throw IOException("gone")
    }
}

/** Records scheduled jobs ("job-1", "job-2", …); each job's state is set by the test. */
internal class FakeArchiveScheduler : ArchiveJobScheduler {
    val requests = mutableListOf<CompressionRequest>()
    val extractions = mutableListOf<Pair<String, String>>()
    val passwords = mutableListOf<CharArray?>()
    val cancelled = mutableListOf<String>()
    val states = mutableMapOf<String, MutableStateFlow<ArchiveJobState>>()

    private fun newJob(): String = "job-${states.size + 1}".also { states[it] = MutableStateFlow(ArchiveJobState.Queued) }

    /** When set, scheduling waits for it, as the real scheduler waits for its disk and WorkManager. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun compress(request: CompressionRequest, password: CharArray?): String {
        gate?.await()
        requests += request
        passwords += password
        return newJob()
    }

    override suspend fun extract(archiveUri: String, destinationFolderUri: String, password: CharArray?): String {
        extractions += archiveUri to destinationFolderUri
        passwords += password
        return newJob()
    }

    override suspend fun cancel(jobId: String) {
        cancelled += jobId
    }

    override fun state(jobId: String): Flow<ArchiveJobState> = states[jobId] ?: MutableStateFlow(ArchiveJobState.Unknown)
}

internal class FakeArchiveFiles : ArchiveFiles {
    var works = true
    val saved = mutableListOf<Pair<String, String>>()

    var shareFails = false

    override fun shareUri(archive: CompressedArchive): String {
        require(!shareFails) { "Failed to find configured root that contains ${archive.path}" }
        return "content://share${archive.path}"
    }

    override suspend fun saveTo(archive: CompressedArchive, documentUri: String): Boolean {
        saved += archive.path to documentUri
        return works
    }
}
