package io.sonora.player.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import io.sonora.core.watchsync.remote.QueueDirection
import io.sonora.player.wear.R
import io.sonora.player.wear.playback.QueueEntry
import io.sonora.player.wear.playback.QueueUiState

/**
 * The queue: the playing song, then the upcoming ones. Tapping a song opens its controls under it (play it now, move it up
 * or down, take it out); tapping it again closes them. The playing song can only be taken out (the player then goes on to
 * the next one).
 */
@Composable
fun QueueScreen(
    queue: QueueUiState,
    onJump: (QueueEntry) -> Unit,
    onMove: (QueueEntry, QueueDirection) -> Unit,
    onRemove: (QueueEntry) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    // The row whose controls are open; it follows its song when moved, and is forgotten once the song leaves the queue.
    var selectedKey by remember { mutableStateOf<String?>(null) }
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item(key = "header") {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.wear_queue_title)) }
            }
            if (queue.entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.wear_queue_empty),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .transformedHeight(this, spec),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                return@TransformingLazyColumn
            }
            for (entry in queue.entries) {
                val open = entry.key == selectedKey
                item(key = entry.key) {
                    Button(
                        onClick = { selectedKey = if (open) null else entry.key },
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = if (entry.isCurrent) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                        secondaryLabel = if (entry.artist != null) {
                            { Text(entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } else {
                            null
                        },
                    ) { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (open) {
                    item(key = "${entry.key}/controls") {
                        QueueControls(
                            entry = entry,
                            onJump = { onJump(entry) },
                            onMove = { direction -> onMove(entry, direction) },
                            onRemove = {
                                selectedKey = null
                                onRemove(entry)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, spec),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueControls(
    entry: QueueEntry,
    onJump: () -> Unit,
    onMove: (QueueDirection) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(R.drawable.ic_play, R.string.wear_queue_play_here, enabled = !entry.isCurrent, onClick = onJump)
        ControlButton(R.drawable.ic_arrow_up, R.string.wear_queue_move_up, enabled = entry.canMoveUp) { onMove(QueueDirection.Up) }
        ControlButton(R.drawable.ic_arrow_down, R.string.wear_queue_move_down, enabled = entry.canMoveDown) { onMove(QueueDirection.Down) }
        ControlButton(R.drawable.ic_remove, R.string.wear_queue_remove, enabled = entry.canRemove, onClick = onRemove)
    }
}

@Composable
private fun ControlButton(icon: Int, description: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(CONTROL_SIZE),
        enabled = enabled,
        colors = IconButtonDefaults.filledTonalIconButtonColors(),
    ) { Icon(painterResource(icon), contentDescription = stringResource(description)) }
}

private val CONTROL_SIZE = 40.dp
private val CONTROL_SPACING = 3.dp
