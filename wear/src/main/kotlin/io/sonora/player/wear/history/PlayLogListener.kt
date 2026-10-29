package io.sonora.player.wear.history

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.sonora.player.wear.kora.koraTrackId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Feeds the watch's player into the [PlayLog] for `media.history`. Attach it to the player with `addListener`.
 *
 * The callbacks arrive on the player's thread and only update a [PlayRecorder]; the records are written by one coroutine
 * on [io], in order, so the UI thread never touches the file. While sound plays, a ticker extends the play every
 * [tickMs], so a killed process loses at most that much. [scope] is the service's: cancelling it stops both.
 *
 * [PlayRecorder] is not thread-safe, so every call into it must share one thread: the player's listener thread. The ticker
 * therefore runs on [main], which must be the dispatcher of the looper the player was built on (the main one).
 */
internal class PlayLogListener(
    private val log: PlayLog,
    private val scope: CoroutineScope,
    io: CoroutineDispatcher,
    private val main: CoroutineDispatcher,
    private val tickMs: Long = TICK_MS,
    wallClock: () -> Long = System::currentTimeMillis,
    monotonic: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {
    private val writes = Channel<PlayLogEntry>(Channel.UNLIMITED)
    private val recorder = PlayRecorder(wallClock, monotonic) { writes.trySend(it) }
    private var ticker: Job? = null

    init {
        scope.launch(io) { for (entry in writes) runCatching { log.record(entry) } }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        recorder.trackChanged(mediaItem?.mediaId?.let(::koraTrackId))
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        recorder.playingChanged(isPlaying)
        ticker?.cancel()
        ticker = if (isPlaying) scope.launch(main) {
            while (true) {
                delay(tickMs)
                recorder.tick()
            }
        } else null
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) recorder.ended()
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
