package io.sonora.core.data.conversion.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.model.BatchCounts
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.model.ConversionResult

/** One converter batch. Enums are stored by name, so reordering them never rewrites history. */
@Entity(tableName = "conversion_batches")
data class ConversionBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "performance_mode") val performanceMode: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "output_folder_name") val outputFolderName: String,
    @ColumnInfo(name = "output_folder_uri") val outputFolderUri: String?,
    @ColumnInfo(name = "overwrite_originals") val overwriteOriginals: Boolean,
    val status: String,
    @ColumnInfo(name = "failure_reason") val failureReason: String?,
) {
    fun toDomain(counts: BatchCounts = BatchCounts(), profileIds: Set<String> = emptySet()) = ConversionBatch(
        id = id,
        profileId = profileId,
        performanceMode = enumOr(performanceMode, PerformanceMode.DEFAULT),
        createdAtMs = createdAtMs,
        outputFolderName = outputFolderName,
        outputFolderUri = outputFolderUri,
        overwriteOriginals = overwriteOriginals,
        // A status written by a newer version reads as failed rather than as a batch to run again.
        status = enumOr(status, BatchStatus.FAILED),
        failureReason = failureReason,
        counts = counts,
        profileIds = profileIds,
    )

    companion object {
        fun of(batch: ConversionBatch) = ConversionBatchEntity(
            id = batch.id,
            profileId = batch.profileId,
            performanceMode = batch.performanceMode.name,
            createdAtMs = batch.createdAtMs,
            outputFolderName = batch.outputFolderName,
            outputFolderUri = batch.outputFolderUri,
            overwriteOriginals = batch.overwriteOriginals,
            status = batch.status.name,
            failureReason = batch.failureReason,
        )
    }
}

/**
 * One source file of a batch. The `result_*` columns keep what the conversion produced, so a copy of the output can
 * be verified when the originals are replaced later. [profileId] is the file's own profile (version 11); `null` only
 * in a row nothing filled, which reads as its batch's.
 */
@Entity(
    tableName = "conversion_batch_items",
    indices = [Index(value = ["batch_id", "position"], unique = true)],
)
data class ConversionBatchItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "batch_id") val batchId: Long,
    val position: Int,
    @ColumnInfo(name = "source_uri") val sourceUri: String,
    @ColumnInfo(name = "source_name") val sourceName: String,
    @ColumnInfo(name = "source_parent_uri") val sourceParentUri: String?,
    val state: String,
    @ColumnInfo(name = "output_uri") val outputUri: String?,
    @ColumnInfo(name = "output_name") val outputName: String?,
    @ColumnInfo(name = "tags_written") val tagsWritten: Boolean,
    @ColumnInfo(name = "failure_reason") val failureReason: String?,
    @ColumnInfo(name = "suffixed_name") val suffixedName: Boolean,
    @Embedded(prefix = "result_") val result: ResultColumns?,
    @ColumnInfo(name = "profile_id") val profileId: String? = null,
) {
    /** The item, on its own profile or, when the row has none, on [batchProfileId]. */
    fun toDomain(batchProfileId: String): ConversionBatchItem {
        val profileId = profileId ?: batchProfileId
        val known = enumValues<BatchItemState>().firstOrNull { it.name == state }
        return ConversionBatchItem(
            id = id,
            batchId = batchId,
            position = position,
            sourceUri = sourceUri,
            sourceName = sourceName,
            sourceParentUri = sourceParentUri,
            // A state written by a newer version reads as failed. Its output may sit anywhere by then, so it loses the
            // result that would let "replace originals" move or delete anything for it.
            state = known ?: BatchItemState.FAILED,
            outputUri = outputUri,
            outputName = outputName,
            tagsWritten = tagsWritten,
            failureReason = if (known == null) "Unknown state $state (written by a newer version)" else failureReason,
            suffixedName = suffixedName,
            result = if (known == null) null else result?.toDomain(profileId, tagsWritten),
            profileId = profileId,
        )
    }

    companion object {
        fun of(item: ConversionBatchItem) = ConversionBatchItemEntity(
            id = item.id,
            batchId = item.batchId,
            position = item.position,
            sourceUri = item.sourceUri,
            sourceName = item.sourceName,
            sourceParentUri = item.sourceParentUri,
            state = item.state.name,
            outputUri = item.outputUri,
            outputName = item.outputName,
            tagsWritten = item.tagsWritten,
            failureReason = item.failureReason,
            suffixedName = item.suffixedName,
            result = item.result?.let(ResultColumns::of),
            profileId = item.profileId,
        )
    }
}

/** A [ConversionResult] without its profile, which the item row (or, before version 11, the batch row) names. */
data class ResultColumns(
    @ColumnInfo(name = "source_rate_hz") val sourceSampleRateHz: Int,
    @ColumnInfo(name = "target_rate_hz") val targetSampleRateHz: Int,
    @ColumnInfo(name = "source_channels") val sourceChannels: Int,
    @ColumnInfo(name = "target_channels") val targetChannels: Int,
    @ColumnInfo(name = "target_bits") val targetBitsPerSample: Int,
    val dither: Boolean,
    @ColumnInfo(name = "output_frames") val outputFrames: Long,
    @ColumnInfo(name = "source_frames") val sourceFrames: Long,
) {
    /** `null` when the profile is no longer registered or the columns cannot describe a conversion (corrupted row). */
    fun toDomain(profileId: String, tagsWritten: Boolean): ConversionResult? {
        val profile = ConversionProfiles.byId(profileId) ?: return null
        val valid = sourceSampleRateHz > 0 && targetSampleRateHz > 0 && sourceChannels > 0 && targetChannels > 0 &&
            targetBitsPerSample > 0 && outputFrames >= 0 && sourceFrames >= 0
        if (!valid) return null
        val plan = ConversionPlan(
            profile, sourceSampleRateHz, targetSampleRateHz, sourceChannels, targetChannels, targetBitsPerSample, dither,
        )
        return ConversionResult(plan, outputFrames, sourceFrames, tagsWritten)
    }

    companion object {
        fun of(result: ConversionResult) = with(result.plan) {
            ResultColumns(
                sourceSampleRateHz, targetSampleRateHz, sourceChannels, targetChannels, targetBitsPerSample, dither,
                result.outputFrames, result.sourceFrames,
            )
        }
    }
}

/** An item row with its batch's profile, which an item row without a profile of its own falls back to. */
data class ItemWithProfile(
    @Embedded val item: ConversionBatchItemEntity,
    @ColumnInfo(name = "batch_profile_id") val batchProfileId: String,
) {
    fun toDomain(): ConversionBatchItem = item.toDomain(batchProfileId)
}

/** A batch row with its per-state item counts. */
data class BatchWithCounts(
    @Embedded val batch: ConversionBatchEntity,
    @ColumnInfo(name = "total_count") val total: Int,
    @ColumnInfo(name = "pending_count") val pending: Int,
    @ColumnInfo(name = "running_count") val running: Int,
    @ColumnInfo(name = "done_count") val done: Int,
    @ColumnInfo(name = "failed_count") val failed: Int,
    @ColumnInfo(name = "skipped_count") val skipped: Int,
    @ColumnInfo(name = "replaced_count") val replaced: Int,
    @ColumnInfo(name = "untagged_count") val untagged: Int,
    @ColumnInfo(name = "not_replaced_count") val notReplaced: Int,
    /** The items' distinct profiles, comma-separated (profile ids hold no comma); `null` without items. */
    @ColumnInfo(name = "item_profile_ids") val itemProfileIds: String? = null,
) {
    fun toDomain() = batch.toDomain(
        BatchCounts(total, pending, running, done, failed, skipped, replaced, untagged, notReplaced),
        itemProfileIds?.split(',')?.filterTo(LinkedHashSet()) { it.isNotEmpty() }.orEmpty(),
    )
}

private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback
