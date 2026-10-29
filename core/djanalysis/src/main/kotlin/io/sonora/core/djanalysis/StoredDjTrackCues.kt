package io.sonora.core.djanalysis

import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.DjCueStore
import io.sonora.core.djmodel.DjTrackCues
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * The [DjTrackCues] of the app: finds the audio id of the uri the way [StoredDjTrackAnalyses] does (stamp and memo,
 * never a hash, repeated whenever the uri's analysis status moves to a new step) and reads and writes the cues of that
 * id in the [cues] store. A track that is not identified yet (or whose file changed since) has no cues and stores
 * nothing, and the call says so; the cue controls only act for a track with an analysis, which is always identified.
 */
class StoredDjTrackCues(
    private val status: DjAnalysisStatusSource,
    documents: AudioDocuments,
    index: AudioIdIndex,
    private val cues: DjCueStore,
    dispatcher: CoroutineDispatcher,
) : DjTrackCues {

    private val ids = TrackAudioIds(documents, index, dispatcher)

    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
    override fun cuesOf(uri: String): Flow<List<DjCue>> = ids.follow(status, uri)
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else cues.observe(id) }

    override suspend fun save(uri: String, cue: DjCue): Boolean {
        val id = ids.idOf(uri) ?: return false
        cues.put(id, cue)
        return true
    }

    override suspend fun delete(uri: String, cueId: String): Boolean {
        val id = ids.idOf(uri) ?: return false
        cues.delete(id, cueId)
        return true
    }
}
