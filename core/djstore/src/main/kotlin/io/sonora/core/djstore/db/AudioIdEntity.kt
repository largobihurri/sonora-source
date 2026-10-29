package io.sonora.core.djstore.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The memo of which audio a file holds: the id found by hashing [uri] when the file was [sizeBytes] long and last
 * modified at [lastModifiedMs]. Recomputable, so a row that is stale or missing only costs a hash. The stamp is
 * `null` when it was remembered without one.
 */
@Entity(tableName = "audio_ids")
class AudioIdEntity(
    @PrimaryKey @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "last_modified_ms") val lastModifiedMs: Long?,
    /** `AudioId.value`. */
    @ColumnInfo(name = "audio_id") val audioId: String,
)

@Dao
abstract class AudioIdDao {

    @Query("SELECT * FROM audio_ids WHERE uri = :uri")
    abstract suspend fun find(uri: String): AudioIdEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: AudioIdEntity)
}
