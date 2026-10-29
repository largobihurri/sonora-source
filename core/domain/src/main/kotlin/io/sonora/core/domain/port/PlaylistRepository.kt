package io.sonora.core.domain.port

import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import kotlinx.coroutines.flow.Flow

/** The user's playlists. Flows re-emit when a playlist or its entries change. */
interface PlaylistRepository {
    /** Every playlist with its entries, by name. */
    fun playlists(): Flow<List<PlaylistWithEntries>>

    /** Playlist [id] with its entries; `null` when there is no such playlist (or it was deleted). */
    fun playlist(id: Long): Flow<PlaylistWithEntries?>

    /** Stores a new playlist holding [entries] in that order; returns its id. */
    suspend fun create(name: String, description: String, entries: List<PlaylistEntry> = emptyList()): Long

    suspend fun updateDetails(id: Long, name: String, description: String)

    /** Deletes the playlist and all its entries. */
    suspend fun delete(id: Long)

    /**
     * Replaces playlist [id]'s entries with [edit] of the current ones, atomically. A current entry keeps its id the
     * first time it is listed; every other entry (id 0, a repeat, an entry of another playlist) is stored as a new
     * entry with a new id. Does nothing when the playlist does not exist.
     */
    suspend fun editEntries(id: Long, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>)
}
