package io.sonora.player.wear.remote

import io.sonora.core.watchsync.remote.QueueDirection
import io.sonora.core.watchsync.remote.RemoteState
import io.sonora.core.watchsync.remote.moveTarget
import io.sonora.player.wear.playback.PlaybackUiState
import io.sonora.player.wear.playback.QueueEntry
import io.sonora.player.wear.playback.QueueItem
import io.sonora.player.wear.playback.QueueUiState
import io.sonora.player.wear.playback.queueUiState

/** The phone's playing song as the watch's Now Playing screen shows a song; `null` when the phone plays nothing. */
fun RemoteState.toPlaybackUiState(): PlaybackUiState? {
    val song = track ?: return null
    return PlaybackUiState(
        connected = true,
        mediaId = song.id.toString(),
        title = song.title,
        artist = remoteSubtitle(song.artist, song.album),
        playWhenReady = isPlaying,
        waitingForOutput = false,
        hasPrevious = true,
        hasNext = hasNext,
        durationMs = song.durationMs.takeIf { it > 0 },
    )
}

/** One line under the title: artist and album when both are known. */
fun remoteSubtitle(artist: String, album: String): String = when {
    artist.isBlank() -> album
    album.isBlank() || album == artist -> artist
    else -> "$artist · $album"
}

/**
 * The phone's queue window as the rows the watch's own queue screen shows. A row's [QueueEntry.index] is its place in the
 * phone's whole queue (what the commands name), and what can move is decided against the whole queue, not the window.
 * The playing song cannot be removed from the remote.
 */
fun remoteQueueUiState(state: RemoteState): QueueUiState {
    val window = state.queue.orEmpty()
    if (window.isEmpty() || state.queueIndex < 0) return QueueUiState()
    val rows = queueUiState(window.map { QueueItem(it.id.toString(), it.title, it.artist.ifEmpty { null }) }, currentIndex = 0)
    val offset = state.queueIndex
    return QueueUiState(
        rows.entries.map { row ->
            val index = row.index + offset
            row.copy(
                index = index,
                canMoveUp = moveTarget(QueueDirection.Up, index, offset, state.queueSize) != null,
                canMoveDown = moveTarget(QueueDirection.Down, index, offset, state.queueSize) != null,
                canRemove = !row.isCurrent,
            )
        },
    )
}

/** The id the phone knows this row by; `null` for a row that is not one of the phone's queue entries. */
fun QueueEntry.remoteId(): Long? = mediaId.toLongOrNull()
