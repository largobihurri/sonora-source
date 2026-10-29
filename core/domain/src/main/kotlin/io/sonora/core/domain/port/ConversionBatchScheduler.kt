package io.sonora.core.domain.port

import io.sonora.core.domain.model.BatchProgress
import kotlinx.coroutines.flow.Flow

/** Runs batches in the background (a foreground job that survives leaving the app), one unique job per batch. */
interface ConversionBatchScheduler {

    /** Starts converting a stored batch; does nothing while it is already scheduled. */
    fun enqueueConversion(batchId: Long)

    /** Replaces the originals of a finished batch with its outputs, after any conversion still running for it. */
    fun enqueueReplacement(batchId: Long)

    /**
     * Stops the batch's job. True when a job was running: it ends the batch itself once stopped (files not converted
     * yet are skipped). False when none was, e.g. one stopped by the system and waiting to run again.
     */
    suspend fun cancel(batchId: Long): Boolean

    /** Live progress while the batch's job runs, else `null`. The history in [ConversionBatchRepository] persists. */
    fun progress(batchId: Long): Flow<BatchProgress?>
}
