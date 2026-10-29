package io.sonora.core.playback

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.queue.PlayQueue
import io.sonora.core.domain.queue.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The process's queue owner as the session sees it ([Media3PlaybackController]): it holds the real [PlayQueue] and is
 * the only one that reorders the playlist for shuffle. [PlaybackService] keeps it connected whenever the service runs,
 * so shuffle from the system controls, a headset or Android Auto reorders the real queue even with no screen open.
 */
internal interface SessionQueueOwner {
    /** Shuffles or unshuffles the real queue (the app's own toggle). */
    fun setShuffle(enabled: Boolean)

    /** The next playlist holding exactly [queue]'s entries is that queue (full tracks, restore order). */
    fun expectRestore(queue: PlayQueue)

    /** Connects to the session now if not connected. */
    fun keepConnected()

    /**
     * A new queue of [tracks] from [startIndex] (or shuffled from a random track), with entry ids this owner never
     * handed out, for the session to load and the owner to adopt ([expectRestore]); e.g. a song tapped in Android Auto.
     */
    fun queueFor(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false): PlayQueue =
        PlayQueue().play(tracks, startIndex, shuffle).queue
}

/** Shuffle and Repeat for the system media controls (notification, lock screen, Quick Settings). */
@OptIn(UnstableApi::class)
internal object MediaButtons {
    val SHUFFLE = SessionCommand("io.sonora.session.SHUFFLE", Bundle.EMPTY)
    val REPEAT = SessionCommand("io.sonora.session.REPEAT", Bundle.EMPTY)

    /** The buttons for [player]'s state; overflow slots keep play/pause, previous and next standard. */
    fun forPlayer(context: Context, player: Player): List<CommandButton> {
        val shuffle = player.shuffleModeEnabled
        val (repeatIcon, repeatName) = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL to R.string.playback_repeat_all
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE to R.string.playback_repeat_one
            else -> CommandButton.ICON_REPEAT_OFF to R.string.playback_repeat_off
        }
        return listOf(
            button(
                icon = if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
                name = context.getString(if (shuffle) R.string.playback_shuffle_on else R.string.playback_shuffle_off),
                command = SHUFFLE,
            ),
            button(repeatIcon, context.getString(repeatName), REPEAT),
        )
    }

    /** Off -> All -> One -> Off, like the Now Playing screen. */
    fun nextRepeatMode(mode: Int): Int = when (mode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    private fun button(icon: Int, name: String, command: SessionCommand) = CommandButton.Builder(icon)
        .setDisplayName(name)
        .setSessionCommand(command)
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()
}

/**
 * Sonora's session callback: restores Sonora's own controller's URIs ([UriResolvingCallback]; an external controller
 * never gets to name a URI to play, see [onAddMediaItems]), offers [MediaButtons] and runs them
 * (shuffle through the [owner], so the real queue is reordered), and resumes the saved queue ([resumption]) when a
 * play request reaches an empty session (Bluetooth, the system's resumption controls) or at start ([restoreIfEmpty]).
 */
@OptIn(UnstableApi::class)
internal open class SonoraSessionCallback(
    private val owner: SessionQueueOwner,
    private val resumption: PlaybackResumption,
    private val scope: CoroutineScope,
    private val ownUid: Int = Process.myUid(),
) : MediaLibrarySession.Callback {

    /** Controllers whose car or voice request just failed: their queued prepare and play are held back. */
    val failedRequests = FailedRequestHold()

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(MediaButtons.SHUFFLE)
                    .add(MediaButtons.REPEAT)
                    .build(),
            )
            .build()

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val player = session.player
        when (customCommand.customAction) {
            MediaButtons.SHUFFLE.customAction -> owner.setShuffle(!player.shuffleModeEnabled)
            MediaButtons.REPEAT.customAction -> player.repeatMode = MediaButtons.nextRepeatMode(player.repeatMode)
            else -> return super.onCustomCommand(session, controller, customCommand, args)
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    /**
     * Whether [controller] is Sonora itself (the app's [Media3PlaybackController]). Decided by uid, which the system
     * vouches for; the package name a controller announces is not trusted.
     */
    protected fun isOwn(controller: MediaSession.ControllerInfo): Boolean = controller.uid == ownUid

    /**
     * Sonora's own controller names the URI of each track it queues ([UriResolvingCallback] restores it). Any other
     * controller (Android Auto, Bluetooth, System UI, another app) could name any URI, so its items are refused: what
     * it legitimately asks for (a car song id, a voice request) is resolved by [SonoraLibraryCallback] before this,
     * and resumption does not come through here. A refused request leaves the player as it was, and holds back the
     * play queued behind it ([failedRequests]).
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        if (isOwn(controller)) return UriResolvingCallback.onAddMediaItems(mediaSession, controller, mediaItems)
        failedRequests.failed(controller)
        return Futures.immediateFailedFuture(SecurityException("Only Sonora itself may queue its own media items"))
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            val snapshot = resumption.load()
            if (snapshot == null) {
                result.setException(NoSuchElementException("No saved queue to resume"))
                return@launch
            }
            // Only a real resumption changes the player; a metadata request (system resumption card) does not.
            if (isForPlayback) prepareRestore(mediaSession.player, snapshot)
            result.set(PlaybackResumption.mediaItems(snapshot))
        }
        return result
    }

    /** At start: shows the saved queue paused at its position, unless something is already queued by then. */
    fun restoreIfEmpty(player: Player) {
        scope.launch {
            val snapshot = resumption.load() ?: return@launch
            if (player.mediaItemCount > 0) return@launch
            prepareRestore(player, snapshot)
            val items = PlaybackResumption.mediaItems(snapshot)
            // Not prepared: nothing is opened (and no notification shown) until the user presses play.
            player.setMediaItems(items.mediaItems, items.startIndex, items.startPositionMs)
        }
    }

    /** Modes first, so the queue owner never mistakes the restored shuffle flag for a toggle to apply. */
    private fun prepareRestore(player: Player, snapshot: PlaybackSnapshot) {
        owner.expectRestore(snapshot.toQueue())
        player.repeatMode = when (snapshot.repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        player.shuffleModeEnabled = snapshot.shuffled
    }
}

/**
 * Builds the session the way [PlaybackService] runs it: a media library (browsable by Android Auto when the callback is
 * a [SonoraLibraryCallback]) whose buttons follow the player's shuffle and repeat.
 */
@OptIn(UnstableApi::class)
internal object SonoraSession {
    fun build(
        context: Context,
        player: Player,
        callback: SonoraSessionCallback,
        sessionActivity: PendingIntent?,
    ): MediaLibrarySession {
        // A failed car or voice request does not let the play queued behind it start the old queue.
        val holding = HoldingPlayer(player, callback.failedRequests)
        val builder = MediaLibrarySession.Builder(context, holding, callback)
            .setMediaButtonPreferences(MediaButtons.forPlayer(context, player))
        sessionActivity?.let(builder::setSessionActivity)
        val session = builder.build()
        holding.asking = { session.controllerForCurrentRequest }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) {
                    session.setMediaButtonPreferences(MediaButtons.forPlayer(context, player))
                }
            }
        })
        return session
    }
}
