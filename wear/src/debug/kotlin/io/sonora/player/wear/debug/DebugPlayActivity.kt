package io.sonora.player.wear.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.sonora.player.wear.SonoraWearApp
import io.sonora.player.wear.library.TrackQueue
import io.sonora.player.wear.playback.PlaybackConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only (it lives in `src/debug`): drives playback with no sound and no touch, for checks on a real watch.
 *
 * - `am start -n io.sonora.player/.debug.DebugPlayActivity` sets the app's own player volume to 0 (`player.volume`, never the
 *   watch's volume) and plays the first song of the list.
 * - `... --es action pause` pauses it.
 *
 * Nothing here can raise a volume, and the player keeps its volume 0 until the service's process restarts.
 * Results go to logcat under [TAG].
 */
class DebugPlayActivity : ComponentActivity() {
    private val connection by lazy { PlaybackConnection(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_PLAY
        connection.connect { controller ->
            lifecycleScope.launch {
                when (action) {
                    ACTION_PAUSE -> controller.pause()
                    else -> {
                        val repository = (application as SonoraWearApp).tracks
                        repository.refresh()
                        val tracks = repository.tracks.value.orEmpty()
                        if (tracks.isEmpty()) {
                            Log.w(TAG, "No songs in the music folder")
                        } else {
                            controller.volume = 0f
                            connection.play(TrackQueue(tracks, 0))
                        }
                    }
                }
                // Stay visible a moment, so the service may start in the foreground, and report what the player did.
                delay(SETTLE_MS)
                val state = connection.state.value
                Log.i(TAG, "action=$action volume=${controller.volume} playWhenReady=${state.playWhenReady} " +
                    "waitingForOutput=${state.waitingForOutput} isPlaying=${controller.isPlaying} title=${state.title}")
                finish()
            }
        }
    }

    override fun onDestroy() {
        connection.disconnect()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SonoraWearDebug"
        const val EXTRA_ACTION = "action"
        const val ACTION_PLAY = "play"
        const val ACTION_PAUSE = "pause"
        const val SETTLE_MS = 3_000L
    }
}
