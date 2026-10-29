package io.sonora.core.playback

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.sonora.core.domain.car.CarGroup
import io.sonora.core.domain.car.CarLabel
import io.sonora.core.domain.car.CarLibraryStatus
import io.sonora.core.domain.car.CarLibraryTree
import io.sonora.core.domain.car.CarMediaId
import io.sonora.core.domain.car.CarNode
import io.sonora.core.domain.car.CarQueue
import io.sonora.core.domain.car.CarStyle
import io.sonora.core.domain.car.CarTitle
import io.sonora.core.domain.car.CarVoicePlan
import io.sonora.core.domain.car.CarVoiceRequest
import io.sonora.core.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library side of the session for Android Auto: answers browse requests from [tree] as media items with Android
 * Auto's content-style hints (grids for albums and artists, lists for songs, group titles in mixed lists), and tells
 * subscribed browsers when the library changes. Requests run on [scope] (the session's thread), their library work
 * (database reads, search ranking, building the items) on [work]; a failure answers with an error, never throws.
 */
@OptIn(UnstableApi::class)
internal class CarBrowser(
    private val context: Context,
    private val tree: CarLibraryTree,
    private val scope: CoroutineScope,
    /** The car-readable artwork URI for a library artwork URI; `null` shows none. */
    private val artwork: (String) -> String? = { null },
    /** Builds the check of which songs can still be read (their folder grant is held); one per request. */
    private val readable: () -> (Track) -> Boolean = { { true } },
    /** Where the library work runs (database reads, search ranking, building nodes): off the session's thread. */
    private val work: CoroutineContext = Dispatchers.Default,
) {
    private val subscribed = LinkedHashSet<String>()
    private var watcher: Job? = null

    fun root(params: LibraryParams?): LibraryResult<MediaItem> {
        val item = folder(CarMediaId.Root.value, "", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, CarStyle.LIST)
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putBoolean(EXTRAS_KEY_SEARCH_SUPPORTED, true)
        }
        return LibraryResult.ofItem(item, LibraryParams.Builder().setExtras(extras).build())
    }

    /**
     * The children of [parentId], one page. Opening the root (Android Auto does on connecting) also tells [browser]
     * when there is nothing to play: an empty library, or folders Sonora can no longer read.
     */
    fun children(
        session: MediaSession,
        browser: ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = request {
        val id = CarMediaId.parse(parentId) ?: return@request badValue()
        val (items, status) = withContext(work) {
            val nodes = tree.children(id) ?: return@withContext null
            val status = if (id == CarMediaId.Root) tree.status(readable()) else CarLibraryStatus.READY
            nodes.page(page, pageSize).map(::toMediaItem) to status
        } ?: return@request badValue()
        when (status) {
            CarLibraryStatus.EMPTY -> report(session, browser, SessionError.ERROR_SESSION_SETUP_REQUIRED, R.string.car_error_empty)
            CarLibraryStatus.NO_ACCESS -> report(session, browser, SessionError.ERROR_SESSION_SETUP_REQUIRED, R.string.car_error_no_access)
            CarLibraryStatus.READY -> Unit
        }
        LibraryResult.ofItemList(items, params)
    }

    fun item(mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = request {
        val id = CarMediaId.parse(mediaId) ?: return@request badValue()
        val item = withContext(work) { tree.item(id)?.let(::toMediaItem) } ?: return@request badValue()
        LibraryResult.ofItem(item, null)
    }

    /**
     * What a tapped [song] plays: its whole list from it, turned into the session's items by [load]. Fails (the session
     * then leaves the player as it was) when the song is no longer there.
     */
    fun <T : Any> play(session: MediaSession, controller: ControllerInfo, song: CarMediaId.Song, load: (CarQueue) -> T):
        ListenableFuture<T> = launchFuture {
        val queue = withContext(work) { tree.queue(song) }
            ?: fail(session, controller, SessionError.ERROR_BAD_VALUE, R.string.car_error_gone)
        val playable = withContext(work) { queue.readable(readable()) }
            ?: fail(session, controller, SessionError.ERROR_SESSION_SETUP_REQUIRED, R.string.car_error_no_access)
        load(playable)
    }

    /** Starts a search for [browser]; it hears how many items match ([MediaLibrarySession.notifySearchResultChanged]). */
    fun search(session: MediaLibrarySession, browser: ControllerInfo, query: String, params: LibraryParams?): LibraryResult<Void> {
        scope.launch {
            val count = try {
                withContext(work) { tree.children(CarMediaId.Search(query))?.size ?: 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Car search failed", e)
                0
            }
            session.notifySearchResultChanged(browser, query, count, params)
        }
        return LibraryResult.ofVoid()
    }

    fun searchResult(session: MediaSession, browser: ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?) =
        children(session, browser, CarMediaId.Search(query).value, page, pageSize, params)

    /** What a voice request plays, turned into the session's items by [load] (which may fail, see [noMatch]). */
    fun <T : Any> voice(request: CarVoiceRequest, load: (CarVoicePlan) -> T): ListenableFuture<T> =
        launchFuture { load(withContext(work) { tree.voice(request) }) }

    /** Tells [controller] nothing matches [query], and fails the request that asked. */
    fun noMatch(session: MediaSession, controller: ControllerInfo, query: String): Nothing =
        fail(session, controller, SessionError.ERROR_NOT_SUPPORTED, R.string.car_no_match, query)

    /** [report]s the error and fails the request: the session then leaves the player as it was. */
    private fun fail(session: MediaSession, controller: ControllerInfo, code: Int, message: Int, vararg args: Any): Nothing {
        report(session, controller, code, message, *args)
        throw NoSuchElementException(context.getString(message, *args))
    }

    /**
     * Shows [message] in the car. Android Auto connects as a legacy browser, so the session puts the error in the
     * platform session's state, where the car reads it; it offers to open Sonora (on the phone, when safe).
     */
    private fun report(session: MediaSession, controller: ControllerInfo, code: Int, message: Int, vararg args: Any) {
        val extras = Bundle()
        session.sessionActivity?.let { openApp ->
            extras.putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, context.getString(R.string.car_error_action))
            extras.putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, openApp)
        }
        session.sendError(controller, SessionError(code, context.getString(message, *args), extras))
    }

    /** Keeps [parentId] for change notices; every subscribed list is re-announced when the library changes. */
    fun subscribe(session: MediaLibrarySession, parentId: String): LibraryResult<Void> {
        if (CarMediaId.parse(parentId) == null) return badValue()
        subscribed += parentId
        if (watcher == null) {
            watcher = scope.launch {
                tree.changes().flowOn(work).collect {
                    subscribed.toList().forEach { session.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
                }
            }
        }
        return LibraryResult.ofVoid()
    }

    fun unsubscribe(parentId: String): LibraryResult<Void> {
        subscribed -= parentId
        return LibraryResult.ofVoid()
    }

    fun toMediaItem(node: CarNode): MediaItem {
        val track = node.track
        val title = title(node.title)
        if (track == null) return folder(node.id.value, title, mediaType(node.id), CarStyle.childrenOf(node.id), node)
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setAlbumArtist(track.albumArtist)
            .setTrackNumber(track.trackNumber)
            .setDurationMs(track.durationMs.takeIf { it > 0 })
            .setArtworkUri(node.artworkUri?.let(artwork)?.toUri())
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(groupExtras(node.group))
            .build()
        return MediaItem.Builder().setMediaId(node.id.value).setMediaMetadata(metadata).build()
    }

    private fun folder(id: String, title: String, mediaType: Int, style: CarStyle, node: CarNode? = null): MediaItem {
        val extras = groupExtras(node?.group).apply {
            val hint = when (style) {
                CarStyle.GRID -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                CarStyle.LIST -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            }
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, hint)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, hint)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(node?.subtitle)
            .setArtist(node?.subtitle?.takeIf { node.id is CarMediaId.Album })
            .setArtworkUri(node?.artworkUri?.let(artwork)?.toUri())
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun groupExtras(group: CarGroup?) = Bundle().apply {
        if (group != null) putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle(group))
    }

    private fun mediaType(id: CarMediaId): Int = when (id) {
        is CarMediaId.Album -> MediaMetadata.MEDIA_TYPE_ALBUM
        is CarMediaId.Artist -> MediaMetadata.MEDIA_TYPE_ARTIST
        is CarMediaId.Playlist, CarMediaId.Favourites -> MediaMetadata.MEDIA_TYPE_PLAYLIST
        else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    }

    fun title(title: CarTitle): String = when (title) {
        is CarTitle.Text -> title.text
        is CarTitle.Label -> context.getString(
            when (title.label) {
                CarLabel.PLAYLISTS -> R.string.car_playlists
                CarLabel.ALBUMS -> R.string.car_albums
                CarLabel.ARTISTS -> R.string.car_artists
                CarLabel.SONGS -> R.string.car_songs
                CarLabel.FAVOURITES -> R.string.car_favourites
                CarLabel.RECENTLY_ADDED -> R.string.car_recently_added
                CarLabel.ALL_SONGS -> R.string.car_all_songs
            },
        )
    }

    private fun groupTitle(group: CarGroup): String = context.getString(
        when (group) {
            CarGroup.SONGS -> R.string.car_songs
            CarGroup.ALBUMS -> R.string.car_albums
            CarGroup.ARTISTS -> R.string.car_artists
            CarGroup.PLAYLISTS -> R.string.car_playlists
        },
    )

    /** Runs [block] on [scope]; anything it throws (a database error) answers as an I/O error. */
    private fun <T : Any> request(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> =
        launchFuture {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Car library request failed", e)
                LibraryResult.ofError<T>(SessionError.ERROR_IO)
            }
        }

    /** [block]'s result as a future, run on [scope]; it fails with what [block] throws, or is cancelled with [scope]. */
    private fun <T : Any> launchFuture(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try {
                result.set(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.setException(e)
            }
        }
        job.invokeOnCompletion { if (!result.isDone) result.cancel(false) }
        return result
    }

    private fun <T : Any> badValue(): LibraryResult<T> = LibraryResult.ofError<T>(SessionError.ERROR_BAD_VALUE)

    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0) return emptyList()
        val from = page.toLong() * pageSize
        if (from >= size) return emptyList()
        return subList(from.toInt(), minOf(size.toLong(), from + pageSize).toInt())
    }

    private companion object {
        const val TAG = "SonoraCar"

        /** Root hint: the app answers searches (Android Auto shows its search button). */
        const val EXTRAS_KEY_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}
