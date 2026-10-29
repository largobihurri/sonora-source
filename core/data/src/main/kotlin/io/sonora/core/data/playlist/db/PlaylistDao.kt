package io.sonora.core.data.playlist.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlaylistDao {

    @Transaction
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE, id")
    abstract fun playlists(): Flow<List<PlaylistWithEntryRows>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun playlist(id: Long): Flow<PlaylistWithEntryRows?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    protected abstract suspend fun playlistRow(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :playlistId ORDER BY position")
    protected abstract suspend fun entryRows(playlistId: Long): List<PlaylistEntryEntity>

    @Insert
    protected abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Insert
    protected abstract suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlist_id = :playlistId")
    protected abstract suspend fun deleteEntries(playlistId: Long)

    @Query("UPDATE playlists SET name = :name, description = :description, updated_at_ms = :nowMs WHERE id = :id")
    abstract suspend fun updateDetails(id: Long, name: String, description: String, nowMs: Long)

    @Query("UPDATE playlists SET updated_at_ms = :nowMs WHERE id = :id")
    protected abstract suspend fun touch(id: Long, nowMs: Long)

    /** Deletes the playlist; its entries go with it (foreign key cascade). */
    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** Stores [playlist] (new id) with [entries] in list order (their playlist and positions are set here). */
    @Transaction
    open suspend fun insert(playlist: PlaylistEntity, entries: List<PlaylistEntryEntity>): Long {
        val id = insertPlaylist(playlist.copy(id = 0))
        insertEntries(entries.mapIndexed { position, entry -> entry.copy(playlistId = id, position = position) })
        return id
    }

    /**
     * Rewrites playlist [id]'s entries as [edit] of the current ones, renumbering positions. An entry keeps its id only
     * the first time it appears and only when it is one of this playlist's current entries; any other (id 0, a repeat,
     * or an entry of another playlist) is stored with a new id, so an edit can never collide on the primary key.
     * Rewriting the whole list keeps the unique (playlist, position) index valid at every step. No-op when the
     * playlist does not exist.
     */
    @Transaction
    open suspend fun editEntries(id: Long, nowMs: Long, edit: (List<PlaylistEntryEntity>) -> List<PlaylistEntryEntity>) {
        if (playlistRow(id) == null) return
        val current = entryRows(id)
        val keepable = current.mapTo(HashSet()) { it.id }
        val edited = edit(current).map { entry -> if (keepable.remove(entry.id)) entry else entry.copy(id = 0) }
        deleteEntries(id)
        insertEntries(edited.mapIndexed { position, entry -> entry.copy(playlistId = id, position = position) })
        touch(id, nowMs)
    }
}
