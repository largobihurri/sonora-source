package io.sonora.core.djanalysis

import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.DjGridEditStore
import io.sonora.core.djmodel.DjTrackGridEditor
import io.sonora.core.djmodel.GridEdit
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The [DjTrackGridEditor] of the app: finds the audio id of the uri the way [StoredDjTrackAnalyses] does (stamp and
 * memo, never a hash) and writes the edit into the [edits] store under it. A track that is not identified yet (or whose
 * file changed since) has nothing stored and the call says so; the editor only opens for a track with an analysis, which
 * is always identified.
 */
class StoredDjTrackGridEditor(
    documents: AudioDocuments,
    index: AudioIdIndex,
    private val edits: DjGridEditStore,
    dispatcher: CoroutineDispatcher,
) : DjTrackGridEditor {

    private val ids = TrackAudioIds(documents, index, dispatcher)

    override suspend fun save(uri: String, edit: GridEdit): Boolean {
        val id = ids.idOf(uri) ?: return false
        edits.put(id, edit)
        return true
    }

    override suspend fun reset(uri: String): Boolean {
        val id = ids.idOf(uri) ?: return false
        edits.delete(id)
        return true
    }
}
