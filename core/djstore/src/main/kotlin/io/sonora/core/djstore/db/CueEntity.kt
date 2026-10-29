package io.sonora.core.djstore.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One cue of one track (`DjCue`), keyed by audio identity and the cue's own id, so a track has any number of rows and
 * a hot cue pad is one row (its id is derived from the pad). Kept beside the analyses, never inside them.
 *
 * The columns follow what rekordbox keeps per cue (type, pad, position and loop end in milliseconds, colour, comment):
 * the USB export reads them as they are.
 */
@Entity(tableName = "cues", primaryKeys = ["audio_id", "cue_id"])
data class CueEntity(
    /** `AudioId.value`. */
    @ColumnInfo(name = "audio_id") val audioId: String,
    /** `DjCue.id`. */
    @ColumnInfo(name = "cue_id") val cueId: String,
    /** [KIND_MEMORY] or [KIND_HOT], as rekordbox numbers the types. */
    @ColumnInfo(name = "kind") val kind: Int,
    /** The pad 0..7 of a hot cue; `null` for a memory cue. */
    @ColumnInfo(name = "slot") val slot: Int?,
    /** `DjCue.positionMs`. */
    @ColumnInfo(name = "position_ms") val positionMs: Double,
    /** `DjCue.loopEndMs`; `null` for a plain cue. */
    @ColumnInfo(name = "loop_end_ms") val loopEndMs: Double?,
    /** `CueColor.paletteIndex`. */
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    /** `CueColor.rgb`. */
    @ColumnInfo(name = "color_rgb") val colorRgb: Int,
    /** `DjCue.name`. */
    @ColumnInfo(name = "name") val name: String,
    /** When the cue was last saved, epoch milliseconds. */
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    /** The layout of this row; a row with a newer one than [RECORD_VERSION] was written by a later build. */
    @ColumnInfo(name = "record_version") val recordVersion: Int,
) {
    companion object {
        /** The row layout this build writes and reads. */
        const val RECORD_VERSION: Int = 1

        const val KIND_MEMORY: Int = 0
        const val KIND_HOT: Int = 1
    }
}

@Dao
abstract class CueDao {

    @Query("SELECT * FROM cues WHERE audio_id = :audioId")
    abstract suspend fun forAudio(audioId: String): List<CueEntity>

    /** Emits the rows now and after every change of the table (also of other rows: callers de-duplicate). */
    @Query("SELECT * FROM cues WHERE audio_id = :audioId")
    abstract fun observe(audioId: String): Flow<List<CueEntity>>

    /** Inserts or overwrites the whole row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: CueEntity)

    /** The audio id of every track that has a cue row, in order. */
    @Query("SELECT DISTINCT audio_id FROM cues ORDER BY audio_id")
    abstract suspend fun allAudioIds(): List<String>

    @Query("DELETE FROM cues WHERE audio_id = :audioId AND cue_id = :cueId")
    abstract suspend fun delete(audioId: String, cueId: String)
}
