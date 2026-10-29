package io.sonora.core.domain.port

import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.QueueAction
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Controls the playback session. Queue indices refer to [PlaybackState.queue], which is the real play order
 * (shuffle reorders it; see `PlayQueue`).
 */
interface PlaybackController {
    val state: StateFlow<PlaybackState>

    /** Replaces the queue; [shuffle] shuffles it (a random track first) and sets the shuffle state accordingly. */
    fun playQueue(queue: List<Track>, startIndex: Int = 0, shuffle: Boolean = false)

    /** Plays [tracks] right away, inserted after the current track; the rest of the queue is kept. */
    fun playNow(tracks: List<Track>)

    /** Inserts [tracks] right after the current track. */
    fun playNext(tracks: List<Track>)

    /** Appends [tracks] to the end of the queue. */
    fun addToQueue(tracks: List<Track>)

    /** Moves an upcoming item; [to] is its index once moved. Other indices are ignored. */
    fun moveQueueItem(from: Int, to: Int)

    /** Removes any item but the current one. */
    fun removeQueueItem(index: Int)

    /** Removes every item after the current one. */
    fun clearUpcoming()

    /** Jumps to the item at [index] and plays it. */
    fun skipToQueueItem(index: Int)

    fun play()
    fun pause()
    fun togglePlayPause()
    fun skipToNext()

    /** Restarts the current track when more than 3 s in, otherwise goes to the previous one. */
    fun skipToPrevious()
    fun seekTo(positionMs: Long)
    fun setShuffle(enabled: Boolean)
    fun setRepeatMode(mode: RepeatMode)
    fun cycleRepeatMode()

    /** Applies a library [action] to [tracks]. */
    fun enqueue(tracks: List<Track>, action: QueueAction) = when (action) {
        QueueAction.PLAY_NOW -> playNow(tracks)
        QueueAction.PLAY_NEXT -> playNext(tracks)
        QueueAction.ADD_TO_QUEUE -> addToQueue(tracks)
    }
}
