package io.sonora.core.data.stats

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import io.sonora.core.domain.stats.TrackListening
import kotlinx.coroutines.flow.Flow

/** One playback of a song: when it first played (ms since epoch), how long it was heard, and whether it counted. */
@Entity(tableName = "listening", indices = [Index("started_at_ms")])
data class ListeningEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "track_uri") val trackUri: String,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    val counted: Boolean,
)

@Dao
abstract class ListeningDao {

    // SQLite's upsert needs 3.24 (API 30); minSdk 29 ships 3.22, so insert the playback if new and then extend it.
    @Query(
        "INSERT OR IGNORE INTO listening (id, track_uri, started_at_ms, listened_ms, counted) " +
            "VALUES (:id, :trackUri, :startedAtMs, :listenedMs, :counted)",
    )
    protected abstract suspend fun insertNew(id: String, trackUri: String, startedAtMs: Long, listenedMs: Long, counted: Boolean)

    /** Never shortens a playback nor uncounts it: a save that lands after a later one changes nothing. */
    @Query("UPDATE listening SET listened_ms = MAX(listened_ms, :listenedMs), counted = (counted OR :counted) WHERE id = :id")
    protected abstract suspend fun extend(id: String, listenedMs: Long, counted: Boolean)

    @Transaction
    open suspend fun record(entity: ListeningEntity) {
        insertNew(entity.id, entity.trackUri, entity.startedAtMs, entity.listenedMs, entity.counted)
        extend(entity.id, entity.listenedMs, entity.counted)
    }

    @Query(
        "SELECT track_uri AS trackUri, SUM(counted) AS plays, SUM(listened_ms) AS listenedMs FROM listening " +
            "WHERE started_at_ms >= :sinceMs GROUP BY track_uri ORDER BY track_uri",
    )
    abstract fun perTrack(sinceMs: Long): Flow<List<TrackListening>>

    @Query("SELECT MIN(started_at_ms) FROM listening")
    abstract fun firstStartedAtMs(): Flow<Long?>

    /** The playbacks started in `[fromMs, toMs]`, oldest first and by id on a tie, so the order is stable to page. */
    @Query(
        "SELECT * FROM listening WHERE started_at_ms BETWEEN :fromMs AND :toMs " +
            "ORDER BY started_at_ms, id LIMIT :limit OFFSET :offset",
    )
    abstract suspend fun startedBetween(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<ListeningEntity>

    @Query("SELECT COUNT(*) FROM listening WHERE started_at_ms BETWEEN :fromMs AND :toMs")
    abstract suspend fun countStartedBetween(fromMs: Long, toMs: Long): Int
}

/**
 * The listening statistics, kept apart from the library index so their schema evolves on its own. Unlike the lyrics
 * cache this is the user's history and cannot be rebuilt: a future version needs a real migration.
 */
@Database(entities = [ListeningEntity::class], version = 1, exportSchema = true)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun listeningDao(): ListeningDao

    companion object {
        const val NAME = "stats.db"
    }
}
