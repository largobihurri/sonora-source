package io.sonora.player.wear.kora

import eus.bihurri.kora.contract.media.NowPlaying
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import eus.bihurri.kora.contract.media.PlaybackState as KoraPlaybackState

/** How far the position may be from where the last snapshot predicts before it counts as a seek (as on the phone). */
internal const val POSITION_JUMP_TOLERANCE_MS = 1_000L

/** What a consumer cannot recompute from a position anchor: if one of these changes, the state is reported. */
private data class StateKey(val status: WatchStatus, val trackId: String?, val shuffle: Boolean)

/**
 * The reported state changes of a player's [states]: the first state, then every change of status, current track or
 * shuffle, and every jump of the position (a seek, or a song restarting). A snapshot that only moves the position as
 * a playing song would is not reported: a consumer extrapolates from the last anchor. [clock] is read once per
 * observed state and stamps the emitted snapshot; it is also what measures the time a playing song has run.
 *
 * Same rules as Sonora's phone node. The source here is event-driven (the player's own callbacks, no ticker), so the
 * snapshots are few and nothing is computed between them.
 */
internal fun Flow<WatchPlayerState>.playbackStateChanges(clock: () -> Long): Flow<KoraPlaybackState> {
    val source = this
    return flow {
        var key: StateKey? = null
        var lastPositionMs = 0L
        var lastAtMs = 0L
        var lastPlaying = false
        source.collect { state ->
            val now = clock()
            val current = StateKey(state.status, state.track?.id, state.shuffle)
            val expected = lastPositionMs + if (lastPlaying) now - lastAtMs else 0L
            val jumped = key != null && abs(state.positionMs - expected) > POSITION_JUMP_TOLERANCE_MS
            val report = key == null || key != current || jumped
            key = current
            lastPositionMs = state.positionMs
            lastAtMs = now
            lastPlaying = state.status == WatchStatus.PLAYING
            if (report) emit(playbackState(state, now))
        }
    }
}

/**
 * The songs that become current while [this] is observed. The first snapshot is only the starting point (the song
 * already playing is not "new"), and the same song staying current (a pause, a seek) is not announced again.
 * [clock] is read once per announcement.
 */
internal fun Flow<WatchPlayerState>.nowPlayingChanges(clock: () -> Long): Flow<NowPlaying> {
    val source = this
    return flow {
        var started = false
        var currentId: String? = null
        source.collect { state ->
            val track = state.track
            val announce = if (started && track != null && track.id != currentId) track else null
            started = true
            currentId = track?.id
            if (announce != null) emit(NowPlaying(trackSummary(announce), startedAtEpochMs = clock()))
        }
    }
}
