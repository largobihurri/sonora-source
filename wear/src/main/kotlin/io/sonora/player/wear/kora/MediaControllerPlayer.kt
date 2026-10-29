package io.sonora.player.wear.kora

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import io.sonora.player.wear.library.WatchTrack
import io.sonora.player.wear.playback.WearPlaybackService
import io.sonora.player.wear.playback.toMediaItem
import io.sonora.player.wear.playback.toWatchTrack
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The watch's player for Kora: a [MediaController] on [WearPlaybackService]'s session, the same player the screens and the
 * system's media controls drive. Everything runs on the main thread, where a controller must be used.
 *
 * The controller is connected on the first use and shared by everything in flight (a call, or a subscription for as
 * long as it is collected). Once the last one leaves it is kept for [IDLE_LINGER_MS] and then released, which unbinds the
 * service and, if nothing plays, lets it stop. The linger covers a command still in the session's queue when the caller
 * is done: unbinding at once could end a freshly created service before it handled `play` and went foreground. Nothing
 * runs in between: no timer but the release, no polling, no listener without a subscriber.
 */
internal class MediaControllerPlayer(
    context: Context,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) : WatchPlayerPort {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Main thread only, from here to the end of the class.
    private var future: ListenableFuture<MediaController>? = null
    private var users = 0
    private var releaseJob: Job? = null
    private val onLost = CopyOnWriteArraySet<() -> Unit>()

    override suspend fun state(): WatchPlayerState = use { it.snapshot() }

    override fun states(): Flow<WatchPlayerState> = callbackFlow {
        val controller = acquire()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(*OBSERVED_EVENTS)) trySend(player.snapshot())
            }
        }
        val lost = { close(unavailable()); Unit }
        onLost += lost
        controller.addListener(listener)
        trySend(controller.snapshot())
        awaitClose {
            onLost -= lost
            controller.removeListener(listener)
            leave()
        }
    }.flowOn(Dispatchers.Main.immediate)

    override suspend fun play() = use { it.resume() }

    override suspend fun pause() = use { it.pause() }

    override suspend fun togglePlayPause() = use { if (it.isPlaying || it.playWhenReady) it.pause() else it.resume() }

    override suspend fun next() = use { it.seekToNextMediaItem() }

    /** As the screens: back to the start of the song, or to the previous song when already near the start. */
    override suspend fun previous() = use { it.seekToPrevious() }

    override suspend fun seekTo(positionMs: Long) = use { it.seekTo(positionMs) }

    override suspend fun playQueue(tracks: List<WatchTrack>, startIndex: Int) = use {
        // The queue is already in the order to play (see `playQueue`): the player's own shuffle would reorder it again.
        it.shuffleModeEnabled = false
        it.setMediaItems(tracks.map(WatchTrack::toMediaItem), startIndex, 0L)
        it.prepare()
        it.play()
    }

    /** Plays; prepares first if the player never was, and starts over if the queue had ended. */
    private fun MediaController.resume() {
        when (playbackState) {
            Player.STATE_IDLE -> if (mediaItemCount > 0) prepare()
            Player.STATE_ENDED -> seekToDefaultPosition(0)
            else -> Unit // buffering or ready: `play()` is all it takes
        }
        play()
    }

    private suspend fun <T> use(block: (MediaController) -> T): T = withContext(Dispatchers.Main.immediate) {
        val controller = acquire()
        try {
            block(controller)
        } finally {
            leave()
        }
    }

    private suspend fun acquire(): MediaController {
        releaseJob?.cancel()
        releaseJob = null
        users++
        try {
            return connected()
        } catch (failure: Throwable) {
            leave()
            throw failure
        }
    }

    private fun leave() {
        users--
        if (users > 0) return
        releaseJob = scope.launch {
            delay(IDLE_LINGER_MS)
            drop()
        }
    }

    private suspend fun connected(): MediaController {
        val pending = future ?: build().also { future = it }
        // A service that cannot be bound fails the future; to a caller that is the same as one that is too slow.
        val controller = try {
            withTimeoutOrNull(connectTimeoutMs) { pending.await() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (controller == null) {
            if (future === pending) drop()
            throw unavailable()
        }
        return controller
    }

    private fun build(): ListenableFuture<MediaController> {
        val token = SessionToken(appContext, ComponentName(appContext, WearPlaybackService::class.java))
        return MediaController.Builder(appContext, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    future = null
                    onLost.forEach { it() }
                }
            })
            .buildAsync()
    }

    private fun drop() {
        future?.let(MediaController::releaseFuture)
        future = null
    }

    private fun unavailable() = KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "The player is not available"))

    private companion object {
        /** A cold start binds the service and builds its player in well under a second; the node's call timeout is 5 s. */
        const val CONNECT_TIMEOUT_MS = 3_000L

        val IDLE_LINGER_MS = 10.seconds.inWholeMilliseconds

        /** What changes the contract's state: play/pause, loading, the song, a seek, the queue, shuffle. */
        val OBSERVED_EVENTS = intArrayOf(
            Player.EVENT_IS_PLAYING_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_POSITION_DISCONTINUITY,
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
        )
    }
}

/** The player as the contract sees it, at this moment. */
private fun Player.snapshot(): WatchPlayerState {
    val item: MediaItem? = currentMediaItem
    val sound = soundExpected(
        isPlaying = isPlaying,
        playWhenReady = playWhenReady,
        buffering = playbackState == Player.STATE_BUFFERING,
        suppressed = playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    )
    return WatchPlayerState(
        status = watchStatus(hasMedia = item != null, ended = playbackState == Player.STATE_ENDED, playing = sound),
        track = item?.toWatchTrack(),
        positionMs = currentPosition.coerceAtLeast(0L),
        shuffle = shuffleModeEnabled,
    )
}

private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        },
        MoreExecutors.directExecutor(),
    )
}
