package io.sonora.player.wear.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.sonora.core.watchsync.remote.QueueDirection
import io.sonora.player.wear.R
import io.sonora.player.wear.playback.QueueEntry
import io.sonora.player.wear.remote.RemoteUiState
import io.sonora.player.wear.remote.remoteQueueUiState
import io.sonora.player.wear.remote.toPlaybackUiState

/**
 * Now Playing of the phone: the same screen as the watch's own player, fed by the phone's state, with the crown on the
 * phone's volume. When there is no phone, or it plays nothing, a message says so.
 */
@Composable
fun RemoteNowPlayingScreen(
    ui: RemoteUiState,
    volume: VolumeControl,
    positionMs: () -> Long,
    onSetPlaying: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    when (ui) {
        RemoteUiState.NoPhone -> NoPhoneMessage()
        RemoteUiState.Connecting -> RemoteMessage(R.string.wear_remote_connecting, body = null)
        is RemoteUiState.Ready -> {
            val playback = ui.state.toPlaybackUiState()
            if (playback == null) {
                RemoteMessage(R.string.wear_remote_idle_title, R.string.wear_remote_idle_body)
            } else {
                NowPlayingScreen(
                    playback = playback,
                    volume = volume,
                    positionMs = positionMs,
                    onTogglePlayPause = { onSetPlaying(!playback.playWhenReady) },
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onOpenQueue = onOpenQueue,
                    volumeLabel = R.string.wear_remote_volume_percent,
                    positionKey = ui.receivedAt,
                )
            }
        }
    }
}

/** The phone's queue, on the watch's own queue screen: jump to a song, move it, take it out. */
@Composable
fun RemoteQueueScreen(
    ui: RemoteUiState,
    onJump: (QueueEntry) -> Unit,
    onMove: (QueueEntry, QueueDirection) -> Unit,
    onRemove: (QueueEntry) -> Unit,
) {
    when {
        ui is RemoteUiState.NoPhone -> NoPhoneMessage()
        ui is RemoteUiState.Ready && ui.state.queue != null ->
            QueueScreen(queue = remoteQueueUiState(ui.state), onJump = onJump, onMove = onMove, onRemove = onRemove)
        else -> RemoteMessage(R.string.wear_remote_connecting, body = null)
    }
}

@Composable
private fun NoPhoneMessage() = RemoteMessage(R.string.wear_remote_no_phone_title, R.string.wear_remote_no_phone_body)

@Composable
private fun RemoteMessage(@StringRes title: Int, @StringRes body: Int? = null) {
    ScreenScaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Icon(
                painterResource(R.drawable.ic_phone),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (body != null) {
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
