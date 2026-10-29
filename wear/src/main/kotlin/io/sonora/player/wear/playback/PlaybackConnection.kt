package io.sonora.player.wear.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.sonora.core.watchsync.remote.QueueDirection
import io.sonora.core.watchsync.remote.moveTarget
import io.sonora.player.wear.library.TrackQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The screens' handle on the playback session: connects a [MediaController] to [WearPlaybackService] (starting it), mirrors
 * the player into [state] and forwards the user's commands. Main thread only. Connect while a screen is visible
 * ([connect]) and let go when it is not ([disconnect]); the music goes on in the service.
 */
class PlaybackConnection(context: Context) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(PlaybackUiState())
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val mutableQueue = MutableStateFlow(QueueUiState())

    val state: StateFlow<PlaybackUiState> = mutableState.asStateFlow()

    /** The playing song and the upcoming ones, from the player's timeline. */
    val queue: StateFlow<QueueUiState> = mutableQueue.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player, events)
    }

    /** Connects to the session if not connected; [onConnected] runs with the controller once it is (also if it already was). */
    fun connect(onConnected: (MediaController) -> Unit = {}) {
        controller?.let(onConnected)
        if (future != null) return
        val token = SessionToken(appContext, ComponentName(appContext, WearPlaybackService::class.java))
        val pending = MediaController.Builder(appContext, token).buildAsync()
        future = pending
        pending.addListener(
            {
                val connected = runCatching { pending.get() }.getOrNull() ?: return@addListener
                if (future !== pending) {
                    connected.release()
                    return@addListener
                }
                controller = connected
                connected.addListener(listener)
                publish(connected)
                onConnected(connected)
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun disconnect() {
        controller?.removeListener(listener)
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
        mutableState.value = PlaybackUiState()
        mutableQueue.value = QueueUiState()
    }

    /** Plays [queue] from its start index. */
    fun play(queue: TrackQueue) {
        val player = controller ?: return
        player.setMediaItems(queue.tracks.map { it.toMediaItem() }, queue.startIndex, 0L)
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.playWhenReady) player.pause() else player.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    /** Back to the start of the song, or to the previous song when already near the start. */
    fun previous() {
        controller?.seekToPrevious()
    }

    /**
     * Jumps to the queue entry [entry] and plays it. Does nothing when the timeline no longer has that song at that place
     * (the queue changed under the screen).
     */
    fun jumpTo(entry: QueueEntry) {
        val player = controller ?: return
        if (!player.isAt(entry)) return
        player.seekTo(entry.index, 0L)
        player.prepare()
        player.play()
    }

    /** Takes the queue entry [entry] out of the queue; the player moves on by itself when it was the playing song. */
    fun remove(entry: QueueEntry) {
        val player = controller ?: return
        if (player.isAt(entry)) player.removeMediaItem(entry.index)
    }

    /** Moves the queue entry [entry] one place in [direction]; see [moveTarget] for what can move. */
    fun move(entry: QueueEntry, direction: QueueDirection) {
        val player = controller ?: return
        if (!player.isAt(entry)) return
        val target = moveTarget(direction, entry.index, player.currentMediaItemIndex, player.mediaItemCount) ?: return
        player.moveMediaItem(entry.index, target)
    }

    /** The playing song's position, for a progress display; 0 when nothing plays. */
    fun positionMs(): Long = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L

    private fun Player.isAt(entry: QueueEntry): Boolean = entry.isAt(mediaItemCount) { getMediaItemAt(it).mediaId }

    private fun publishQueue(player: Player) {
        val items = (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            QueueItem(item.mediaId, item.mediaMetadata.title?.toString(), item.mediaMetadata.artist?.toString())
        }
        mutableQueue.value = queueUiState(items, player.currentMediaItemIndex)
    }

    private fun publish(player: Player, events: Player.Events? = null) {
        if (events == null || events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            publishQueue(player)
        }
        val item = player.currentMediaItem
        mutableState.value = PlaybackUiState(
            connected = true,
            mediaId = item?.mediaId,
            title = item?.mediaMetadata?.title?.toString(),
            artist = item?.mediaMetadata?.artist?.toString(),
            playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            waitingForOutput = player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT,
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 },
        )
    }
}
