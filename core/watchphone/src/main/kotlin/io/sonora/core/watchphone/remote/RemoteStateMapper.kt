package io.sonora.core.watchphone.remote

import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.watchsync.remote.RemoteLimits
import io.sonora.core.watchsync.remote.RemoteQueueItem
import io.sonora.core.watchsync.remote.RemoteState
import io.sonora.core.watchsync.remote.RemoteTrack

/** The phone's music volume as a step out of its range. */
data class VolumeLevel(val current: Int, val max: Int)

/**
 * The picture of [playback] the watch gets. The playing song is the queue entry at the current index (its id is what the
 * watch's commands name it by); when the index is not inside the queue there is no playing song to name, so the state says
 * nothing is playing. [includeQueue] adds the window from the playing song on (at most [RemoteLimits.MAX_QUEUE_ITEMS]).
 */
fun remoteState(playback: PlaybackState, volume: VolumeLevel, includeQueue: Boolean): RemoteState {
    val index = playback.currentIndex.takeIf { it in playback.queue.indices }
    val current = index?.let { playback.queue[it] }
    val track = current?.let {
        RemoteTrack(
            id = it.id,
            title = it.track.title,
            artist = it.track.artist,
            album = it.track.album,
            durationMs = playback.durationMs.takeIf { duration -> duration > 0 } ?: it.track.durationMs,
        )
    }
    val window = when {
        !includeQueue -> null
        index == null -> emptyList()
        else -> playback.queue.subList(index, minOf(playback.queue.size, index + RemoteLimits.MAX_QUEUE_ITEMS))
            .map { RemoteQueueItem(it.id, it.track.title, it.track.artist) }
    }
    return RemoteState(
        track = track,
        isPlaying = track != null && playback.isPlaying,
        positionMs = playback.positionMs.coerceAtLeast(0L),
        volume = volume.current,
        volumeMax = volume.max,
        queueIndex = index ?: -1,
        queueSize = playback.queue.size,
        hasNext = index != null && index < playback.queue.lastIndex,
        queue = window,
    )
}
