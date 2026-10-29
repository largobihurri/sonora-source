package io.sonora.core.watchport

import io.sonora.core.domain.model.Track
import io.sonora.core.watchsync.sender.WatchState
import kotlinx.coroutines.flow.StateFlow

/**
 * What the phone's UI uses to put music on the watch (the entry point of the "Send to watch" actions and the Watch screen;
 * both come in W4). Inject it with Hilt (`@Inject lateinit var` / constructor): the `full` build binds it in
 * `io.sonora.core.watchphone.di.WatchSyncModule`, the `play` build (no Google Play services) to [UnavailableWatchSync].
 *
 * One send runs at a time, in a foreground `dataSync` service that keeps the app alive with a progress notification, so it
 * goes on when the user leaves the app. Each song is encoded to Ogg Opus (the `WATCH_OPUS` profile) and streamed over the
 * Wearable Data Layer; a dropped link resumes where the watch stopped; songs already on the watch are not sent again.
 */
interface WatchSyncController {
    /** False in a build without the watch link: the screens then offer nothing about the watch. */
    val isAvailable: Boolean get() = true

    /** [WatchSyncState.Idle] at rest; [WatchSyncState.Running] with progress while sending; the result stays until [dismissResult]. */
    val state: StateFlow<WatchSyncState>

    /** Sends the album's songs, in album order. Returns false (and does nothing) when a send is already running. */
    fun sendAlbum(albumId: Long): Boolean

    /** Sends the playlist's songs, in playlist order, skipping entries whose file is gone. False when a send is running. */
    fun sendPlaylist(playlistId: Long): Boolean

    /** Sends these songs in this order. False when a send is running. */
    fun sendTracks(tracks: List<Track>): Boolean

    /** Stops the running send and returns to idle. What reached the watch stays there, and the next send resumes. */
    fun cancel()

    /** Clears a finished or failed result back to idle. Does nothing while sending. */
    fun dismissResult()

    /**
     * The watch's free space and the songs it holds. Fails with [WatchBusyException] while a send runs, or with the
     * connection's error (`WatchNotFoundException` when no watch is connected).
     */
    suspend fun readWatch(): Result<WatchState>

    /** Deletes songs from the watch by id (`WatchState.tracks[].id`); returns the ids it confirmed. Same failures as [readWatch]. */
    suspend fun removeFromWatch(trackIds: List<String>): Result<List<String>>
}
