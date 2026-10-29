package io.sonora.core.domain.conversion.batch

import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.port.ConversionBatchScheduler

/**
 * Cancels a batch. A batch whose job is running is ended by that job once stopped, which deletes its part files and
 * skips the files left; any other batch is ended here: one no job has claimed yet, and one whose job the system stopped
 * and would have run again (cancelling drops that run, so nobody else would end it). Both sides go through an atomic
 * status transition, so a job starting while the user cancels never runs a cancelled batch.
 */
class CancelConversionBatch(
    private val scheduler: ConversionBatchScheduler,
    private val runner: ConversionBatchRunner,
) {
    suspend operator fun invoke(batchId: Long) {
        val jobEndsIt = scheduler.cancel(batchId)
        if (runner.cancelQueued(batchId) || jobEndsIt) return
        runner.finishInterrupted(batchId, BatchStatus.CANCELLED)
    }
}
