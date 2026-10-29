package io.sonora.feature.library

import io.sonora.core.domain.model.Track
import io.sonora.core.watchport.WatchSyncController
import io.sonora.core.watchport.WatchSyncState
import io.sonora.core.watchsync.sender.WatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A [WatchSyncController] that records what the menu asks of it; a send it accepts is [WatchSyncState.Running] until [sync] says otherwise. */
internal class FakeWatchController : WatchSyncController {
    val sync = MutableStateFlow<WatchSyncState>(WatchSyncState.Idle)
    override val state: StateFlow<WatchSyncState> = sync

    /** Whether a send is accepted; `false` stands for one already running. */
    var accepts = true

    /** When set, an accepted send leaves [sync] as it is (a stale result, say) until the test flips it: a controller that is slow to say `Running`. */
    var startsLate = false
    val calls = mutableListOf<String>()

    private fun start(call: String): Boolean {
        calls += call
        if (accepts && !startsLate) sync.value = WatchSyncState.Running(progress = null, songCount = 0)
        return accepts
    }

    override fun sendAlbum(albumId: Long) = start("album:$albumId")
    override fun sendPlaylist(playlistId: Long) = start("playlist:$playlistId")
    override fun sendTracks(tracks: List<Track>) = start("tracks:" + tracks.joinToString(",") { it.id.toString() })
    override fun cancel() {
        sync.value = WatchSyncState.Idle
    }
    override fun dismissResult() = Unit
    override suspend fun readWatch(): Result<WatchState> = Result.failure(IllegalStateException("not used by the menu"))
    override suspend fun removeFromWatch(trackIds: List<String>): Result<List<String>> = Result.failure(IllegalStateException("not used by the menu"))
}
