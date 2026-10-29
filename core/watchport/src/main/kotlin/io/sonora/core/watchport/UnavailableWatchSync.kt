package io.sonora.core.watchport

import io.sonora.core.domain.model.Track
import io.sonora.core.watchsync.WatchNotFoundException
import io.sonora.core.watchsync.sender.WatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The controller of a build without the watch link (the `play` build carries no Google Play services): nothing is
 * offered ([isAvailable] is false), and a call that still comes in starts nothing and finds no watch.
 */
object UnavailableWatchSync : WatchSyncController {
    override val isAvailable: Boolean get() = false

    override val state: StateFlow<WatchSyncState> = MutableStateFlow<WatchSyncState>(WatchSyncState.Idle).asStateFlow()

    override fun sendAlbum(albumId: Long): Boolean = false

    override fun sendPlaylist(playlistId: Long): Boolean = false

    override fun sendTracks(tracks: List<Track>): Boolean = false

    override fun cancel() = Unit

    override fun dismissResult() = Unit

    override suspend fun readWatch(): Result<WatchState> = Result.failure(WatchNotFoundException())

    override suspend fun removeFromWatch(trackIds: List<String>): Result<List<String>> = Result.failure(WatchNotFoundException())
}
