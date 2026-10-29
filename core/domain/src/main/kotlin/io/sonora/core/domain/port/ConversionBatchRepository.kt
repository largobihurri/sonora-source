package io.sonora.core.domain.port

import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import kotlinx.coroutines.flow.Flow

/** The converter history: every batch with its files, kept after the batch ends. */
interface ConversionBatchRepository {

    /** Stores [batch] (its id is ignored) with one pending item per source, in order; returns the new batch id. */
    suspend fun create(batch: ConversionBatch, sources: List<BatchSource>): Long

    /** Every batch with its [ConversionBatch.counts], newest first. */
    fun batches(): Flow<List<ConversionBatch>>

    fun batch(id: Long): Flow<ConversionBatch?>

    /** The batch's files in order. */
    fun items(batchId: Long): Flow<List<ConversionBatchItem>>

    suspend fun getBatch(id: Long): ConversionBatch?

    suspend fun getItems(batchId: Long): List<ConversionBatchItem>

    /** Writes the batch's status, failure reason and output folder. */
    suspend fun updateBatch(batch: ConversionBatch)

    suspend fun updateItem(item: ConversionBatchItem)

    /**
     * Sets the batch's status and [failureReason] only while its status is one of [from], atomically; false when it
     * was not (or the batch is gone). A job starting a batch and a user cancelling it race through here, so only one
     * of them wins.
     */
    suspend fun transition(batchId: Long, from: Set<BatchStatus>, to: BatchStatus, failureReason: String? = null): Boolean

    /** Forgets a batch and its files; the converted files themselves stay where they are. */
    suspend fun delete(batchId: Long)
}
