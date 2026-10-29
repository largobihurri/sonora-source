package io.sonora.core.data.conversion.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A file converted and verified whose original was not replaced: failed, with its output and conversion result still
 * on record, as [io.sonora.core.domain.model.ConversionBatchItem.canReplaceOriginal] requires to replace it again.
 */
private const val NOT_REPLACED = "(i.state = 'FAILED' AND i.output_uri IS NOT NULL AND i.result_output_frames IS NOT NULL)"
private const val WITH_COUNTS = "SELECT b.*, COUNT(i.id) AS total_count, " +
    "COALESCE(SUM(i.state = 'PENDING'), 0) AS pending_count, " +
    "COALESCE(SUM(i.state = 'RUNNING'), 0) AS running_count, " +
    "COALESCE(SUM(i.state = 'DONE'), 0) AS done_count, " +
    "COALESCE(SUM(i.state = 'FAILED'), 0) AS failed_count, " +
    "COALESCE(SUM(i.state = 'SKIPPED'), 0) AS skipped_count, " +
    "COALESCE(SUM(i.state = 'REPLACED_ORIGINAL'), 0) AS replaced_count, " +
    "COALESCE(SUM($NOT_REPLACED), 0) AS not_replaced_count, " +
    "COALESCE(SUM(i.tags_written = 0 AND (i.state IN ('DONE', 'REPLACED_ORIGINAL') OR $NOT_REPLACED)), 0) AS untagged_count, " +
    "GROUP_CONCAT(DISTINCT CASE WHEN i.id IS NOT NULL THEN COALESCE(i.profile_id, b.profile_id) END) AS item_profile_ids " +
    "FROM conversion_batches b LEFT JOIN conversion_batch_items i ON i.batch_id = b.id"
private const val ITEMS_WITH_PROFILE = "SELECT i.*, b.profile_id AS batch_profile_id FROM conversion_batch_items i " +
    "JOIN conversion_batches b ON b.id = i.batch_id WHERE i.batch_id = :batchId ORDER BY i.position"

@Dao
abstract class ConversionHistoryDao {

    @Insert
    protected abstract suspend fun insertBatch(batch: ConversionBatchEntity): Long

    @Insert
    protected abstract suspend fun insertItems(items: List<ConversionBatchItemEntity>)

    /** Stores [batch] and its [items] (their batch id is set here) together; returns the batch id. */
    @Transaction
    open suspend fun insert(batch: ConversionBatchEntity, items: List<ConversionBatchItemEntity>): Long {
        val id = insertBatch(batch.copy(id = 0))
        insertItems(items.map { it.copy(id = 0, batchId = id) })
        return id
    }

    @Query("$WITH_COUNTS GROUP BY b.id ORDER BY b.created_at_ms DESC, b.id DESC")
    abstract fun batches(): Flow<List<BatchWithCounts>>

    @Query("$WITH_COUNTS WHERE b.id = :id GROUP BY b.id")
    abstract fun batch(id: Long): Flow<BatchWithCounts?>

    @Query("$WITH_COUNTS WHERE b.id = :id GROUP BY b.id")
    abstract suspend fun getBatch(id: Long): BatchWithCounts?

    @Query(ITEMS_WITH_PROFILE)
    abstract fun items(batchId: Long): Flow<List<ItemWithProfile>>

    @Query(ITEMS_WITH_PROFILE)
    abstract suspend fun getItems(batchId: Long): List<ItemWithProfile>

    @Update
    abstract suspend fun updateBatch(batch: ConversionBatchEntity)

    @Update
    abstract suspend fun updateItem(item: ConversionBatchItemEntity)

    /** Sets the batch's status and failure reason only while its status is one of [from]; returns the rows changed. */
    @Query("UPDATE conversion_batches SET status = :to, failure_reason = :failureReason WHERE id = :id AND status IN (:from)")
    abstract suspend fun transition(id: Long, from: List<String>, to: String, failureReason: String?): Int

    @Query("DELETE FROM conversion_batch_items WHERE batch_id = :batchId")
    protected abstract suspend fun deleteItems(batchId: Long)

    @Query("DELETE FROM conversion_batches WHERE id = :batchId")
    protected abstract suspend fun deleteBatch(batchId: Long)

    @Transaction
    open suspend fun delete(batchId: Long) {
        deleteItems(batchId)
        deleteBatch(batchId)
    }
}
