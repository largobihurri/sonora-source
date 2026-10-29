package io.sonora.core.data.backup.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.sonora.core.data.library.db.FavouriteEntity
import io.sonora.core.data.library.db.HiddenItemEntity
import io.sonora.core.data.library.db.PinEntity
import io.sonora.core.data.library.db.PlayCountEntity

/** One indexed song as a backup names it. */
data class LibrarySongRow(
    @ColumnInfo(name = "document_uri") val key: String,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
)

/**
 * Reads the library's marks and play counts whole for a backup, and merges a restore into them without removing or
 * lowering anything current. Same tables as `LibraryMarksDao` and `PlayCountDao`; no schema of its own.
 */
@Dao
abstract class BackupDao {

    /** Every indexed song, hidden ones included. */
    @Query("SELECT document_uri, title, artist, album, duration_ms FROM tracks ORDER BY document_uri")
    abstract suspend fun librarySongs(): List<LibrarySongRow>

    @Query("SELECT * FROM favourites ORDER BY added_at_ms, kind, item_key")
    abstract suspend fun favourites(): List<FavouriteEntity>

    @Query("SELECT * FROM pins ORDER BY context, pinned_at_ms, kind, item_key")
    abstract suspend fun pins(): List<PinEntity>

    @Query("SELECT * FROM hidden_items ORDER BY hidden_at_ms, kind, item_key")
    abstract suspend fun hidden(): List<HiddenItemEntity>

    @Query("SELECT * FROM play_counts ORDER BY track_uri")
    abstract suspend fun plays(): List<PlayCountEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertFavourites(favourites: List<FavouriteEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPins(pins: List<PinEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertHidden(hidden: List<HiddenItemEntity>)

    // SQLite's upsert needs 3.24 (API 30); minSdk 29 ships 3.22, so insert a zero row and raise it.
    @Query("INSERT OR IGNORE INTO play_counts (track_uri, play_count, last_played_ms) VALUES (:trackUri, 0, 0)")
    protected abstract suspend fun insertUnplayed(trackUri: String)

    @Query(
        "UPDATE play_counts SET play_count = MAX(play_count, :count), last_played_ms = MAX(last_played_ms, :lastPlayedMs) " +
            "WHERE track_uri = :trackUri",
    )
    protected abstract suspend fun raisePlays(trackUri: String, count: Int, lastPlayedMs: Long)

    /** Adds the marks a current one does not already cover; a current mark keeps its time and names. */
    @Transaction
    open suspend fun mergeMarks(favourites: List<FavouriteEntity>, pins: List<PinEntity>, hidden: List<HiddenItemEntity>) {
        insertFavourites(favourites)
        insertPins(pins)
        insertHidden(hidden)
    }

    /** Raises each song's count and last play to [plays]' where those are higher. */
    @Transaction
    open suspend fun mergePlays(plays: List<PlayCountEntity>) {
        for (row in plays) {
            insertUnplayed(row.trackUri)
            raisePlays(row.trackUri, row.playCount, row.lastPlayedMs)
        }
    }
}
