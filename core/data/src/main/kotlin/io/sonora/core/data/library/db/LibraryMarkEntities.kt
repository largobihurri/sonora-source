package io.sonora.core.data.library.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.MarkKind
import kotlinx.coroutines.flow.Flow

/**
 * A favourite: [kind] is a [MarkKind] name and [key] the item's [ItemRef.key]. Kept apart from `tracks`, which the
 * scanner rewrites, so a rescan never drops one.
 */
@Entity(tableName = "favourites", primaryKeys = ["kind", "item_key"])
data class FavouriteEntity(
    val kind: String,
    @ColumnInfo(name = "item_key") val key: String,
    @ColumnInfo(name = "added_at_ms") val addedAtMs: Long,
)

/** An item pinned to the top of one list ([context], a `PinContext` id). */
@Entity(tableName = "pins", primaryKeys = ["context", "kind", "item_key"])
data class PinEntity(
    val context: String,
    val kind: String,
    @ColumnInfo(name = "item_key") val key: String,
    @ColumnInfo(name = "pinned_at_ms") val pinnedAtMs: Long,
)

/** An item removed from Sonora (its files stay), with the names it is listed by in Settings. */
@Entity(tableName = "hidden_items", primaryKeys = ["kind", "item_key"])
data class HiddenItemEntity(
    val kind: String,
    @ColumnInfo(name = "item_key") val key: String,
    val title: String,
    val subtitle: String,
    @ColumnInfo(name = "hidden_at_ms") val hiddenAtMs: Long,
) {
    /** `null` for a kind this version does not know (written by a newer one). */
    fun toDomain(): HiddenItem? {
        val kind = MarkKind.entries.firstOrNull { it.name == kind } ?: return null
        return HiddenItem(ItemRef(kind, key), title, subtitle, hiddenAtMs)
    }

    companion object {
        fun of(item: HiddenItem) = HiddenItemEntity(item.ref.kind.name, item.ref.key, item.title, item.subtitle, item.hiddenAtMs)
    }
}

@Dao
abstract class LibraryMarksDao {

    @Query("SELECT item_key FROM favourites WHERE kind = :kind ORDER BY added_at_ms DESC, item_key")
    abstract fun favourites(kind: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertFavourite(favourite: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE kind = :kind AND item_key = :key")
    abstract suspend fun deleteFavourite(kind: String, key: String)

    @Query("SELECT item_key FROM pins WHERE context = :context ORDER BY pinned_at_ms, item_key")
    abstract fun pins(context: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPin(pin: PinEntity)

    @Query("DELETE FROM pins WHERE context = :context AND kind = :kind AND item_key = :key")
    abstract suspend fun deletePin(context: String, kind: String, key: String)

    @Query("SELECT * FROM hidden_items ORDER BY hidden_at_ms DESC, item_key")
    abstract fun hidden(): Flow<List<HiddenItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertHidden(item: HiddenItemEntity)

    @Query("DELETE FROM hidden_items WHERE kind = :kind AND item_key = :key")
    abstract suspend fun deleteHidden(kind: String, key: String)

    @Query("DELETE FROM favourites WHERE kind = 'SONG' AND item_key IN (:uris)")
    protected abstract suspend fun deleteSongFavourites(uris: List<String>)

    @Query("DELETE FROM pins WHERE kind = 'SONG' AND item_key IN (:uris)")
    protected abstract suspend fun deleteSongPins(uris: List<String>)

    @Query("DELETE FROM hidden_items WHERE kind = 'SONG' AND item_key IN (:uris)")
    protected abstract suspend fun deleteSongHidden(uris: List<String>)

    /** Every mark of the songs at [uris], in chunks below SQLite's bound-argument limit. */
    @Transaction
    open suspend fun forgetSongs(uris: Collection<String>) {
        for (chunk in uris.distinct().chunked(MAX_BOUND_ARGS)) {
            deleteSongFavourites(chunk)
            deleteSongPins(chunk)
            deleteSongHidden(chunk)
        }
    }

    @Query("SELECT * FROM favourites WHERE kind = 'SONG' AND item_key = :uri")
    protected abstract suspend fun songFavourite(uri: String): FavouriteEntity?

    @Query("SELECT * FROM pins WHERE kind = 'SONG' AND item_key = :uri")
    protected abstract suspend fun songPins(uri: String): List<PinEntity>

    @Query("SELECT * FROM play_counts WHERE track_uri = :uri")
    protected abstract suspend fun songPlays(uri: String): PlayCountEntity?

    // SQLite's upsert needs 3.24 (API 30); minSdk 29 ships 3.22, so insert a zero row and add to it.
    @Query("INSERT OR IGNORE INTO play_counts (track_uri, play_count, last_played_ms) VALUES (:uri, 0, :lastPlayedMs)")
    protected abstract suspend fun insertUnplayed(uri: String, lastPlayedMs: Long)

    @Query(
        "UPDATE play_counts SET play_count = play_count + :count, last_played_ms = MAX(last_played_ms, :lastPlayedMs) " +
            "WHERE track_uri = :uri",
    )
    protected abstract suspend fun addPlays(uri: String, count: Int, lastPlayedMs: Long)

    @Query("DELETE FROM play_counts WHERE track_uri IN (:uris)")
    protected abstract suspend fun deleteSongPlays(uris: List<String>)

    /**
     * Hands each moved song's marks (a key of [moves]) to its replacement (the value), in one transaction: the favourite
     * and every pin go to the replacement unless it has its own (which keeps its place), the play counts are added up
     * keeping the latest time played. The moved songs' marks, hidden ones included, and plays are then dropped.
     */
    @Transaction
    open suspend fun moveSongs(moves: Map<String, String>) {
        val moved = moves.filter { (song, replacement) -> song != replacement }
        for ((song, replacement) in moved) {
            songFavourite(song)?.let { insertFavourite(it.copy(key = replacement)) }
            for (pin in songPins(song)) insertPin(pin.copy(key = replacement))
            songPlays(song)?.let { plays ->
                insertUnplayed(replacement, plays.lastPlayedMs)
                addPlays(replacement, plays.playCount, plays.lastPlayedMs)
            }
        }
        forgetSongs(moved.keys)
        for (chunk in moved.keys.chunked(MAX_BOUND_ARGS)) deleteSongPlays(chunk)
    }

    private companion object {
        const val MAX_BOUND_ARGS = 900
    }
}
