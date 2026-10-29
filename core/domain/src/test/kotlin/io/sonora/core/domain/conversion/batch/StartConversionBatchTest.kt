package io.sonora.core.domain.conversion.batch

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.port.ConversionBatchScheduler
import io.sonora.core.domain.port.ConversionSourceExpander
import io.sonora.core.domain.port.ExpandedSources
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StartConversionBatchTest {

    private val madrid = ZoneId.of("Europe/Madrid")
    private val clock = Clock.fixed(LocalDateTime.parse("2026-10-03T14:05:30").atZone(madrid).toInstant(), madrid)
    private val repository = FakeRepository()
    private val root = FakeOutputRoot("tree:usb")
    private val access = FakeAccess(writable = setOf("tree:music"))
    private val persisted = mutableListOf<String>()
    private val scheduler = object : ConversionBatchScheduler {
        val enqueued = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        /** Whether the batch's job is running when cancelled. */
        var jobRunning = false
        override fun enqueueConversion(batchId: Long) {
            enqueued += batchId
        }
        override fun enqueueReplacement(batchId: Long) = Unit
        override suspend fun cancel(batchId: Long): Boolean {
            cancelled += batchId
            return jobRunning
        }
        override fun progress(batchId: Long): Flow<BatchProgress?> = flowOf(null)
    }
    private var found = listOf(BatchSource("doc:a", "A.flac", "tree:music"), BatchSource("doc:b", "B.mp3", null))
    private var skippedFolders = 0
    private val expander = ConversionSourceExpander { ExpandedSources(found, skippedFolders) }

    private fun start() = StartConversionBatch(
        expander,
        repository,
        root,
        object : io.sonora.core.domain.port.SourceFolderAccess by access {
            override suspend fun persistWrite(treeUri: String) = access.persistWrite(treeUri).also { persisted += treeUri }
        },
        scheduler,
        clock,
    )

    private val picks = listOf(PickedSource.Folder("tree:music"), PickedSource.File("doc:b"))

    @Test
    fun `a batch is stored with its sources and scheduled`() = runBlocking<Unit> {
        val result = start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.LOW_RESOURCES, overwriteOriginals = true)

        val id = (result as StartBatchResult.Started).batchId
        assertThat(scheduler.enqueued).containsExactly(id)
        val batch = repository.getBatch(id)!!
        assertThat(batch.status).isEqualTo(BatchStatus.QUEUED)
        assertThat(batch.profileId).isEqualTo("xdj-700")
        assertThat(batch.performanceMode).isEqualTo(PerformanceMode.LOW_RESOURCES)
        assertThat(batch.overwriteOriginals).isTrue()
        assertThat(batch.createdAtMs).isEqualTo(clock.millis())
        assertThat(batch.outputFolderName).isEqualTo("2026-10-03 14.05")
        assertThat(repository.items(id).first().map { it.sourceUri to it.sourceParentUri })
            .containsExactly("doc:a" to "tree:music", "doc:b" to null).inOrder()
    }

    @Test
    fun `each file stores the profile chosen for it while the batch keeps the batch-wide one`() = runBlocking<Unit> {
        found = listOf(
            BatchSource("doc:a", "A.flac", "tree:music", pickUri = "tree:music"),
            BatchSource("doc:b", "B.mp3", null, pickUri = "doc:b"),
        )

        val result = start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, overwriteOriginals = false) { source ->
            if (source.pickUri == "doc:b") ConversionProfiles.WATCH_OPUS else ConversionProfiles.XDJ_700
        }

        val id = (result as StartBatchResult.Started).batchId
        assertThat(repository.getBatch(id)!!.profileId).isEqualTo("xdj-700")
        assertThat(repository.items(id).first().map { it.sourceUri to it.profileId })
            .containsExactly("doc:a" to "xdj-700", "doc:b" to "watch-opus").inOrder()
    }

    @Test
    fun `without a choice per file every file takes the batch-wide profile`() = runBlocking<Unit> {
        val result = start()(picks, ConversionProfiles.WATCH_OPUS, PerformanceMode.NORMAL, overwriteOriginals = false)

        val id = (result as StartBatchResult.Started).batchId
        assertThat(repository.items(id).first().map { it.profileId }).containsExactly("watch-opus", "watch-opus")
    }

    @Test
    fun `write access is kept for picked folders, so originals can be replaced now or later`() = runBlocking<Unit> {
        start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, overwriteOriginals = false)

        assertThat(persisted).containsExactly("tree:music")
    }

    @Test
    fun `nothing starts without an output folder`() = runBlocking<Unit> {
        root.root.value = null

        val result = start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, overwriteOriginals = false)

        assertThat(result).isEqualTo(StartBatchResult.NoOutputFolder)
        assertThat(repository.batchRows).isEmpty()
        assertThat(scheduler.enqueued).isEmpty()
    }

    @Test
    fun `nothing starts when the picks hold no audio`() = runBlocking<Unit> {
        found = emptyList()

        val result = start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, overwriteOriginals = false)

        assertThat(result).isEqualTo(StartBatchResult.NoAudioFiles)
        assertThat(repository.batchRows).isEmpty()
    }

    @Test
    fun `the history groups stored batches by day`() = runBlocking<Unit> {
        start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, overwriteOriginals = false)

        val groups = ObserveConversionHistory(repository, clock)().first()

        assertThat(groups.map { it.section }).containsExactly(HistorySection.TODAY)
        assertThat(groups.single().batches.single().counts.total).isEqualTo(2)
    }

    private fun cancel(): CancelConversionBatch {
        val files = FakeFiles()
        val converter = FakeConverter(files)
        val runner = ConversionBatchRunner(
            repository, files, root, converter, FakeBudgets(), OriginalReplacer(files, access, converter, repository),
        )
        return CancelConversionBatch(scheduler, runner)
    }

    @Test
    fun `cancelling a batch whose job never started ends it here`() = runBlocking<Unit> {
        val id = (start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, false) as StartBatchResult.Started).batchId

        cancel()(id)

        assertThat(scheduler.cancelled).containsExactly(id)
        val batch = repository.getBatch(id)!!
        assertThat(batch.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(batch.counts.skipped).isEqualTo(2)
    }

    @Test
    fun `cancelling a running batch leaves the ending to its job`() = runBlocking<Unit> {
        val id = (start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, false) as StartBatchResult.Started).batchId
        repository.updateBatch(repository.getBatch(id)!!.copy(status = BatchStatus.RUNNING))
        scheduler.jobRunning = true

        cancel()(id)

        assertThat(scheduler.cancelled).containsExactly(id)
        assertThat(repository.getBatch(id)!!.status).isEqualTo(BatchStatus.RUNNING)
        assertThat(repository.getBatch(id)!!.counts.pending).isEqualTo(2)
    }

    @Test
    fun `cancelling a claimed batch whose job is not running ends it here`() = runBlocking<Unit> {
        // The system stopped the job mid-batch; WorkManager would run it again, but cancelling drops that run.
        val id = (start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, false) as StartBatchResult.Started).batchId
        repository.updateBatch(repository.getBatch(id)!!.copy(status = BatchStatus.RUNNING))
        scheduler.jobRunning = false

        cancel()(id)

        val batch = repository.getBatch(id)!!
        assertThat(batch.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(batch.counts.skipped).isEqualTo(2)
    }

    @Test
    fun `cancelling a batch that already ended changes nothing`() = runBlocking<Unit> {
        val id = (start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, false) as StartBatchResult.Started).batchId
        repository.transition(id, setOf(BatchStatus.QUEUED), BatchStatus.COMPLETED)

        cancel()(id)

        assertThat(repository.getBatch(id)!!.status).isEqualTo(BatchStatus.COMPLETED)
    }

    @Test
    fun `subfolders that could not be read are reported with the started batch`() = runBlocking<Unit> {
        skippedFolders = 2

        val result = start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, overwriteOriginals = false)

        assertThat((result as StartBatchResult.Started).skippedFolders).isEqualTo(2)
    }

    @Test
    fun `a library song whose library folder is writable can replace its original`() = runBlocking<Unit> {
        found = listOf(BatchSource("doc:song", "Song.flac", "dir:music/album"))
        access.writable = setOf("dir:music/album")

        val id = (start()(listOf(PickedSource.Track("doc:song")), ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, true)
            as StartBatchResult.Started).batchId

        assertThat(repository.items(id).first().single().sourceParentUri).isEqualTo("dir:music/album")
    }

    @Test
    fun `a library song in a read-only library folder converts but cannot replace its original`() = runBlocking<Unit> {
        found = listOf(BatchSource("doc:song", "Song.flac", "dir:music/album"), BatchSource("doc:a", "A.flac", "tree:music"))
        access.writable = setOf("tree:music")

        val picks = listOf(PickedSource.Track("doc:song"), PickedSource.Folder("tree:music"))
        val id = (start()(picks, ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, true) as StartBatchResult.Started).batchId

        assertThat(repository.items(id).first().map { it.sourceUri to it.sourceParentUri })
            .containsExactly("doc:song" to null, "doc:a" to "tree:music").inOrder()
    }

    @Test
    fun `library songs never ask for more access than the library folder already has`() = runBlocking<Unit> {
        found = listOf(BatchSource("doc:song", "Song.flac", "dir:music/album"))

        start()(listOf(PickedSource.Track("doc:song")), ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, true)

        assertThat(persisted).isEmpty()
    }

    @Test
    fun `a write check that fails leaves the library song converting without replacing`() = runBlocking<Unit> {
        found = listOf(BatchSource("doc:song", "Song.flac", "dir:music/album"))
        access.writable = setOf("dir:music/album")
        access.failure = SecurityException("revoked")

        val id = (start()(listOf(PickedSource.Track("doc:song")), ConversionProfiles.XDJ_700, PerformanceMode.NORMAL, true)
            as StartBatchResult.Started).batchId

        assertThat(repository.items(id).first().single().sourceParentUri).isNull()
    }
}
