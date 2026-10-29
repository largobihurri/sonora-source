package io.sonora.player.wear

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.sonora.player.wear.playback.NowPlayingLaunch
import io.sonora.player.wear.playback.PlaybackConnection
import io.sonora.player.wear.ui.SystemVolume
import io.sonora.player.wear.ui.WearApp
import kotlinx.coroutines.launch

/** The watch app's one screen host: lists the songs, plays them through the playback service. */
class MainActivity : ComponentActivity() {
    private val connection by lazy { PlaybackConnection(this) }
    private var openNowPlayingRequests by mutableIntStateOf(0)

    /** The phone remote talks only while the app is on screen. */
    private var foreground by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(NowPlayingLaunch.EXTRA_OPEN_NOW_PLAYING, false)) openNowPlayingRequests++
        val tracks = (application as SonoraWearApp).tracks
        val volume = SystemVolume(this)
        setContent {
            WearApp(
                tracks = tracks,
                connection = connection,
                volume = volume,
                remote = (application as SonoraWearApp).remote,
                foreground = foreground,
                openNowPlayingRequests = openNowPlayingRequests,
                openBluetoothSettings = ::openBluetoothSettings,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(NowPlayingLaunch.EXTRA_OPEN_NOW_PLAYING, false)) openNowPlayingRequests++
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        connection.connect()
        // The folder may have changed while the app was away (a transfer finished).
        lifecycleScope.launch { (application as SonoraWearApp).tracks.refresh() }
    }

    override fun onStop() {
        foreground = false
        // The music goes on in the playback service.
        connection.disconnect()
        super.onStop()
    }

    private fun openBluetoothSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }
}
