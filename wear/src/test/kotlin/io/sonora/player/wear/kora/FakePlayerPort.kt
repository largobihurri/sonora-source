package io.sonora.player.wear.kora

import io.sonora.player.wear.library.WatchTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription

/** Records every command. [states] is hot while collected, like the real port, and counts its collectors. */
internal class FakePlayerPort(initial: WatchPlayerState = playerState(null, WatchStatus.IDLE)) : WatchPlayerPort {
    private val current = MutableStateFlow(initial)
    private val updates = MutableSharedFlow<WatchPlayerState>(extraBufferCapacity = 16)
    val calls = mutableListOf<String>()

    /** Set to make every call fail the way an unreachable player does. */
    var failure: Throwable? = null

    @Volatile
    var activeSubscriptions = 0
        private set

    suspend fun emit(state: WatchPlayerState) {
        current.value = state
        updates.emit(state)
    }

    private fun record(call: String) {
        failure?.let { throw it }
        calls += call
    }

    override suspend fun state(): WatchPlayerState {
        failure?.let { throw it }
        return current.value
    }

    override fun states(): Flow<WatchPlayerState> = updates
        .onSubscription {
            emit(current.value)
            activeSubscriptions++
        }
        .onCompletion { activeSubscriptions-- }

    override suspend fun play() = record("play")
    override suspend fun pause() = record("pause")
    override suspend fun togglePlayPause() = record("toggle")
    override suspend fun next() = record("next")
    override suspend fun previous() = record("previous")
    override suspend fun seekTo(positionMs: Long) = record("seekTo $positionMs")
    override suspend fun playQueue(tracks: List<WatchTrack>, startIndex: Int) =
        record("playQueue ${tracks.map { it.id }} start=$startIndex")
}
