package io.sonora.core.djstore.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The user's edit of the beat grid of one track (`GridEdit`), keyed by audio identity like the analyses and stored
 * beside them, never inside them: an analysis is replaced or re-made without touching its edit.
 */
@Entity(tableName = "grid_edits")
data class GridEditEntity(
    /** `AudioId.value`. */
    @PrimaryKey @ColumnInfo(name = "audio_id") val audioId: String,
    /** `GridEdit.bpmHundredths`. */
    @ColumnInfo(name = "bpm_hundredths") val bpmHundredths: Int,
    /** `GridEdit.anchorMs`. */
    @ColumnInfo(name = "anchor_ms") val anchorMs: Double,
    /** When the edit was last saved, epoch milliseconds. */
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    /** The layout of this row; a row with a newer one than [RECORD_VERSION] was written by a later build. */
    @ColumnInfo(name = "record_version") val recordVersion: Int,
) {
    companion object {
        /** The row layout this build writes and reads. */
        const val RECORD_VERSION: Int = 1
    }
}

@Dao
abstract class GridEditDao {

    @Query("SELECT * FROM grid_edits WHERE audio_id = :audioId")
    abstract suspend fun find(audioId: String): GridEditEntity?

    /** Emits the row now and after every change of the table (also of other rows: callers de-duplicate). */
    @Query("SELECT * FROM grid_edits WHERE audio_id = :audioId")
    abstract fun observe(audioId: String): Flow<GridEditEntity?>

    /** Inserts or overwrites the whole row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: GridEditEntity)

    /** The audio id of every track that has an edit row, in order. */
    @Query("SELECT audio_id FROM grid_edits ORDER BY audio_id")
    abstract suspend fun allAudioIds(): List<String>

    @Query("DELETE FROM grid_edits WHERE audio_id = :audioId")
    abstract suspend fun delete(audioId: String)
}
