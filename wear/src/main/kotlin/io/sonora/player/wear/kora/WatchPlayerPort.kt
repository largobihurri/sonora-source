package io.sonora.player.wear.kora

import io.sonora.player.wear.library.WatchTrack
import kotlinx.coroutines.flow.Flow

/**
 * The watch's own player, as Kora's handlers need it. Every function may be called from any thread; a player that
 * cannot be reached (its service did not come up in time) fails with a `KoraException` coded `provider_unavailable`.
 */
internal interface WatchPlayerPort {
    /** The player as it is now. */
    suspend fun state(): WatchPlayerState

    /**
     * A snapshot of the player now, then one at each change the player reports (play and pause, a new song, a seek,
     * shuffle, the queue ending). Cold: the player is only observed while this is collected, and not at all otherwise.
     */
    fun states(): Flow<WatchPlayerState>

    suspend fun play()
    suspend fun pause()
    suspend fun togglePlayPause()
    suspend fun next()
    suspend fun previous()
    suspend fun seekTo(positionMs: Long)

    /** Replaces the queue with [tracks], starts at [startIndex] and plays. */
    suspend fun playQueue(tracks: List<WatchTrack>, startIndex: Int)
}
