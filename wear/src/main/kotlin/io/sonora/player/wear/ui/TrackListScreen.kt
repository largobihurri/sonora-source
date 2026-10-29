package io.sonora.player.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import io.sonora.player.wear.R
import io.sonora.player.wear.library.WatchTrack
import io.sonora.player.wear.playback.PlaybackUiState

/**
 * The songs on the watch. [tracks] is `null` while the first load runs (nothing is shown then, so the empty message does
 * not flash), and empty when there is no music: the message then says to send some from the phone. The Phone entry (the
 * remote for the phone's playback) is there either way.
 */
@Composable
fun TrackListScreen(
    tracks: List<WatchTrack>?,
    playback: PlaybackUiState,
    onTrackClick: (WatchTrack) -> Unit,
    onNowPlayingClick: () -> Unit,
    onPhoneClick: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            if (tracks == null) return@TransformingLazyColumn
            if (tracks.isEmpty()) {
                item {
                    EmptyLibrary(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, spec),
                    )
                }
                item { PhoneEntry(onPhoneClick, Modifier.fillMaxWidth().transformedHeight(this, spec), SurfaceTransformation(spec)) }
                return@TransformingLazyColumn
            }
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_tracks_title)) }
            }
            if (playback.hasMedia) {
                item {
                    Button(
                        onClick = onNowPlayingClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        icon = { Icon(painterResource(R.drawable.ic_music_note), contentDescription = null) },
                        secondaryLabel = { Text(playback.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    ) { Text(stringResource(R.string.wear_now_playing_entry), maxLines = 1) }
                }
            }
            item { PhoneEntry(onPhoneClick, Modifier.fillMaxWidth().transformedHeight(this, spec), SurfaceTransformation(spec)) }
            items(tracks, key = { it.id }) { track ->
                val current = track.id == playback.mediaId
                Button(
                    onClick = { onTrackClick(track) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = if (current) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = if (track.artist != null) {
                        { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else {
                        null
                    },
                ) { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun PhoneEntry(onClick: () -> Unit, modifier: Modifier, transformation: SurfaceTransformation) {
    Button(
        onClick = onClick,
        modifier = modifier,
        transformation = transformation,
        icon = { Icon(painterResource(R.drawable.ic_phone), contentDescription = null) },
    ) { Text(stringResource(R.string.wear_phone_entry), maxLines = 1) }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.wear_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.wear_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
