package io.sonora.core.data.conversion.work

import android.content.Context
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.conversion.RoomConversionBatchRepository
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.domain.conversion.ConversionBudget
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.batch.ConversionBatchRunner
import io.sonora.core.domain.conversion.batch.OriginalReplacer
import io.sonora.core.domain.conversion.batch.ReplaceBatchOriginals
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.model.ConversionRequest
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.domain.port.AudioConverter
import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConversionBudgetSource
import io.sonora.core.domain.port.ConversionFiles
import io.sonora.core.domain.port.ConversionOutputRootStore
import io.sonora.core.domain.port.OutputRootFolder
import io.sonora.core.domain.port.SourceFolderAccess
import io.sonora.core.domain.port.StoredDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The worker's contract with WorkManager: its input, its result, and that a batch never stays running. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversionBatchWorkerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var db: LibraryDatabase
    private lateinit var rooms: RoomConversionBatchRepository
    private lateinit var repository: BreakingRepository

    /** Room, except that reading the items fails [failingReads] times, as a broken database would. */
    private class BreakingRepository(delegate: ConversionBatchRepository) : ConversionBatchRepository by delegate {
        private val rooms = delegate
        var failingReads = 0
        /** Runs once, inside the next read of the items: holds the run there, e.g. until the work is stopped. */
        var holdNextRead: (suspend () -> Unit)? = null

        override suspend fun getItems(batchId: Long): List<ConversionBatchItem> {
            holdNextRead?.let { hold ->
                holdNextRead = null
                hold()
            }
            return if (failingReads-- > 0) throw IllegalStateException("database disk image is malformed") else rooms.getItems(batchId)
        }
    }

    /** Storage holding the batch folder, empty. Nothing else is reached by these tests. */
    private object EmptyFolders : ConversionFiles {
        override suspend fun children(folderUri: String): List<StoredDocument> = emptyList()
        override suspend fun findOrCreateFolder(parentUri: String, name: String) = error("unused")
        override suspend fun createFolder(parentUri: String, name: String) = error("unused")
        override suspend fun createFile(folderUri: String, name: String, mimeType: String) = error("unused")
        override suspend fun rename(documentUri: String, name: String) = error("unused")
        override suspend fun copy(fromUri: String, toUri: String) = error("unused")
        override suspend fun delete(documentUri: String) = true
    }

    private object NoConverter : AudioConverter {
        override suspend fun convert(request: ConversionRequest, onProgress: (Float) -> Unit): ConversionResult = error("unused")
        override suspend fun verify(outputUri: String, expected: ConversionResult): OutputVerification = error("unused")
    }

    private object NoBudgets : ConversionBudgetSource {
        override fun budgets(mode: PerformanceMode): Flow<ConversionBudget> = error("unused")
    }

    private object NoRoot : ConversionOutputRootStore {
        override val root = MutableStateFlow<OutputRootFolder?>(null)
        override suspend fun set(treeUri: String) = error("unused")
    }

    private object NoAccess : SourceFolderAccess {
        override suspend fun persistWrite(treeUri: String) = false
        override suspend fun canWrite(folderUri: String) = false
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        rooms = RoomConversionBatchRepository(db.conversionHistoryDao())
        repository = BreakingRepository(rooms)
    }

    @After
    fun tearDown() = db.close()

    private fun worker(batchId: Long, task: String = ConversionWork.TASK_CONVERT): ConversionBatchWorker {
        val replacer = OriginalReplacer(EmptyFolders, NoAccess, NoConverter, repository)
        val runner = ConversionBatchRunner(repository, EmptyFolders, NoRoot, NoConverter, NoBudgets, replacer)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                ConversionBatchWorker(
                    appContext, workerParameters, repository, runner, ReplaceBatchOriginals(repository, replacer),
                    ConversionNotifications(appContext),
                )
        }
        return TestListenableWorkerBuilder<ConversionBatchWorker>(context)
            .setInputData(workDataOf(ConversionWork.KEY_BATCH_ID to batchId, ConversionWork.KEY_TASK to task))
            .setWorkerFactory(factory)
            .build()
    }

    private fun batch(status: BatchStatus, folder: String? = null) = runBlocking {
        rooms.create(
            ConversionBatch(
                id = 0,
                profileId = ConversionProfiles.XDJ_700.id,
                performanceMode = PerformanceMode.NORMAL,
                createdAtMs = 1,
                outputFolderName = "2026-10-03 14.05",
                outputFolderUri = folder,
                overwriteOriginals = false,
                status = status,
            ),
            listOf(BatchSource("content://a.flac", "a.flac", null), BatchSource("content://b.flac", "b.flac", null)),
        )
    }

    @Test
    fun `a batch that no longer exists fails the work`() = runBlocking<Unit> {
        assertThat(worker(404).doWork()).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `a conversion re-run after its batch ended succeeds without touching it`() = runBlocking<Unit> {
        val id = batch(BatchStatus.COMPLETED)

        assertThat(worker(id).doWork()).isEqualTo(ListenableWorker.Result.success())

        assertThat(rooms.getBatch(id)!!.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(rooms.getItems(id).map { it.state }.toSet()).containsExactly(BatchItemState.PENDING)
    }

    @Test
    fun `a replacement with nothing to replace succeeds`() = runBlocking<Unit> {
        val id = batch(BatchStatus.COMPLETED)

        assertThat(worker(id, ConversionWork.TASK_REPLACE).doWork()).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `an unexpected failure fails the work and ends the batch as failed, never running`() = runBlocking<Unit> {
        val id = batch(BatchStatus.RUNNING, folder = "content://batch-folder")
        repository.failingReads = 1

        assertThat(worker(id).doWork()).isEqualTo(ListenableWorker.Result.failure())

        val batch = rooms.getBatch(id)!!
        assertThat(batch.status).isEqualTo(BatchStatus.FAILED)
        assertThat(batch.failureReason).contains("database disk image is malformed")
        assertThat(rooms.getItems(id).map { it.state }.toSet()).containsExactly(BatchItemState.SKIPPED)
    }

    /** Starts the work on a queued batch, stops it with [reason] while it runs, and returns the batch once ended. */
    private fun stopWhileRunning(reason: Int): ConversionBatch = runBlocking {
        val id = batch(BatchStatus.QUEUED, folder = "content://batch-folder")
        val running = CompletableDeferred<Unit>()
        repository.holdNextRead = {
            running.complete(Unit)
            awaitCancellation()
        }
        val worker = worker(id)

        // WorkManager stops a worker by recording why, then cancelling the coroutine running doWork().
        val work = async(Dispatchers.Default) { worker.doWork() }
        withTimeout(5_000) { running.await() }
        assertThat(rooms.getBatch(id)!!.status).isEqualTo(BatchStatus.RUNNING)
        worker.stop(reason)
        work.cancel()

        withTimeout(5_000) {
            while (!rooms.getBatch(id)!!.status.isFinal) delay(10)
        }
        rooms.getBatch(id)!!
    }

    @Test
    fun `a stop by the user's cancel ends the running batch as cancelled`() {
        val batch = stopWhileRunning(WorkInfo.STOP_REASON_CANCELLED_BY_APP)

        assertThat(batch.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(runBlocking { rooms.getItems(batch.id) }.map { it.state }.toSet()).containsExactly(BatchItemState.SKIPPED)
    }

    @Test
    fun `a stop by the system's time limit for media processing ends the running batch as timed out`() {
        val batch = stopWhileRunning(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT)

        assertThat(batch.status).isEqualTo(BatchStatus.TIMED_OUT)
        assertThat(runBlocking { rooms.getItems(batch.id) }.map { it.state }.toSet()).containsExactly(BatchItemState.SKIPPED)
    }

    @Test
    fun `any other stop leaves the batch to resume when the work runs again`() {
        val others = listOf(
            WorkInfo.STOP_REASON_PREEMPT,
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING,
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
            WorkInfo.STOP_REASON_UNKNOWN,
        )

        assertThat(others.map(ConversionBatchWorker::endingStatusFor).toSet()).containsExactly(null)
        assertThat(ConversionBatchWorker.endingStatusFor(WorkInfo.STOP_REASON_CANCELLED_BY_APP)).isEqualTo(BatchStatus.CANCELLED)
        assertThat(ConversionBatchWorker.endingStatusFor(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT)).isEqualTo(BatchStatus.TIMED_OUT)
    }
}
