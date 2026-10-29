package io.sonora.player.wear.sync

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import io.sonora.core.watchlink.syncStreams
import io.sonora.core.watchsync.SyncStreams
import io.sonora.core.watchsync.receiver.MusicLibraryStore
import io.sonora.core.watchsync.receiver.SyncReceiver
import io.sonora.player.wear.library.TrackRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The watch's end of the music transfer: serves each channel the phone opens with the shared [SyncReceiver], writing into
 * the app's music folder, and refreshes the song list once a session that changed the folder ends (a scan per song would
 * make a long transfer quadratic). Sessions run on [scope], the application's, so they do not depend on any screen or on the
 * listener service that was woken for them.
 *
 * @param directory The music folder (`filesDir/music`); the same one the song list reads.
 * @param channelClient The Data Layer's channel client, needed only when a channel arrives.
 */
class WatchSyncReceiver(
    directory: File,
    private val tracks: TrackRepository,
    private val scope: CoroutineScope,
    private val channelClient: () -> ChannelClient,
) {
    private val refresher = LibraryRefresher { tracks.refresh() }
    private val receiver = SyncReceiver(MusicLibraryStore(directory), onLibraryChanged = refresher::changed)
    private val sessions = MutableStateFlow(0)

    /**
     * How many sessions are running. A new connection makes [SyncReceiver] close the previous one's streams and wait for it
     * to finish before it runs, so this is 0 or 1 except for the moment the old session winds up; its changes are tracked
     * by [LibraryRefresher], not per session, so overlapping sessions cannot lose each other's refresh.
     */
    val activeSessions: StateFlow<Int> = sessions

    /** Serves the sync [channel] a phone opened. */
    fun accept(channel: ChannelClient.Channel): Job = serve { channelClient().syncStreams(channel) }

    internal fun serve(open: suspend () -> SyncStreams): Job {
        sessions.update { it + 1 }
        return scope.launch {
            try {
                receiver.serve(open())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A dropped connection: what arrived stays as a partial file and the phone resumes it.
                Log.w(TAG, "Sync session ended: ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    runCatching { refresher.refreshIfChanged() }
                    sessions.update { it - 1 }
                }
            }
        }
    }

    private companion object {
        const val TAG = "SonoraWearSync"
    }
}
