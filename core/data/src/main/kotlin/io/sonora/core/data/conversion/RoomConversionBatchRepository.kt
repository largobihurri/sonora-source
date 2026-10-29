package io.sonora.core.data.conversion

import io.sonora.core.data.conversion.db.BatchWithCounts
import io.sonora.core.data.conversion.db.ConversionBatchEntity
import io.sonora.core.data.conversion.db.ConversionBatchItemEntity
import io.sonora.core.data.conversion.db.ConversionHistoryDao
import io.sonora.core.data.conversion.db.ItemWithProfile
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.port.ConversionBatchRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [ConversionBatchRepository] in the app's Room database (tables `conversion_batches` and `conversion_batch_items`). */
class RoomConversionBatchRepository @Inject constructor(private val dao: ConversionHistoryDao) : ConversionBatchRepository {

    override suspend fun create(batch: ConversionBatch, sources: List<BatchSource>): Long {
        val items = sources.mapIndexed { position, source ->
            ConversionBatchItemEntity.of(
                ConversionBatchItem(
                    id = 0,
                    batchId = 0,
                    position = position,
                    sourceUri = source.uri,
                    sourceName = source.displayName,
                    sourceParentUri = source.parentUri,
                    profileId = source.profileId ?: batch.profileId,
                ),
            )
        }
        return dao.insert(ConversionBatchEntity.of(batch), items)
    }

    override fun batches(): Flow<List<ConversionBatch>> = dao.batches().map { rows -> rows.map(BatchWithCounts::toDomain) }

    override fun batch(id: Long): Flow<ConversionBatch?> = dao.batch(id).map { it?.toDomain() }

    override fun items(batchId: Long): Flow<List<ConversionBatchItem>> = dao.items(batchId).map { rows -> rows.map(::toDomain) }

    override suspend fun getBatch(id: Long): ConversionBatch? = dao.getBatch(id)?.toDomain()

    override suspend fun getItems(batchId: Long): List<ConversionBatchItem> = dao.getItems(batchId).map(::toDomain)

    override suspend fun updateBatch(batch: ConversionBatch) = dao.updateBatch(ConversionBatchEntity.of(batch))

    override suspend fun updateItem(item: ConversionBatchItem) = dao.updateItem(ConversionBatchItemEntity.of(item))

    override suspend fun transition(batchId: Long, from: Set<BatchStatus>, to: BatchStatus, failureReason: String?): Boolean =
        dao.transition(batchId, from.map { it.name }, to.name, failureReason) == 1

    override suspend fun delete(batchId: Long) = dao.delete(batchId)

    private fun toDomain(row: ItemWithProfile) = row.toDomain()
}
