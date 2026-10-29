package io.sonora.player.wear

import android.app.Application
import com.google.android.gms.wearable.Wearable
import io.sonora.core.watchlink.WearableRemoteLink
import io.sonora.player.wear.history.FilePlayLog
import io.sonora.player.wear.history.PlayLog
import io.sonora.player.wear.kora.WatchKora
import io.sonora.player.wear.library.FilesTrackRepository
import io.sonora.player.wear.library.MediaMetadataTagReader
import io.sonora.player.wear.library.MusicStorage
import io.sonora.player.wear.library.TrackRepository
import io.sonora.player.wear.remote.RemoteClient
import io.sonora.player.wear.sync.WatchSyncReceiver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's one wiring point. The songs are the files of the music folder ([tracks]); the phone's transfer writes into that
 * same folder ([sync]), so the list needs nothing but a refresh when a transfer ends.
 */
class SonoraWearApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val tracks: TrackRepository by lazy {
        FilesTrackRepository(
            directory = { MusicStorage.directory(this) },
            readTags = MediaMetadataTagReader::read,
        )
    }

    /** What the watch played, for `media.history`; written by the playback service, read by the Kora node. */
    internal val playLog: PlayLog by lazy { FilePlayLog(File(filesDir, "plays.log")) }

    /** Receives the songs the phone sends. */
    val sync: WatchSyncReceiver by lazy {
        WatchSyncReceiver(
            directory = MusicStorage.directory(this),
            tracks = tracks,
            scope = appScope,
            channelClient = { Wearable.getChannelClient(this) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Last, so everything the node reads is wired: other Bihurri apps on the watch find the player as a peer.
        WatchKora.start(this)
    }

    /** The remote for the phone's playback; main thread only, as the Data Layer calls it there. */
    val remote: RemoteClient by lazy {
        RemoteClient(WearableRemoteLink(this), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
}
