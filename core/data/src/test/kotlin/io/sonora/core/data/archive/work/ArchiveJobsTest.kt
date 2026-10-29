package io.sonora.core.data.archive.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveCompressor
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveImporter
import io.sonora.core.domain.archive.ArchiveJobState
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveOutcome
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.archive.ArchiveProgressListener
import io.sonora.core.domain.archive.ArchiveSummary
import io.sonora.core.domain.archive.CompressedArchive
import io.sonora.core.domain.archive.CompressionRequest
import io.sonora.core.domain.archive.ExtractedArchive
import io.sonora.core.domain.archive.ExtractedFile
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.port.LibraryScanner
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The archive workers and their scheduler: inputs without secrets, results, and the password's life. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArchiveJobsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val passwords = ArchivePasswords()
    private lateinit var cache: ArchiveCache
    private val notifications = ArchiveNotifications(context)
    private val request = CompressionRequest(ArchiveKind.PLAYLIST, "Mix", emptyList())

    /** Records what it was given and answers [outcome]. */
    private inner class FakeCompressor : ArchiveCompressor {
        var password: String? = null
        var outcome: ArchiveOutcome<CompressedArchive>? = null

        /** When set, the compression waits for it after its first report; [started] says it got there. */
        var hang: CompletableDeferred<Unit>? = null
        var started: CompletableDeferred<Unit>? = null

        override suspend fun compress(
            request: CompressionRequest,
            password: CharArray?,
            outputPath: String,
            onProgress: ArchiveProgressListener,
        ): ArchiveOutcome<CompressedArchive> {
            this.password = password?.let(::String)
            onProgress(ArchiveProgress(1, 2, 10, 20, "a.flac"))
            hang?.let { gate ->
                checkNotNull(started).complete(Unit)
                gate.await()
            }
            return outcome ?: ArchiveOutcome.Done(CompressedArchive(outputPath, File(outputPath).name, 20, 2, password != null))
        }
    }

    private inner class FakeImporter : ArchiveImporter {
        var password: String? = null

        override suspend fun inspect(archiveUri: String): ArchiveOutcome<ArchiveSummary> = error("unused")

        override suspend fun extract(
            archiveUri: String,
            destinationFolderUri: String,
            password: CharArray?,
            onProgress: ArchiveProgressListener,
        ): ArchiveOutcome<ExtractedArchive> {
            this.password = password?.let(::String)
            val files = listOf(ExtractedFile("Mix/01.flac", "$destinationFolderUri/01.flac"))
            return ArchiveOutcome.Done(ExtractedArchive("$destinationFolderUri/Mix", files, null, listOf("cover.jpg")))
        }
    }

    private class CountingScanner : LibraryScanner {
        var requests = 0
        override val progress: StateFlow<ScanProgress> get() = MutableStateFlow(ScanProgress.Idle)

        override fun requestScan() {
            requests++
        }
    }

    private val compressor = FakeCompressor()
    private val importer = FakeImporter()
    private val scanner = CountingScanner()

    /** Builds the real workers; the scheduler test also records the input data each job was started with. */
    private val inputs = mutableListOf<Data>()
    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
            inputs += workerParameters.inputData
            return when (workerClassName) {
                CompressArchiveWorker::class.java.name ->
                    CompressArchiveWorker(appContext, workerParameters, compressor, passwords, cache, notifications)
                else -> ExtractArchiveWorker(appContext, workerParameters, importer, passwords, notifications, scanner)
            }
        }
    }

    @Before
    fun setUp() {
        cache = ArchiveCache(temp.newFolder("archives"))
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    private fun compressWorker(jobId: String, encrypted: Boolean) = TestListenableWorkerBuilder<CompressArchiveWorker>(context)
        .setInputData(ArchiveWork.compressInput(jobId, encrypted))
        .setWorkerFactory(factory)
        .build()

    private fun extractWorker(jobId: String, encrypted: Boolean) = TestListenableWorkerBuilder<ExtractArchiveWorker>(context)
        .setInputData(ArchiveWork.extractInput(jobId, "content://downloads/Mix.zip", "content://tree/Music", encrypted))
        .setWorkerFactory(factory)
        .build()

    private fun stateOf(result: ListenableWorker.Result, succeeded: Boolean) = ArchiveWork.stateOf(
        if (succeeded) androidx.work.WorkInfo.State.SUCCEEDED else androidx.work.WorkInfo.State.FAILED,
        result.outputData,
        Data.EMPTY,
    )

    @Test
    fun `an encrypted job whose password was lost asks for it again`() = runBlocking {
        cache.writeRequest("lost", request)
        val result = compressWorker("lost", encrypted = true).doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        assertThat(stateOf(result, succeeded = false)).isEqualTo(ArchiveJobState.Failed(ArchiveFailure.PasswordNeededAgain))
        assertThat(compressor.password).isNull()

        val extract = extractWorker("lost-too", encrypted = true).doWork()
        assertThat(stateOf(extract, succeeded = false)).isEqualTo(ArchiveJobState.Failed(ArchiveFailure.PasswordNeededAgain))
    }

    @Test
    fun `a compression gets its password from memory and wipes it at the end`() = runBlocking {
        val secret = "Correct-Horse-42".toCharArray()
        passwords.put("job", secret)
        cache.writeRequest("job", request)

        val result = compressWorker("job", encrypted = true).doWork()

        assertThat(compressor.password).isEqualTo("Correct-Horse-42")
        assertThat(passwords.get("job")).isNull()
        assertThat(secret.all { it == '\u0000' }).isTrue()
        val state = stateOf(result, succeeded = true) as ArchiveJobState.Compressed
        assertThat(state.archive.path).isEqualTo(cache.archiveFile("job", "Mix").path)
        assertThat(state.archive.encrypted).isTrue()
    }

    @Test
    fun `a failed compression reports its failure and wipes the password`() = runBlocking {
        passwords.put("job", "Correct-Horse-42".toCharArray())
        cache.writeRequest("job", request)
        compressor.outcome = ArchiveOutcome.Failed(ArchiveFailure.UnreadableSource("Intro"))

        val result = compressWorker("job", encrypted = true).doWork()

        assertThat(stateOf(result, succeeded = false)).isEqualTo(ArchiveJobState.Failed(ArchiveFailure.UnreadableSource("Intro")))
        assertThat(passwords.get("job")).isNull()
    }

    @Test
    fun `an extraction asks the library for a scan`() = runBlocking {
        passwords.put("ex", "Correct-Horse-42".toCharArray())
        val result = extractWorker("ex", encrypted = true).doWork()

        assertThat(importer.password).isEqualTo("Correct-Horse-42")
        assertThat(stateOf(result, succeeded = true))
            .isEqualTo(ArchiveJobState.Extracted("content://tree/Music/Mix", fileCount = 1, skippedCount = 1))
        assertThat(scanner.requests).isEqualTo(1)
        assertThat(passwords.get("ex")).isNull()
    }

    @Test
    fun `the scheduler keeps the password out of the job's data and reports the result`() = runBlocking {
        val scheduler = WorkManagerArchiveScheduler(context, passwords, cache)
        val jobId = scheduler.compress(request, "Correct-Horse-42".toCharArray())

        val state = withTimeout(5_000) { scheduler.state(jobId).first { it is ArchiveJobState.Compressed } }

        assertThat((state as ArchiveJobState.Compressed).archive.encrypted).isTrue()
        assertThat(compressor.password).isEqualTo("Correct-Horse-42")
        val values = inputs.single().keyValueMap.values
        assertThat(values.none { it.toString().contains("Correct-Horse-42") }).isTrue()
        assertThat(values.none { it is CharArray || it is Array<*> }).isTrue()
    }

    @Test
    fun `cancelling a job forgets its password`() = runBlocking {
        val scheduler = WorkManagerArchiveScheduler(context, passwords, cache)
        passwords.put("waiting", "Correct-Horse-42".toCharArray())

        scheduler.cancel("waiting")

        assertThat(passwords.get("waiting")).isNull()
    }

    /** Runs a compression that waits until it is stopped with [reason], as WorkManager stops a running job. */
    private fun stoppedCompression(jobId: String, reason: Int) = runBlocking {
        passwords.put(jobId, "Correct-Horse-42".toCharArray())
        cache.writeRequest(jobId, request)
        compressor.hang = CompletableDeferred()
        compressor.started = CompletableDeferred()
        val worker = compressWorker(jobId, encrypted = true)
        val run = launch(Dispatchers.Default) { worker.doWork() }
        withTimeout(5_000) { checkNotNull(compressor.started).await() }

        worker.stop(reason)
        run.cancel()
        run.join()
    }

    @Test
    fun `a job the user cancels while it runs forgets its password`() {
        stoppedCompression("cancelled", WorkInfo.STOP_REASON_CANCELLED_BY_APP)

        assertThat(passwords.get("cancelled")).isNull()
    }

    @Test
    fun `a job the system stops keeps its password for the rerun`() {
        stoppedCompression("preempted", WorkInfo.STOP_REASON_PREEMPT)

        assertThat(passwords.get("preempted")?.let(::String)).isEqualTo("Correct-Horse-42")
    }

    @Test
    fun `a scheduled job is known as soon as its id is returned`() = runBlocking {
        val scheduler = WorkManagerArchiveScheduler(context, passwords, cache)
        val jobId = scheduler.extract("content://downloads/Mix.zip", "content://tree/Music", null)

        assertThat(scheduler.state(jobId).first()).isNotEqualTo(ArchiveJobState.Unknown)
    }

    @Test
    fun `an unknown job has no state`() = runBlocking {
        val scheduler = WorkManagerArchiveScheduler(context, passwords, cache)
        assertThat(scheduler.state("nobody").first()).isEqualTo(ArchiveJobState.Unknown)
    }
}
