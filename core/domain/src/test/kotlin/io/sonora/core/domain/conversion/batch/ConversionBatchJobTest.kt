package io.sonora.core.domain.conversion.batch

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.batch.ConversionBatchJob.Outcome
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

class ConversionBatchJobTest {

    private val files = FakeFiles()
    private val outputRoot = files.folder("USB")
    private val music = files.folder("Music")
    private val repository = FakeRepository()
    private val converter = FakeConverter(files)
    private val access = FakeAccess(writable = setOf(music))
    private val time = TestTimeSource()
    private val replacer = OriginalReplacer(files, access, converter, repository)
    private val runner = ConversionBatchRunner(repository, files, FakeOutputRoot(outputRoot), converter, FakeBudgets(), replacer)
    private val job = ConversionBatchJob(repository, runner, ReplaceBatchOriginals(repository, replacer), time)

    private class Host(var stop: BatchStatus? = null) : ConversionBatchJob.Host {
        val published = mutableListOf<BatchProgress>()
        val promoted = mutableListOf<BatchProgress?>()
        val summaries = mutableListOf<Pair<ConversionBatch, Boolean>>()

        override suspend fun publish(progress: BatchProgress) {
            published += progress
        }

        override suspend fun promote(progress: BatchProgress?) {
            promoted += progress
        }

        override fun showSummary(batch: ConversionBatch, replacing: Boolean) {
            summaries += batch to replacing
        }

        override suspend fun endingStatus(): BatchStatus? = stop
    }

    private suspend fun batchOf(vararg names: String): Long = repository.create(
        newBatch(),
        names.map { BatchSource(files.file(music, it, it), it, music) },
    )

    @Test
    fun `a conversion runs its batch and shows a summary`() = runBlocking<Unit> {
        val id = batchOf("One.flac")
        val host = Host()

        assertThat(job.run(id, replacing = false, host)).isEqualTo(Outcome.SUCCESS)

        assertThat(repository.getBatch(id)!!.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(host.promoted.first()).isNull()
        assertThat(host.summaries.single().second).isFalse()
    }

    @Test
    fun `a missing batch fails the job`() = runBlocking<Unit> {
        assertThat(job.run(404, replacing = false, Host())).isEqualTo(Outcome.FAILURE)
    }

    @Test
    fun `a conversion run again after its batch ended does nothing`() = runBlocking<Unit> {
        val id = batchOf("One.flac")
        job.run(id, replacing = false, Host())
        val host = Host()

        assertThat(job.run(id, replacing = false, host)).isEqualTo(Outcome.SUCCESS)

        assertThat(host.promoted).isEmpty()
        assertThat(host.summaries).isEmpty()
    }

    @Test
    fun `a replacement run again with nothing left to replace does nothing`() = runBlocking<Unit> {
        val id = batchOf("One.flac")
        job.run(id, replacing = false, Host())
        job.run(id, replacing = true, Host())
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        val host = Host()

        assertThat(job.run(id, replacing = true, host)).isEqualTo(Outcome.SUCCESS)

        assertThat(host.promoted).isEmpty()
        assertThat(host.summaries).isEmpty()
    }

    @Test
    fun `a replacement asked for a batch that has not ended does nothing`() = runBlocking<Unit> {
        val id = batchOf("One.flac")
        val host = Host()

        assertThat(job.run(id, replacing = true, host)).isEqualTo(Outcome.SUCCESS)

        assertThat(host.promoted).isEmpty()
        assertThat(files.names(music)).containsExactly("One.flac")
    }

    @Test
    fun `an unexpected failure ends the batch as failed instead of leaving it running`() = runBlocking<Unit> {
        val id = batchOf("One.flac", "Two.flac")
        // The database breaks while the batch runs: the first file's outcome cannot be written.
        repository.failItemWrite = { item ->
            if (item.state == BatchItemState.DONE || item.state == BatchItemState.FAILED) IOException("disk I/O error") else null
        }
        val host = Host()

        assertThat(job.run(id, replacing = false, host)).isEqualTo(Outcome.FAILURE)

        repository.failItemWrite = { null }
        val batch = repository.getBatch(id)!!
        assertThat(batch.status).isEqualTo(BatchStatus.FAILED)
        assertThat(batch.failureReason).contains("disk I/O error")
        assertThat(repository.getItems(id).map { it.state }.toSet()).containsExactly(BatchItemState.SKIPPED)
        assertThat(host.summaries.single().first.status).isEqualTo(BatchStatus.FAILED)
    }

    @Test
    fun `a user's cancel ends the batch as cancelled`() = runBlocking<Unit> {
        val id = batchOf("One.flac", "Two.flac")
        val gate = CompletableDeferred<Unit>()
        converter.gates[repository.item(id, 0).sourceUri] = gate
        val host = Host(stop = BatchStatus.CANCELLED)

        val running = async { runCatching { job.run(id, replacing = false, host) } }
        while (converter.running == 0) yield()
        running.cancel()
        running.join()

        assertThat(repository.getBatch(id)!!.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(host.summaries.single().first.status).isEqualTo(BatchStatus.CANCELLED)
    }

    @Test
    fun `a stop the system will retry leaves the batch to resume`() = runBlocking<Unit> {
        val id = batchOf("One.flac")
        converter.gates[repository.item(id, 0).sourceUri] = CompletableDeferred()
        val host = Host(stop = null)

        val running = async { runCatching { job.run(id, replacing = false, host) } }
        while (converter.running == 0) yield()
        running.cancel()
        running.join()

        assertThat(repository.getBatch(id)!!.status).isEqualTo(BatchStatus.RUNNING)
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.PENDING)
        assertThat(host.summaries).isEmpty()
    }

    @Test
    fun `progress is throttled on a monotonic clock, boundaries always pass`() = runBlocking<Unit> {
        val id = batchOf("One.flac", "Two.flac", "Three.flac")
        val host = Host()
        val reports = listOf(
            BatchProgress(0, 3, "One.flac", 0f) to 0L,
            BatchProgress(0, 3, "One.flac", 0.1f) to 100L,
            BatchProgress(1, 3, "Two.flac", 0.4f) to 500L,
            BatchProgress(1, 3, "Two.flac", 0.5f) to 600L,
            BatchProgress(2, 3, "Three.flac", 0.7f) to 300L,
            BatchProgress(3, 3, null, 1f) to 10L,
        )

        job.report(host).let { report ->
            for ((progress, after) in reports) {
                time += after.milliseconds
                report(progress)
            }
        }

        // 0 ms (start), 1.2 s (1 s after the last one), 1.51 s (the end); 0.1, 0.6 and 1.5 s were too soon.
        val expected = listOf(reports[0].first, reports[3].first, reports[5].first)
        assertThat(host.published).isEqualTo(expected)
        assertThat(host.promoted).isEqualTo(expected)
    }

    @Test
    fun `a conversion run again after its ending was cut short completes the clean-up`() = runBlocking<Unit> {
        // The process died right after the cancel was recorded, before the files left were marked skipped.
        val id = batchOf("One.flac", "Two.flac")
        repository.transition(id, setOf(BatchStatus.QUEUED), BatchStatus.CANCELLED)
        val host = Host()

        assertThat(job.run(id, replacing = false, host)).isEqualTo(Outcome.SUCCESS)

        assertThat(repository.getBatch(id)!!.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(repository.getItems(id).map { it.state }.toSet()).containsExactly(BatchItemState.SKIPPED)
        assertThat(converter.requests).isEmpty()
    }

    @Test
    fun `a replacement that fails unexpectedly fails the job and leaves the batch as it ended`() = runBlocking<Unit> {
        val id = batchOf("One.flac")
        job.run(id, replacing = false, Host())
        val output = repository.item(id, 0).outputUri!!
        // The database breaks: the replacement cannot be recorded.
        repository.failItemWrite = { item -> if (item.state == BatchItemState.REPLACED_ORIGINAL) IOException("disk I/O error") else null }
        val host = Host(stop = BatchStatus.CANCELLED)

        assertThat(job.run(id, replacing = true, host)).isEqualTo(Outcome.FAILURE)

        repository.failItemWrite = { null }
        assertThat(repository.getBatch(id)!!.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(host.summaries).isEmpty()
        // Unrecorded, so the history still points at the batch copy, which is kept.
        val item = repository.item(id, 0)
        assertThat(item.state).isEqualTo(BatchItemState.DONE)
        assertThat(item.outputUri).isEqualTo(output)
        assertThat(files.content(output)).isEqualTo("AIFF:One.flac")
    }

    @Test
    fun `a cancelled replacement finishes the file in hand and leaves the batch as it ended`() = runBlocking<Unit> {
        val id = batchOf("One.flac", "Two.flac")
        job.run(id, replacing = false, Host())
        val first = repository.item(id, 0).sourceUri
        val host = Host(stop = BatchStatus.CANCELLED)
        lateinit var running: kotlinx.coroutines.Deferred<Result<Outcome>>
        files.onDelete = { uri -> if (uri == first) running.cancel() }

        running = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { runCatching { job.run(id, replacing = true, host) } }
        running.join()

        assertThat(repository.getBatch(id)!!.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.DONE)
        assertThat(host.summaries).isEmpty()
    }
}
