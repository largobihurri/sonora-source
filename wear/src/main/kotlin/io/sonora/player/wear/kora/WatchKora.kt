package io.sonora.player.wear.kora

import android.util.Log
import eus.bihurri.kora.android.KoraAndroid
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.core.AuditLog
import io.sonora.player.wear.R
import io.sonora.player.wear.SonoraWearApp
import io.sonora.player.wear.library.WatchTrack
import io.sonora.player.wear.library.loaded

/**
 * The watch app as a Kora node: provides `media.library` (the songs stored on the watch), `media.playback` (the watch's
 * own player) and `media.history` (the songs it played itself) to the Bihurri apps signed with the same key, so they find it as an on-device peer.
 *
 * The watch has no settings screen, so there is no external-access switch: [watchCallerPolicy] lets this app and its signed
 * peers use every scope and refuses everyone else, the adb shell included.
 *
 * [start] runs once, at the end of `Application.onCreate`. It builds nothing heavy: the player connection and the song list
 * are only created by the first call that needs them.
 */
internal object WatchKora {
    private const val TAG = "SonoraWearKora"

    fun start(app: SonoraWearApp) {
        val songs: suspend () -> List<WatchTrack> = { app.tracks.loaded() }
        val player = MediaControllerPlayer(app)
        KoraAndroid.install(app, policy = watchCallerPolicy(), audit = AuditLog { Log.d(TAG, it.toString()) }) {
            provide(MediaLibrary) {
                WatchLibraryHandlers(songs) { app.getString(R.string.wear_kora_all_songs) }.register(this)
            }
            provide(MediaPlayback) {
                WatchPlaybackHandlers(player, songs, System::currentTimeMillis).register(this)
            }
            provide(MediaHistory) {
                WatchHistoryHandlers(log = { app.playLog }, songs = songs).register(this)
            }
        }
    }
}
