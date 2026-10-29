package io.sonora.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.sonora.core.domain.car.CarMediaId
import io.sonora.core.domain.car.CarVoicePlan
import io.sonora.core.domain.model.Track
import kotlinx.coroutines.CoroutineScope

/**
 * The session's library side (what Android Auto browses, see [CarBrowser]), on top of Sonora's playback callback:
 * commands, buttons, resumption and the app's own items stay as [SonoraSessionCallback] handles them.
 */
@OptIn(UnstableApi::class)
internal class SonoraLibraryCallback(
    private val queueOwner: SessionQueueOwner,
    resumption: PlaybackResumption,
    scope: CoroutineScope,
    private val car: CarBrowser,
) : SonoraSessionCallback(queueOwner, resumption, scope) {

    /** Never fails: a failed root makes legacy browsers (Android Auto) fail to connect at all. */
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(car.root(params))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = car.children(session, browser, parentId, page, pageSize, params)

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = car.item(mediaId)

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(car.subscribe(session, parentId))

    override fun onUnsubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
    ): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(car.unsubscribe(parentId))

    /**
     * A song tapped in the car arrives as one item naming it: it plays its whole list from it, unshuffled, as a queue
     * the app's queue owner adopts with full track details. Anything else (the app's own items) is handled as before.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        failedRequests.release(controller)
        val item = mediaItems.singleOrNull()
        val song = item?.let { CarMediaId.parse(it.mediaId) } as? CarMediaId.Song
        if (song != null) {
            return car.play(mediaSession, controller, song) { load(mediaSession.player, it.tracks, it.startIndex, shuffle = false) }
                .holdingPlayOnFailure(controller)
        }
        val query = item?.requestMetadata?.searchQuery
        if (query != null && item.localConfiguration == null && item.requestMetadata.mediaUri == null) {
            return car.voice(CarVoice.request(query, item.requestMetadata.extras)) { plan ->
                when (plan) {
                    is CarVoicePlan.Play -> load(mediaSession.player, plan.queue.tracks, plan.queue.startIndex, shuffle = false)
                    is CarVoicePlan.Anything -> resumeOrShuffle(mediaSession.player, plan.songs)
                        ?: car.noMatch(mediaSession, controller, query)
                    CarVoicePlan.NoMatch -> car.noMatch(mediaSession, controller, query)
                }
            }.holdingPlayOnFailure(controller)
        }
        return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
    }

    /**
     * A failed request leaves the queue as it was; the prepare and play [controller] queued behind it are then held
     * back, so they do not start that old queue instead.
     */
    private fun <T> ListenableFuture<T>.holdingPlayOnFailure(controller: MediaSession.ControllerInfo): ListenableFuture<T> =
        also { future ->
            Futures.addCallback(
                future,
                object : FutureCallback<T> {
                    override fun onSuccess(result: T) = Unit

                    override fun onFailure(t: Throwable) = failedRequests.failed(controller)
                },
                MoreExecutors.directExecutor(),
            )
        }

    /** "Play music": what is queued, where it was; with nothing queued, the whole library shuffled. */
    private fun resumeOrShuffle(player: Player, songs: List<Track>): MediaSession.MediaItemsWithStartPosition? {
        if (player.mediaItemCount > 0) {
            val items = List(player.mediaItemCount, player::getMediaItemAt)
            return MediaSession.MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
        }
        return songs.takeIf { it.isNotEmpty() }?.let { load(player, it, 0, shuffle = true) }
    }

    /** Hands [tracks] to the queue owner first, then sets the shuffle flag, so the owner never takes it for a toggle. */
    private fun load(player: Player, tracks: List<Track>, startIndex: Int, shuffle: Boolean): MediaSession.MediaItemsWithStartPosition {
        val queue = queueOwner.queueFor(tracks, startIndex, shuffle)
        queueOwner.expectRestore(queue)
        player.shuffleModeEnabled = queue.shuffled
        return MediaSession.MediaItemsWithStartPosition(queue.items.map(MediaItemMapper::toMediaItem), queue.currentIndex, 0L)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(car.search(session, browser, query, params))

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = car.searchResult(session, browser, query, page, pageSize, params)
}
