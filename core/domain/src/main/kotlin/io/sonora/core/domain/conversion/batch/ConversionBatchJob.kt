package io.sonora.core.domain.conversion.batch

import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.port.ConversionBatchRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One run of the background job behind a batch: its conversion, or the later replacement of its originals. The
 * platform job (a WorkManager worker) is a thin [Host] around it.
 *
 * - A conversion re-run after its batch ended only completes that ending's clean-up if it was cut short; a replacement
 *   with nothing left to replace does nothing.
 * - Progress reaches the host at most once per second on [timeSource] (monotonic, unlike wall time), plus the start
 *   and the end of the batch.
 * - A stop the host reports as final ([Host.endingStatus]) ends the batch; any other stop leaves it to resume.
 * - Any unexpected failure ends a conversion as [BatchStatus.FAILED], never leaving it running.
 */
class ConversionBatchJob(
    private val repository: ConversionBatchRepository,
    private val runner: ConversionBatchRunner,
    private val replaceOriginals: ReplaceBatchOriginals,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    /** What the platform job offers the run. */
    interface Host {
        /** Publishes progress to whoever watches the job (the converter screen). */
        suspend fun publish(progress: BatchProgress)

        /** Shows or updates the job's ongoing notification; `null` before the first progress. */
        suspend fun promote(progress: BatchProgress?)

        /** The notification telling how [batch] (or its replacement) ended. */
        fun showSummary(batch: ConversionBatch, replacing: Boolean)

        /** How the stop that just cancelled the run ends the batch, or `null` when the job will run again. */
        suspend fun endingStatus(): BatchStatus?
    }

    enum class Outcome { SUCCESS, FAILURE }

    suspend fun run(batchId: Long, replacing: Boolean, host: Host): Outcome {
        val batch = repository.getBatch(batchId) ?: return Outcome.FAILURE
        if (!replacing && batch.status.isFinal) return completeEnding(batchId)
        if (!hasWork(batch, replacing)) return Outcome.SUCCESS
        host.promote(null)
        val report = report(host)
        return try {
            val ended = if (replacing) replaceOriginals(batchId, report) else runner.run(batchId, report)
            ended?.takeIf { it.status.isFinal }?.let { host.showSummary(it, replacing) }
            Outcome.SUCCESS
        } catch (e: CancellationException) {
            if (!replacing) {
                withContext(NonCancellable) {
                    val status = host.endingStatus() ?: return@withContext
                    runner.finishInterrupted(batchId, status)?.let { host.showSummary(it, replacing = false) }
                }
            }
            throw e
        } catch (e: Exception) {
            if (!replacing) {
                // Best effort: when the history itself is what broke, there is nothing more to record.
                val failed = try {
                    runner.finishFailed(batchId, ConversionBatchRunner.reasonFor(e))
                } catch (_: Exception) {
                    null
                }
                failed?.let { host.showSummary(it, replacing = false) }
            }
            Outcome.FAILURE
        }
    }

    /** A conversion runs while its batch has not ended; a replacement once it has, for outputs still to move. */
    private suspend fun hasWork(batch: ConversionBatch, replacing: Boolean): Boolean {
        if (!replacing) return !batch.status.isFinal
        return batch.status.isFinal && repository.getItems(batch.id).any { it.canReplaceOriginal }
    }

    /** A conversion run again after its batch ended: a run killed while ending it left the clean-up to this one. */
    private suspend fun completeEnding(batchId: Long): Outcome = try {
        runner.cleanUpEnded(batchId)
        Outcome.SUCCESS
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Outcome.FAILURE
    }

    /** Passes progress to [host], throttled to [PROGRESS_INTERVAL] except at the batch's start and end. */
    internal fun report(host: Host): suspend (BatchProgress) -> Unit {
        var last: TimeMark? = null
        return { progress ->
            val boundary = progress.finished == progress.total || progress.fraction == 0f
            val since = last?.elapsedNow()
            if (boundary || since == null || since >= PROGRESS_INTERVAL) {
                last = timeSource.markNow()
                host.publish(progress)
                host.promote(progress)
            }
        }
    }

    private companion object {
        val PROGRESS_INTERVAL = 1.seconds
    }
}
