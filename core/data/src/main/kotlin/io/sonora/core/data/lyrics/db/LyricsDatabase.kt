package io.sonora.core.data.lyrics.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * The answer of one online lyrics search, by track URI: the record found ([found] true; lyrics may still be only
 * [instrumental]) or the fact that the service had none.
 */
@Entity(tableName = "downloaded_lyrics")
data class DownloadedLyricsEntity(
    @PrimaryKey @ColumnInfo(name = "track_uri") val trackUri: String,
    val found: Boolean,
    @ColumnInfo(name = "synced_lyrics") val syncedLyrics: String?,
    @ColumnInfo(name = "plain_lyrics") val plainLyrics: String?,
    val instrumental: Boolean,
)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM downloaded_lyrics WHERE track_uri = :trackUri")
    suspend fun get(trackUri: String): DownloadedLyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: DownloadedLyricsEntity)

    @Query("DELETE FROM downloaded_lyrics WHERE track_uri = :trackUri")
    suspend fun remove(trackUri: String)
}

/**
 * Downloaded lyrics, kept apart from the library index so their schema evolves on its own. The content is a cache
 * that can always be searched again, so a future version may be opened with a destructive migration instead of a
 * hand-written one.
 */
@Database(entities = [DownloadedLyricsEntity::class], version = 1, exportSchema = true)
abstract class LyricsDatabase : RoomDatabase() {
    abstract fun lyricsDao(): LyricsDao

    companion object {
        const val NAME = "lyrics.db"
    }
}
