package io.sonora.core.djmodel

import kotlinx.coroutines.flow.Flow

/**
 * The permanent store of the user's grid edits, keyed by audio identity like the analyses (so moving, renaming or
 * retagging a file keeps its edit) and kept apart from them: an edit never rewrites an analysis, a new analysis never
 * discards an edit, and deleting an edit brings the analysed grid back.
 */
interface DjGridEditStore {

    suspend fun get(id: AudioId): GridEdit?

    /** Emits the edit of [id] now and after every change; `null` while there is none. */
    fun observe(id: AudioId): Flow<GridEdit?>

    /** Stores [edit] as the edit of [id], replacing any previous one. */
    suspend fun put(id: AudioId, edit: GridEdit)

    /** Removes the edit of [id]; nothing happens when there is none. */
    suspend fun delete(id: AudioId)
}

/**
 * Writes the grid edit of the track at a uri, as the editor needs it: it finds the track's [AudioId] from the uri the
 * way [DjTrackAnalyses] does (without ever hashing), so it can only edit a track whose identity is already known,
 * which is every track that has an analysis to edit.
 */
interface DjTrackGridEditor {

    /** Stores [edit] for the audio at [uri]; `false` when the track is not identified (nothing was stored). */
    suspend fun save(uri: String, edit: GridEdit): Boolean

    /** Removes the edit of the audio at [uri], so its analysed grid is in force again; `false` when it is not identified. */
    suspend fun reset(uri: String): Boolean
}
