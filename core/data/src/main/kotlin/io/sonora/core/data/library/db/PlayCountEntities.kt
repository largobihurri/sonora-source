package io.sonora.core.data.library.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Plays of one song, keyed like `tracks.document_uri`. Kept apart from `tracks`, which the scanner rewrites, so a
 * rescan never resets a count.
 */
@Entity(tableName = "play_counts")
data class PlayCountEntity(
    @PrimaryKey @ColumnInfo(name = "track_uri") val trackUri: String,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "last_played_ms") val lastPlayedMs: Long,
)

@Dao
abstract class PlayCountDao {

    @Query("SELECT * FROM play_counts ORDER BY track_uri")
    abstract fun all(): Flow<List<PlayCountEntity>>

    // SQLite's upsert needs 3.24 (API 30); minSdk 29 ships 3.22, so insert a zero row and add to it.
    @Query("INSERT OR IGNORE INTO play_counts (track_uri, play_count, last_played_ms) VALUES (:trackUri, 0, :playedAtMs)")
    protected abstract suspend fun insertUnplayed(trackUri: String, playedAtMs: Long)

    @Query("UPDATE play_counts SET play_count = play_count + 1, last_played_ms = :playedAtMs WHERE track_uri = :trackUri")
    protected abstract suspend fun addPlay(trackUri: String, playedAtMs: Long)

    @Transaction
    open suspend fun recordPlay(trackUri: String, playedAtMs: Long) {
        insertUnplayed(trackUri, playedAtMs)
        addPlay(trackUri, playedAtMs)
    }
}
