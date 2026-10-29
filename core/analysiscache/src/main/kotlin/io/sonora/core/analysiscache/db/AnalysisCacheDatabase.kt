package io.sonora.core.analysiscache.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * The analysis cache, a database of its own: it holds nothing that cannot be recomputed, so a schema change may simply
 * drop it (see `AnalysisCacheModule`).
 */
@Database(entities = [AnalysisEntity::class], version = 1, exportSchema = true)
abstract class AnalysisCacheDatabase : RoomDatabase() {
    abstract fun analysisCacheDao(): AnalysisCacheDao

    companion object {
        const val NAME = "sonora-analysis-cache.db"
    }
}

/**
 * One cached analysis: the `TrackAnalysis` of the audio whose bytes hash to [contentSha256], made by
 * [analyzerVersion]. The waveforms are stored as the players' packed bytes; [sizeBytes] is what the row counts against
 * the cache's size bound.
 */
@Entity(
    tableName = "analyses",
    primaryKeys = ["content_sha256", "analyzer_version"],
    indices = [Index("last_used_ms"), Index("analyzer_version")],
)
class AnalysisEntity(
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    @ColumnInfo(name = "analyzer_version") val analyzerVersion: String,
    @ColumnInfo(name = "bpm") val bpm: Double,
    @ColumnInfo(name = "first_beat_ms") val firstBeatMs: Double,
    @ColumnInfo(name = "first_beat_in_bar") val firstBeatInBar: Int,
    @ColumnInfo(name = "beat_count") val beatCount: Int,
    /** qm-dsp key index 1..24, `null` when no key was found. */
    @ColumnInfo(name = "key_index") val keyIndex: Int?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "waveform_detail") val waveformDetail: ByteArray,
    @ColumnInfo(name = "waveform_preview") val waveformPreview: ByteArray,
    @ColumnInfo(name = "waveform_tiny_preview") val waveformTinyPreview: ByteArray,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "last_used_ms") val lastUsedMs: Long,
)

/** The key and size of an entry, for eviction. */
data class EntrySize(
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    @ColumnInfo(name = "analyzer_version") val analyzerVersion: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
)

private const val ENTRY = "content_sha256 = :contentSha256 AND analyzer_version = :analyzerVersion"

/** [nowMs], or one more than the latest stored use if that is not earlier (see [AnalysisCacheDao.useStamp]). */
private const val USE_STAMP = "MAX(:nowMs, COALESCE((SELECT MAX(last_used_ms) FROM analyses) + 1, :nowMs))"

@Dao
abstract class AnalysisCacheDao {

    @Query("SELECT * FROM analyses WHERE $ENTRY")
    abstract suspend fun find(contentSha256: String, analyzerVersion: String): AnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: AnalysisEntity)

    /**
     * Marks an entry used: its `last_used_ms` becomes [nowMs], or one more than the latest use stored when the clock
     * has gone back since (see [useStamp]).
     */
    @Query("UPDATE analyses SET last_used_ms = $USE_STAMP WHERE $ENTRY")
    abstract suspend fun touch(contentSha256: String, analyzerVersion: String, nowMs: Long)

    /**
     * The `last_used_ms` of a use at [nowMs]: [nowMs], or one more than the latest use stored if that is not earlier.
     * Uses are thus ordered as they happen even when the wall clock is set back, so eviction never takes the entry
     * used last for the oldest one.
     */
    @Query("SELECT $USE_STAMP")
    abstract suspend fun useStamp(nowMs: Long): Long

    @Query("DELETE FROM analyses WHERE $ENTRY")
    abstract suspend fun delete(contentSha256: String, analyzerVersion: String)

    @Query("DELETE FROM analyses WHERE analyzer_version != :keepAnalyzerVersion")
    abstract suspend fun deleteOtherVersions(keepAnalyzerVersion: String): Int

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM analyses")
    abstract suspend fun totalBytes(): Long

    @Query("SELECT COUNT(*) FROM analyses")
    abstract suspend fun count(): Int

    /** Every entry, least recently used first (`last_used_ms` follows the order of use, see [useStamp]). */
    @Query("SELECT content_sha256, analyzer_version, size_bytes FROM analyses ORDER BY last_used_ms, created_at_ms")
    abstract suspend fun leastRecentlyUsedFirst(): List<EntrySize>

    /** Stores [entity], then evicts least recently used entries until the cache holds at most [maxBytes]. */
    @Transaction
    open suspend fun putWithin(entity: AnalysisEntity, maxBytes: Long) {
        upsert(entity)
        var total = totalBytes()
        if (total <= maxBytes) return
        for (entry in leastRecentlyUsedFirst()) {
            if (total <= maxBytes) break
            delete(entry.contentSha256, entry.analyzerVersion)
            total -= entry.sizeBytes
        }
    }
}
