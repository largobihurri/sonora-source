package io.sonora.player.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.sonora.player.wear.library.TrackQueue
import io.sonora.player.wear.library.TrackRepository
import io.sonora.player.wear.playback.PlaybackConnection
import io.sonora.player.wear.remote.RemoteClient
import io.sonora.player.wear.remote.RemoteMode
import io.sonora.player.wear.remote.RemoteRoutes
import io.sonora.player.wear.remote.effectiveMode
import io.sonora.player.wear.remote.remoteModeFor

private const val ROUTE_TRACKS = "tracks"
private const val ROUTE_NOW_PLAYING = "now_playing"
private const val ROUTE_QUEUE = "queue"

/**
 * The app: the song list, Now Playing one swipe to the right of it, and the queue, opened from Now Playing. Tapping a song queues the whole list from that
 * song and opens Now Playing. The Phone entry of the list opens the remote: the phone's Now Playing and its queue, through
 * [remote], which only talks to the phone while one of those screens shows and the app is on screen ([foreground]).
 * [openNowPlayingRequests] counts requests from outside (the media notification) to show Now Playing;
 * [openBluetoothSettings] is the headphones prompt's action.
 */
@Composable
fun WearApp(
    tracks: TrackRepository,
    connection: PlaybackConnection,
    volume: SystemVolume,
    remote: RemoteClient,
    foreground: Boolean,
    openNowPlayingRequests: Int,
    openBluetoothSettings: () -> Unit,
) {
    SonoraWearTheme {
        AppScaffold {
            val navController = rememberSwipeDismissableNavController()
            val trackList by tracks.tracks.collectAsState()
            val playback by connection.state.collectAsState()
            val queue by connection.queue.collectAsState()
            // The prompt is asked again each time playback starts waiting for an output, not while it keeps waiting.
            var prompt by remember { mutableStateOf(HeadphonesPromptState()) }
            LaunchedEffect(playback.waitingForOutput) { prompt = prompt.onWaitingChanged(playback.waitingForOutput) }
            val phone by remote.state.collectAsState()
            val route = navController.currentBackStackEntryFlow.collectAsState(initial = null).value?.destination?.route
            LaunchedEffect(route, foreground) { remote.setMode(effectiveMode(remoteModeFor(route), foreground)) }
            DisposableEffect(remote) { onDispose { remote.setMode(RemoteMode.Off) } }
            val phoneVolume = remember(remote) { VolumeControl(remote::volumeSteps) }
            LaunchedEffect(openNowPlayingRequests) {
                if (openNowPlayingRequests > 0) navController.navigate(ROUTE_NOW_PLAYING) { launchSingleTop = true }
            }

            SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_TRACKS) {
                composable(ROUTE_TRACKS) {
                    TrackListScreen(
                        tracks = trackList,
                        playback = playback,
                        onTrackClick = { track ->
                            connection.play(TrackQueue.startingAt(trackList.orEmpty(), track.id))
                            navController.navigate(ROUTE_NOW_PLAYING) { launchSingleTop = true }
                        },
                        onNowPlayingClick = { navController.navigate(ROUTE_NOW_PLAYING) { launchSingleTop = true } },
                        onPhoneClick = { navController.navigate(RemoteRoutes.NOW_PLAYING) { launchSingleTop = true } },
                    )
                }
                composable(ROUTE_NOW_PLAYING) {
                    NowPlayingScreen(
                        playback = playback,
                        volume = volume,
                        positionMs = connection::positionMs,
                        onTogglePlayPause = connection::togglePlayPause,
                        onPrevious = connection::previous,
                        onNext = connection::next,
                        onOpenQueue = { navController.navigate(ROUTE_QUEUE) { launchSingleTop = true } },
                    )
                }
                composable(ROUTE_QUEUE) {
                    QueueScreen(
                        queue = queue,
                        onJump = connection::jumpTo,
                        onMove = connection::move,
                        onRemove = connection::remove,
                    )
                }
                composable(RemoteRoutes.NOW_PLAYING) {
                    RemoteNowPlayingScreen(
                        ui = phone,
                        volume = phoneVolume,
                        positionMs = remote::positionMs,
                        onSetPlaying = remote::setPlaying,
                        onPrevious = remote::previous,
                        onNext = remote::next,
                        onOpenQueue = { navController.navigate(RemoteRoutes.QUEUE) { launchSingleTop = true } },
                    )
                }
                composable(RemoteRoutes.QUEUE) {
                    RemoteQueueScreen(
                        ui = phone,
                        onJump = remote::jump,
                        onMove = remote::move,
                        onRemove = remote::remove,
                    )
                }
            }
            HeadphonesPrompt(
                visible = prompt.isVisible(playback.waitingForOutput),
                onOpenSettings = {
                    prompt = prompt.dismissed()
                    openBluetoothSettings()
                },
                onDismiss = { prompt = prompt.dismissed() },
            )
        }
    }
}
