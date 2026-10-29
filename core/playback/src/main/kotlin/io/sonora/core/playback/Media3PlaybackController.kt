package io.sonora.core.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.model.DecodedAudioFormat
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playback.AbLoop
import io.sonora.core.domain.playback.AbLoopController
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PlaybackSnapshotStore
import io.sonora.core.domain.port.QueuedTrackUpdater
import io.sonora.core.domain.queue.PlayQueue
import io.sonora.core.domain.queue.QueueChange
import io.sonora.core.domain.queue.snapshot
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [PlaybackController] backed by a Media3 [MediaController] connected to [PlaybackService].
 *
 * The queue logic lives in [PlayQueue]; every operation computes its edits there and applies them to the controller
 * playlist, so the session's playlist is always the real play order. Media3's shuffle flag only mirrors
 * [PlayQueue.shuffled] (the session plays its playlist as listed, see [SessionPlayer]), so Up Next never lies and a
 * shuffle toggle from any other controller reorders the real queue through the same path ([QueueEditor.followPlayer]).
 * Repeat mode is read back from the player, so a change from any controller shows as is. The queue re-syncs from the
 * player on every event, so external skips and track transitions are picked up.
 *
 * It is the process's only queue owner: [PlaybackService] lives in the same process and keeps it connected whenever
 * the service runs ([sessionOwner]), with or without a screen, so the session's shuffle (system controls, Bluetooth)
 * always reaches the real queue, and the queue is recorded for resumption after process death ([SnapshotRecorder]).
 */
@Singleton
class Media3PlaybackController internal constructor(
    private val context: Context,
    snapshots: PlaybackSnapshotStore,
    private val sessionToken: () -> SessionToken,
    now: () -> Long = SystemClock::elapsedRealtime,
) : PlaybackController, QueuedTrackUpdater, AbLoopController {

    @Inject
    constructor(@ApplicationContext context: Context, snapshots: PlaybackSnapshotStore) :
        this(context, snapshots, { SessionToken(context, ComponentName(context, PlaybackService::class.java)) })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val editor = QueueEditor()
    private val recorder = SnapshotRecorder(scope, snapshots, now)
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private var ticker: Job? = null
    private val loopWatcher = AbLoopWatcher(scope)
    override val abLoop: StateFlow<AbLoop> = loopWatcher.loop

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (editor.editing) return // a half-edited playlist is not published; one refresh follows
            editor.followPlayer(player) // e.g. shuffle toggled from the notification or a Bluetooth device
            refresh(player)
        }
    }

    /** What the session reported it decodes and outputs, by queue entry (see [DecodedFormatReporter]). */
    private var decodedFormats: Map<Long, DecodedAudioFormat> = emptyMap()

    private val sessionListener = object : MediaController.Listener {
        /** Fires when the session is released or [PlaybackService] dies; the next command reconnects. */
        override fun onDisconnected(controller: MediaController) = connection.invalidate(controller)

        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            decodedFormats = decodedFormatsOf(extras)
            val state = _state.value
            _state.value = state.copy(decodedFormat = state.queue.getOrNull(state.currentIndex)?.let { decodedFormats[it.id] })
        }
    }

    private val connection = ControllerConnection(
        scope = scope,
        connect = ::connect,
        isAlive = { it.isConnected },
        onDropped = ::drop,
        onConnected = ::attach,
        onError = { Log.w(TAG, "MediaController connection failed", it) },
        onCommandsDropped = { Log.w(TAG, "Playback session unreachable; dropped $it pending command(s)") },
    )

    init {
        scope.launch { connection.execute {} }
    }

    /** What [PlaybackService]'s session needs from the queue owner (see [SessionQueueOwner]). */
    internal val sessionOwner: SessionQueueOwner = object : SessionQueueOwner {
        override fun setShuffle(enabled: Boolean) = this@Media3PlaybackController.setShuffle(enabled)
        override fun expectRestore(queue: PlayQueue) = editor.expectRestore(queue)
        override fun keepConnected() {
            scope.launch { connection.execute {} }
        }
        override fun queueFor(tracks: List<Track>, startIndex: Int, shuffle: Boolean) =
            editor.queue.play(tracks, startIndex, shuffle, Random).queue
    }

    override fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean) =
        editQueue(startPlayback = true) { play(queue, startIndex, shuffle, Random) }

    override fun playNow(tracks: List<Track>) = editQueue(startPlayback = true) { playNow(tracks) }
    override fun playNext(tracks: List<Track>) = editQueue { playNext(tracks) }
    override fun addToQueue(tracks: List<Track>) = editQueue { addToQueue(tracks) }
    override fun moveQueueItem(from: Int, to: Int) = editQueue { move(from, to) }
    override fun removeQueueItem(index: Int) = editQueue { remove(index) }
    override fun clearUpcoming() = editQueue { clearUpcoming() }
    override fun skipToQueueItem(index: Int) = editQueue(startPlayback = true) { skipTo(index) }

    /**
     * Shows [track]'s new details (e.g. after its tags were edited) in every entry for its file. When it is the entry
     * playing, the player is re-prepared at the same position: the file was rewritten under it, and its tags may now
     * take more or less room, so offsets it read before no longer hold.
     */
    override fun update(track: Track) = withController {
        val playing = editor.sync(this).current?.track?.uri == track.uri
        if (editor.edit(this, startPlayback = false) { updateTrack(track) }) refresh(this)
        if (playing && playbackState != Player.STATE_IDLE) {
            stop()
            prepare()
        }
    }

    override fun play() = withController { if (playbackState == Player.STATE_IDLE) prepare(); play() }
    override fun pause() = withController { pause() }
    override fun togglePlayPause() = withController {
        if (showsAsPlaying()) pause() else { if (playbackState == Player.STATE_IDLE) prepare(); play() }
    }
    override fun skipToNext() = withController { if (hasNextMediaItem()) seekToNextMediaItem() }
    override fun skipToPrevious() = withController {
        when (PlaybackLogic.previousAction(currentPosition, hasPreviousMediaItem())) {
            PlaybackLogic.PreviousAction.RESTART -> seekTo(0L)
            PlaybackLogic.PreviousAction.PREVIOUS_ITEM -> seekToPreviousMediaItem()
        }
    }
    override fun seekTo(positionMs: Long) = withController { seekTo(positionMs.coerceAtLeast(0L)) }
    override fun setShuffle(enabled: Boolean) = editQueue { setShuffle(enabled, Random) }
    override fun setRepeatMode(mode: RepeatMode) = withController { repeatMode = mode.toPlayer() }
    override fun cycleRepeatMode() = setRepeatMode(_state.value.repeatMode.next())

    /** Whether the A-B loop may be used: the service turns it off while DJ mode is on (its loops replace it). */
    internal fun setAbLoopAllowed(allowed: Boolean) = loopWatcher.setAllowed(allowed)

    // The A-B loop's mode is kept while disconnected; its points need the player (see [AbLoopWatcher]).
    override fun toggleAbLoop() = loopWatcher.toggle()
    override fun tapAbLoop() = withController { loopWatcher.tap() }
    override fun moveLoopStart(positionMs: Long) = withController { loopWatcher.moveStart(positionMs) }
    override fun moveLoopEnd(positionMs: Long) = withController { loopWatcher.moveEnd(positionMs) }

    /**
     * Runs [block] on a connected controller, on the main thread. While disconnected it waits for the one shared
     * connection round and is dropped if that fails (see [ControllerConnection]).
     */
    private fun withController(block: MediaController.() -> Unit) {
        scope.launch { connection.execute { it.block() } }
    }

    /**
     * Runs a [PlayQueue] operation against the controller's current playlist and applies its edits. Edits are applied
     * to the MediaController, which masks them locally right away, so the next publish already sees the new playlist.
     */
    private fun editQueue(startPlayback: Boolean = false, operation: PlayQueue.() -> QueueChange) = withController {
        if (editor.edit(this, startPlayback, operation)) refresh(this)
    }

    private fun refresh(player: Player) {
        publish(player)
        record(player)
        if (player.isPlaying) startTicker(player) else stopTicker()
    }

    private fun record(player: Player) = recorder.onState(
        editor.queue.snapshot(player.currentPosition, player.repeatMode.toDomain()),
        player.isPlaying,
        ended = player.playbackState == Player.STATE_ENDED,
    )

    private suspend fun connect(): MediaController =
        MediaController.Builder(context, sessionToken()).setListener(sessionListener).buildAsync().await()

    /** Wires a freshly built controller; if this throws, [ControllerConnection] releases it through [drop]. */
    private fun attach(mc: MediaController) {
        mc.addListener(listener)
        loopWatcher.attach(mc)
        decodedFormats = decodedFormatsOf(mc.sessionExtras)
        publish(mc)
    }

    private fun decodedFormatsOf(extras: Bundle): Map<Long, DecodedAudioFormat> =
        DecodedFormatExtras.fromBundle(extras).associateBy { it.queueItemId }

    /** Forgets a dead controller and publishes the disconnected state instead of its stale snapshot. */
    private fun drop(mc: MediaController) {
        stopTicker()
        loopWatcher.detach()
        mc.removeListener(listener)
        mc.release()
        editor.reset()
        decodedFormats = emptyMap()
        _state.value = PlaybackState()
    }

    private fun startTicker(player: Player) {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                _state.value = _state.value.copy(positionMs = player.currentPosition.coerceAtLeast(0L))
                record(player)
                delay(250L)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun publish(player: Player) {
        val queue = editor.sync(player)
        val current = queue.current?.track
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: current?.durationMs ?: 0L
        _state.value = PlaybackState(
            currentTrack = current,
            queue = queue.items,
            isPlaying = player.showsAsPlaying(),
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration.coerceAtLeast(0L),
            shuffleEnabled = queue.shuffled,
            repeatMode = player.repeatMode.toDomain(),
            currentIndex = queue.currentIndex,
            isConnected = true,
            decodedFormat = queue.current?.let { decodedFormats[it.id] },
        )
    }

    /**
     * The UI's "playing" follows the intent to play (see [PlaybackLogic.showsAsPlaying]), so a track that is still
     * buffering already shows as playing. Anything that needs audio actually moving (the position ticker, play counts,
     * the A-B loop, position saving) reads [Player.isPlaying] directly.
     */
    private fun Player.showsAsPlaying(): Boolean =
        PlaybackLogic.showsAsPlaying(playWhenReady, playbackState, playbackSuppressionReason)

    private fun RepeatMode.toPlayer(): Int = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    }

    private fun Int.toDomain(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    private companion object {
        const val TAG = "SonoraPlayback"
    }
}

/** Minimal ListenableFuture -> suspend bridge (avoids kotlinx-coroutines-guava). */
internal suspend fun <T> ListenableFuture<T>.await(): T {
    if (isDone) return try { get() } catch (e: ExecutionException) { throw e.cause ?: e }
    return suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: ExecutionException) {
                cont.resumeWithException(e.cause ?: e)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, Runnable::run)
        cont.invokeOnCancellation { cancel(false) }
    }
}
