package io.sonora.player.wear.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.sonora.player.wear.R
import io.sonora.player.wear.SonoraWearApp
import io.sonora.player.wear.history.PlayLogListener
import io.sonora.player.wear.library.TrackRepository
import io.sonora.player.wear.library.WatchTrack
import io.sonora.player.wear.library.loaded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Plays the watch's songs in the background: an ExoPlayer ([WearPlayer]) behind a Media3 library session, so the screens, the
 * system's media controls and the watch face's media shortcut all drive the same player, and a browsing client can list the
 * songs (a flat library: one root, every song a child of it). It stops with the task once nothing is playing.
 */
class WearPlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.wear_playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_sonora) },
        )
        val app = application as SonoraWearApp
        val player = WearPlayer.build(this)
        player.addListener(PlayLogListener(app.playLog, scope, Dispatchers.IO, Dispatchers.Main.immediate))
        session = MediaLibrarySession.Builder(this, player, LibraryCallback(app.tracks, scope))
            .setSessionActivity(sessionActivity())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Tapping the notification or the media controls opens the app on Now Playing. */
    private fun sessionActivity(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(
            this,
            0,
            launch
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(NowPlayingLaunch.EXTRA_OPEN_NOW_PLAYING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val CHANNEL_ID = "playback"
    }
}

/** The one browsable node every song hangs from. */
internal const val LIBRARY_ROOT_ID = "sonora_root"

/**
 * The session's answers to controllers and browsers: the song library is [library]'s list, and an item that arrives with
 * only its id (a browser picked it) or with only a request URI is completed before it reaches the player.
 */
internal class LibraryCallback(
    private val library: TrackRepository,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        if (parentId != LIBRARY_ROOT_ID) {
            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        } else {
            val items = songs().pageOf(page, pageSize).map(WatchTrack::toMediaItem)
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        val item = if (mediaId == LIBRARY_ROOT_ID) rootItem() else songs().firstOrNull { it.id == mediaId }?.toMediaItem()
        if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = future {
        val tracks = songs().associateBy { it.id }
        mediaItems.map { item ->
            val uri = item.requestMetadata.mediaUri
            when {
                item.localConfiguration != null -> item
                uri != null -> item.buildUpon().setUri(uri).build()
                else -> tracks[item.mediaId]?.toMediaItem() ?: item
            }
        }.toMutableList()
    }

    private suspend fun songs(): List<WatchTrack> = library.loaded()

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch {
            try {
                result.set(block())
            } catch (cancelled: CancellationException) {
                result.cancel(false)
                throw cancelled
            } catch (failure: Throwable) {
                result.setException(failure)
            }
        }
        return result
    }

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(LIBRARY_ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()
}
