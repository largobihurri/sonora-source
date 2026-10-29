package io.sonora.core.watchphone

import android.util.Log
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.PlaylistResolution
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.watchphone.di.WatchSyncScope
import io.sonora.core.watchport.WatchBusyException
import io.sonora.core.watchport.WatchSyncController
import io.sonora.core.watchport.WatchSyncFailure
import io.sonora.core.watchport.WatchSyncState
import io.sonora.core.watchsync.sender.SendCandidate
import io.sonora.core.watchsync.sender.WatchSender
import io.sonora.core.watchsync.sender.WatchState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Starts the foreground service that keeps a send alive; [WatchSyncService] stops itself when the send ends. */
fun interface WatchSyncForeground {
    fun start()
}

/**
 * [WatchSyncController] over the [WatchSender]. The send runs on [scope] (the application's), not on a screen, so it
 * outlives the UI; the foreground service only keeps the process alive and shows the notification from [state].
 */
@Singleton
class DefaultWatchSyncController @Inject constructor(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val sender: WatchSender,
    private val foreground: WatchSyncForeground,
    @WatchSyncScope private val scope: CoroutineScope,
) : WatchSyncController {
    private val mutableState = MutableStateFlow<WatchSyncState>(WatchSyncState.Idle)
    override val state: StateFlow<WatchSyncState> = mutableState

    private val lock = Any()
    private var job: Job? = null
    private var run = 0L

    override fun sendAlbum(albumId: Long): Boolean =
        start { SendSelection.forAlbum(library.tracksForAlbum(albumId).first()) }

    override fun sendPlaylist(playlistId: Long): Boolean = start {
        val playlist = playlists.playlist(playlistId).first() ?: return@start emptyList()
        val byUri = library.tracks().first().associateBy { it.uri }
        SendSelection.forPlaylist(PlaylistResolution.resolve(playlist, byUri))
    }

    override fun sendTracks(tracks: List<Track>): Boolean = start { SendSelection.forTracks(tracks) }

    override fun cancel() {
        synchronized(lock) {
            if (mutableState.value !is WatchSyncState.Running) return
            run++ // whatever the cancelled send reports from now on is stale
            job?.cancel()
            job = null
            mutableState.value = WatchSyncState.Idle
        }
    }

    override fun dismissResult() {
        synchronized(lock) {
            if (mutableState.value is WatchSyncState.Finished || mutableState.value is WatchSyncState.Failed) {
                mutableState.value = WatchSyncState.Idle
            }
        }
    }

    override suspend fun readWatch(): Result<WatchState> = whenNotSending { sender.readState() }

    override suspend fun removeFromWatch(trackIds: List<String>): Result<List<String>> =
        whenNotSending { sender.remove(trackIds) }

    private suspend fun <T> whenNotSending(block: suspend () -> T): Result<T> {
        if (mutableState.value is WatchSyncState.Running) return Result.failure(WatchBusyException())
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun start(select: suspend () -> List<SendCandidate>): Boolean = synchronized(lock) {
        if (mutableState.value is WatchSyncState.Running) return false
        val mine = ++run
        mutableState.value = WatchSyncState.Running(progress = null, songCount = 0)
        foreground.start()
        job = scope.launch { send(mine, select) }
        true
    }

    private suspend fun send(mine: Long, select: suspend () -> List<SendCandidate>) {
        fun publish(next: WatchSyncState) = synchronized(lock) { if (run == mine) mutableState.value = next }
        try {
            val candidates = select()
            if (candidates.isEmpty()) {
                publish(WatchSyncState.Failed(WatchSyncFailure.NothingToSend))
                return
            }
            publish(WatchSyncState.Running(progress = null, songCount = candidates.size))
            val report = sender.send(candidates) { progress -> publish(WatchSyncState.Running(progress, candidates.size)) }
            report.sent.forEach {
                Log.i(TAG, "sent ${it.id} ${it.transferredBytes} B in ${it.elapsedMs} ms (resumed from ${it.resumedFrom})")
            }
            Log.i(TAG, "send done: ${report.sent.size} sent, ${report.skipped.size} skipped, ${report.failed.size} failed, " +
                "${report.attempts} connection(s), ${report.bytesPerSecond?.toLong() ?: 0} B/s")
            publish(WatchSyncState.Finished(report))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            publish(WatchSyncState.Failed(WatchSyncFailure.of(e)))
        }
    }

    private companion object {
        const val TAG = "SonoraWatchSync"
    }
}
