package io.sonora.feature.settings.watch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.watchsync.ManifestTrack
import io.sonora.feature.settings.ActionRow
import io.sonora.feature.settings.R
import io.sonora.feature.settings.SectionFooter
import io.sonora.feature.settings.SectionHeader
import io.sonora.feature.settings.SettingsGroup

/**
 * Settings › Watch: whether the watch is reachable, the send in progress (per song and overall, with Cancel) or the
 * last result, the watch's storage, and the songs on it by album, each album and song removable. Back returns to Settings.
 */
@Composable
internal fun WatchScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: WatchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val strings = rememberStrings()
    BackHandler(onBack = onBack)

    ContentWidthLazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item(key = "header") {
            BackHeader(stringResource(R.string.settings_title), onBack)
            LargeTitle(stringResource(R.string.settings_watch))
        }
        item(key = "link") { ConnectionSection(state, strings, onRefresh = viewModel::refresh) }
        when (val transfer = state.transfer) {
            WatchTransfer.Idle -> Unit
            is WatchTransfer.Running -> item(key = "transfer") { RunningSection(transfer, strings, onCancel = viewModel::cancelSend) }
            else -> item(key = "transfer") { ResultSection(transfer, strings, onDismiss = viewModel::dismissResult) }
        }
        state.space?.let { space -> item(key = "space") { SpaceSection(space, strings) } }
        item(key = "songs-header") { SectionHeader(stringResource(R.string.watch_songs_header)) }
        if (state.groups.isEmpty()) {
            if (state.link == WatchLink.CONNECTED) item(key = "songs-empty") { SectionFooter(stringResource(R.string.watch_songs_empty)) }
        } else {
            val canRemove = !state.removing && state.transfer !is WatchTransfer.Running
            items(state.groups, key = { it.songIds.first() }) { group ->
                AlbumCard(group, strings, canRemove, onRemove = viewModel::askRemove)
            }
        }
    }

    state.pendingRemoval?.let { pending ->
        TranslucentAlert(
            title = WatchText.removeTitle(pending.count, strings),
            message = stringResource(R.string.watch_remove_message),
            onDismiss = viewModel::cancelRemove,
            confirm = TranslucentAlertButton(stringResource(R.string.watch_remove_confirm), destructive = true, onClick = viewModel::confirmRemove),
            cancel = TranslucentAlertButton(stringResource(R.string.watch_cancel_button), onClick = viewModel::cancelRemove),
        )
    }
    state.message?.let { message ->
        TranslucentAlert(
            title = WatchText.message(message, strings),
            onDismiss = viewModel::dismissMessage,
            confirm = TranslucentAlertButton(stringResource(R.string.watch_ok), onClick = viewModel::dismissMessage),
        )
    }
}

@Composable
private fun ConnectionSection(state: WatchUiState, strings: Strings, onRefresh: () -> Unit) {
    SettingsGroup {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val problem = state.link == WatchLink.NO_WATCH || state.link == WatchLink.APP_OUTDATED || state.link == WatchLink.UNREACHABLE
            Icon(
                SonoraIcons.Watch,
                contentDescription = null,
                tint = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                WatchText.link(state.link, strings),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        InsetDivider(start = 52.dp)
        ActionRow(
            SonoraIcons.Refresh,
            stringResource(R.string.watch_refresh),
            onClick = onRefresh,
            enabled = state.link != WatchLink.CHECKING && state.link != WatchLink.SENDING,
        )
    }
    WatchText.linkAdvice(state.link, strings)?.let { SectionFooter(it) }
}

@Composable
private fun RunningSection(transfer: WatchTransfer.Running, strings: Strings, onCancel: () -> Unit) {
    SectionHeader(stringResource(R.string.watch_transfer_header))
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val song = transfer.song
            if (song == null) {
                Text(WatchText.connecting(strings), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    WatchText.songPosition(song, strings) + " · " + WatchText.phase(song, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProgressLine(song.songFraction, WatchText.percent(song.songFraction, strings))
                Spacer(Modifier.height(4.dp))
                Text(
                    WatchText.overall(transfer.overallFraction, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProgressLine(transfer.overallFraction, null)
            }
        }
        InsetDivider(start = 52.dp)
        ActionRow(SonoraIcons.Clear, stringResource(R.string.watch_cancel), onClick = onCancel)
    }
}

@Composable
private fun ProgressLine(fraction: Float, label: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f))
        if (label != null) {
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultSection(transfer: WatchTransfer, strings: Strings, onDismiss: () -> Unit) {
    SectionHeader(stringResource(R.string.watch_result_header))
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (transfer) {
                is WatchTransfer.Done -> {
                    for (line in WatchText.doneLines(transfer, strings)) {
                        Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                    WatchText.speedLine(transfer, strings)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    for (failed in transfer.failed) {
                        Text(WatchText.failedSong(failed, strings), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                is WatchTransfer.Failed ->
                    Text(WatchText.failure(transfer.failure, strings), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
        InsetDivider(start = 52.dp)
        ActionRow(SonoraIcons.Checkmark, stringResource(R.string.watch_dismiss), onClick = onDismiss)
    }
}

@Composable
private fun SpaceSection(space: WatchSpace, strings: Strings) {
    SectionHeader(stringResource(R.string.watch_space_header))
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(WatchText.spaceCaption(space, strings), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            LinearProgressIndicator(progress = { space.usedFraction }, modifier = Modifier.fillMaxWidth())
        }
        InsetDivider(start = 16.dp)
        ValueRow(stringResource(R.string.watch_space_free), WatchText.size(space.freeBytes, strings))
        InsetDivider(start = 16.dp)
        ValueRow(stringResource(R.string.watch_space_room), WatchText.size(space.usableBytes, strings))
        InsetDivider(start = 16.dp)
        ValueRow(stringResource(R.string.watch_space_songs), WatchText.songsValue(space, strings))
    }
    SectionFooter(stringResource(R.string.watch_space_footer))
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One album on the watch: its header with a remove button for the whole album, then its songs, each removable. */
@Composable
private fun AlbumCard(group: WatchAlbumGroup, strings: Strings, canRemove: Boolean, onRemove: (List<String>) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        SettingsGroup {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(
                        WatchText.groupTitle(group, strings),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        WatchText.groupSubtitle(group, strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RemoveButton(stringResource(R.string.watch_remove_album), enabled = canRemove) { onRemove(group.songIds) }
            }
            for (song in group.songs) {
                InsetDivider(start = 16.dp)
                SongRow(song, strings, canRemove) { onRemove(listOf(song.id)) }
            }
        }
    }
}

@Composable
private fun SongRow(song: ManifestTrack, strings: Strings, canRemove: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(WatchText.songSubtitle(song, strings), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RemoveButton(stringResource(R.string.watch_remove_song), enabled = canRemove, onClick = onRemove)
    }
}

@Composable
private fun RemoveButton(description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            SonoraIcons.Trash,
            contentDescription = description,
            tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
