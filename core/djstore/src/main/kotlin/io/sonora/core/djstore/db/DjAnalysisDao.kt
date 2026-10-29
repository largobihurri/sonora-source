package io.sonora.core.djstore.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DjAnalysisDao {

    @Query("SELECT * FROM analyses WHERE audio_id = :audioId")
    abstract suspend fun find(audioId: String): DjAnalysisEntity?

    /** Emits the row now and after every change of the table (also of other rows: callers de-duplicate). */
    @Query("SELECT * FROM analyses WHERE audio_id = :audioId")
    abstract fun observe(audioId: String): Flow<DjAnalysisEntity?>

    /** Inserts [entity] unless its audio id has a row; returns the new row id, or -1 when the row was left alone. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(entity: DjAnalysisEntity): Long

    /** Inserts or overwrites the whole row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: DjAnalysisEntity)

    /**
     * Which of [ids] have a row this build can read, judged without decoding: the row layout is not newer than
     * [maxRecordVersion] and the BLOB headers (4 magic bytes, then the u16 format version) carry [magic] and a format
     * version of at most [maxFormat] (two big-endian bytes). A row damaged deeper than its headers still counts here
     * and is found out when it is read.
     */
    @Query(
        "SELECT audio_id FROM analyses WHERE audio_id IN (:ids) AND record_version <= :maxRecordVersion " +
            "AND substr(waveform, 1, 4) = :magic AND substr(waveform, 5, 2) <= :maxFormat " +
            "AND (grid IS NULL OR (substr(grid, 1, 4) = :magic AND substr(grid, 5, 2) <= :maxFormat))",
    )
    abstract suspend fun readableAmong(
        ids: List<String>,
        maxRecordVersion: Int,
        magic: ByteArray,
        maxFormat: ByteArray,
    ): List<String>

    /**
     * Which of [ids] are stored without a waveform (`waveform_version` 0, see [DjAnalysisEntity.WAVEFORM_MISSING]) in a
     * row layout this build knows.
     */
    @Query("SELECT audio_id FROM analyses WHERE audio_id IN (:ids) AND waveform_version = 0 AND record_version <= :maxRecordVersion")
    abstract suspend fun waveformMissingAmong(ids: List<String>, maxRecordVersion: Int): List<String>

    /**
     * Sets the waveform of [audioId] if, and only if, the row is stored without one: a single statement, so two fillers
     * racing can never overwrite each other. Returns the number of rows changed (0 or 1); nothing else in the row moves.
     */
    @Query(
        "UPDATE analyses SET waveform = :blob, waveform_version = :version " +
            "WHERE audio_id = :audioId AND waveform_version = 0 AND record_version <= :maxRecordVersion",
    )
    abstract suspend fun fillWaveform(audioId: String, blob: ByteArray, version: Int, maxRecordVersion: Int): Int

    /**
     * One page of rows for the backup, in audio id order, for the ids after [after] (`""` for the first page): every
     * column but the waveform, whose blob is replaced by an empty one so the cursor window only holds small values.
     * The waveform version column is the real one.
     */
    @Query(
        "SELECT audio_id, analyzer_version, beat_source, model_id, accelerator, created_at_ms, duration_ms, key_index, " +
            "key_strength, key_method, grid, x'' AS waveform, waveform_version, record_version " +
            "FROM analyses WHERE audio_id > :after ORDER BY audio_id LIMIT :limit",
    )
    abstract suspend fun pageWithoutWaveforms(after: String, limit: Int): List<DjAnalysisEntity>

    /**
     * Stores [entity] if its audio id has no row (the first writer wins). When a row exists it is only overwritten if
     * [isRepairable] says it is damaged beyond reading, which is decided on the row inside the same transaction, so
     * two writers racing can never replace a good row. Returns whether [entity] was stored.
     */
    @Transaction
    open suspend fun putIfAbsent(entity: DjAnalysisEntity, isRepairable: (DjAnalysisEntity) -> Boolean): Boolean {
        if (insertIfAbsent(entity) != NOT_INSERTED) return true
        val existing = find(entity.audioId) ?: return insertIfAbsent(entity) != NOT_INSERTED
        if (!isRepairable(existing)) return false
        upsert(entity)
        return true
    }

    private companion object {
        const val NOT_INSERTED = -1L
    }
}
