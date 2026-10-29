package io.sonora.core.djmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The permanent store of the user's cues, keyed by audio identity like the analyses and the grid edits (so moving,
 * renaming or retagging a file keeps its cues) and kept apart from them. Nothing here ever deletes a cue except the
 * user's explicit [delete].
 */
interface DjCueStore {

    /** The cues of [id], sorted by position (see [DjCues.sorted]); empty when there are none. */
    suspend fun get(id: AudioId): List<DjCue>

    /** Emits the cues of [id] now and after every change, sorted by position. */
    fun observe(id: AudioId): Flow<List<DjCue>>

    /** Stores [cue] under [id], replacing the cue with the same [DjCue.id]. */
    suspend fun put(id: AudioId, cue: DjCue)

    /** Removes the cue [cueId] of [id]; nothing happens when there is none. */
    suspend fun delete(id: AudioId, cueId: String)
}

/**
 * The cues of the track at a uri, as the DJ screen needs them: it finds the track's [AudioId] from the uri the way
 * [DjTrackAnalyses] does (without ever hashing), so a track can only get cues once it is identified, which every
 * analysed track is.
 */
interface DjTrackCues {

    /**
     * The cues of the audio at [uri]: empty while the track is not identified, then its cues as soon as they exist,
     * and again after every change. It follows the analysis status of [uri], like [DjTrackAnalyses.analysisOf].
     */
    fun cuesOf(uri: String): Flow<List<DjCue>>

    /** Stores [cue] for the audio at [uri]; `false` when the track is not identified (nothing was stored). */
    suspend fun save(uri: String, cue: DjCue): Boolean

    /** Removes the cue [cueId] of the audio at [uri]; `false` when the track is not identified. */
    suspend fun delete(uri: String, cueId: String): Boolean
}

/** The Quantize toggle of the DJ screen: on, a cue or a jump lands on the nearest beat of the grid. On by default, like a CDJ. */
interface DjQuantizeSettings {
    val quantize: StateFlow<Boolean>

    fun setQuantize(on: Boolean)
}
