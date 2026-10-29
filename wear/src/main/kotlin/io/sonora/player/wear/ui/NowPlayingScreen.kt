package io.sonora.player.wear.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.sonora.player.wear.R
import io.sonora.player.wear.playback.PlaybackUiState
import kotlinx.coroutines.delay

/**
 * Now playing: title and artist, previous / play-pause / next, the song's progress around the screen's edge, and the crown
 * for the volume ([volume]: the watch's or, on the phone remote, the phone's; shown for a moment on the same ring under
 * [volumeLabel]). The button at the bottom opens the queue.
 */
@Composable
fun NowPlayingScreen(
    playback: PlaybackUiState,
    volume: VolumeControl,
    positionMs: () -> Long,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenQueue: () -> Unit,
    @StringRes volumeLabel: Int = R.string.wear_volume_percent,
    /** Changes when the song's position was set from outside (a seek on the phone), so a paused display catches up. */
    positionKey: Any? = null,
) {
    val focus = remember { FocusRequester() }
    val stepper = remember { RotaryVolumeStepper(pixelsPerStep = VOLUME_PIXELS_PER_STEP) }
    // The volume (percent) the crown just set, shown until it is quiet for a moment; null when not shown.
    var shownVolume by remember { mutableIntStateOf(-1) }
    var volumeTouch by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(volumeTouch) {
        if (volumeTouch == 0L) return@LaunchedEffect
        delay(VOLUME_SHOWN_MS)
        shownVolume = -1
    }
    LaunchedEffect(playback.mediaId, playback.playWhenReady, positionKey) {
        while (true) {
            position = positionMs()
            if (!playback.playWhenReady) break
            delay(POSITION_TICK_MS)
        }
    }

    ScreenScaffold {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { event ->
                    val steps = stepper.add(event.verticalScrollPixels)
                    if (steps != 0) {
                        shownVolume = volume.step(steps)
                        volumeTouch = System.nanoTime()
                    }
                    true
                }
                .focusRequester(focus)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            val showingVolume = shownVolume >= 0
            val progress = if (showingVolume) {
                shownVolume / 100f
            } else {
                progressFraction(position, playback.durationMs)
            }
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize().padding(2.dp),
                startAngle = 295f,
                endAngle = 245f,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (showingVolume) {
                    Text(
                        stringResource(volumeLabel, shownVolume),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        playback.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        playback.artist.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(),
                    ) { Icon(painterResource(R.drawable.ic_previous), contentDescription = stringResource(R.string.wear_previous)) }
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(IconButtonDefaults.LargeButtonSize),
                        colors = IconButtonDefaults.filledIconButtonColors(),
                    ) {
                        Icon(
                            painterResource(if (playback.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = stringResource(if (playback.playWhenReady) R.string.wear_pause else R.string.wear_play),
                        )
                    }
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
                        enabled = playback.hasNext,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(),
                    ) { Icon(painterResource(R.drawable.ic_next), contentDescription = stringResource(R.string.wear_next)) }
                }
            }
            if (playback.hasMedia) {
                IconButton(
                    onClick = onOpenQueue,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .size(QUEUE_BUTTON_SIZE),
                    colors = IconButtonDefaults.iconButtonColors(),
                ) { Icon(painterResource(R.drawable.ic_queue), contentDescription = stringResource(R.string.wear_queue_title)) }
            }
        }
    }
}

/** Crown rotation (pixels) that makes one volume step. */
private const val VOLUME_PIXELS_PER_STEP = 40f
private const val VOLUME_SHOWN_MS = 1_500L
private const val POSITION_TICK_MS = 500L
private val QUEUE_BUTTON_SIZE = 32.dp
