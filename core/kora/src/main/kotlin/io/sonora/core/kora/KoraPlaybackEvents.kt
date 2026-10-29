package io.sonora.core.kora

import eus.bihurri.kora.contract.media.NowPlaying
import io.sonora.core.domain.model.PlaybackState
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import eus.bihurri.kora.contract.media.PlaybackState as KoraPlaybackState

/**
 * How far the position may be from where the last observation predicts before it counts as a seek. The player's
 * ticker publishes every 250 ms; this leaves room for a late tick without hiding a real seek.
 */
internal const val POSITION_JUMP_TOLERANCE_MS = 1_000L

/** What a consumer cannot recompute from a position anchor: if one of these changes, the state is reported. */
private data class StateKey(val isPlaying: Boolean, val trackId: Long?, val shuffle: Boolean, val connected: Boolean)

/**
 * The reported state changes of a player's [states]: the first state, then every change of status, current track,
 * shuffle or connection, and every jump of the position (a seek, or a track restarting). The ticker's position
 * updates are not reported: a consumer extrapolates from the last anchor. [clock] is read once per observed state
 * and stamps the emitted snapshot (`updatedAtEpochMs`); it is also what measures the elapsed time of a playing track.
 * The repeat mode is not part of the contract's state, so it is not a reason to report.
 */
internal fun Flow<PlaybackState>.playbackStateChanges(clock: () -> Long): Flow<KoraPlaybackState> {
    val source = this
    return flow {
        var key: StateKey? = null
        var lastPositionMs = 0L
        var lastAtMs = 0L
        var lastPlaying = false
        source.collect { state ->
            val now = clock()
            val current = StateKey(state.isPlaying, state.currentTrack?.id, state.shuffleEnabled, state.isConnected)
            val expected = lastPositionMs + if (lastPlaying) now - lastAtMs else 0L
            val jumped = key != null && abs(state.positionMs - expected) > POSITION_JUMP_TOLERANCE_MS
            val report = key == null || key != current || jumped
            key = current
            lastPositionMs = state.positionMs
            lastAtMs = now
            lastPlaying = state.isPlaying
            if (report) emit(playbackState(state, now))
        }
    }
}

/**
 * The tracks that become current while [this] is observed. The first connected state is only the starting point (the
 * track already playing is not "new"), a disconnected player says nothing, and the same track staying current (a
 * pause, a tick, shuffle) is not announced again. [clock] is read once per announcement.
 */
internal fun Flow<PlaybackState>.nowPlayingChanges(clock: () -> Long): Flow<NowPlaying> {
    val source = this
    return flow {
        var started = false
        var currentId: Long? = null
        source.collect { state ->
            if (!state.isConnected) return@collect
            val track = state.currentTrack
            val announce = if (started && track != null && track.id != currentId) track else null
            started = true
            currentId = track?.id
            if (announce != null) emit(NowPlaying(trackSummary(announce), startedAtEpochMs = clock()))
        }
    }
}
