package io.sonora.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.ArtworkMosaic
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.TranslucentAlertTextField
import io.sonora.core.designsystem.component.TranslucentSheetHeader
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.SheetActionGate
import io.sonora.core.designsystem.component.SonoraModalBottomSheet
import io.sonora.core.designsystem.component.rememberSheetScrollStop
import io.sonora.core.designsystem.component.sheetScrollStop
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.LocalContentArea
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.playlist.PlaylistItem
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.feature.library.R
import kotlinx.coroutines.launch

/** Opacity of a song whose file is gone, as iOS dims unavailable rows. */
private const val UNAVAILABLE_ALPHA = 0.4f

/** A playlist in a list: its cover ([ArtworkMosaic]), name and song count. */
@Composable
internal fun PlaylistRow(
    playlist: ResolvedPlaylist,
    onClick: () -> Unit,
    showChevron: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    pinned: Boolean = false,
) {
    Column(if (onLongClick != null) Modifier.pressable(onClick, onLongClick) else Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkMosaic(playlist.coverArtworkUris, Modifier.size(56.dp), RoundedCornerShape(6.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralStringResource(R.plurals.playlist_song_count, playlist.songCount, playlist.songCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pinned) PinnedGlyph(Modifier.padding(horizontal = 8.dp))
            if (showChevron) {
                Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        InsetDivider(start = 88.dp)
    }
}

/** The Favourites smart playlist, first in Playlists: a star in a cover-sized tile. */
@Composable
internal fun FavouritesRow(onClick: () -> Unit) {
    SmartPlaylistRow(stringResource(R.string.favourites_title), SonoraIcons.StarFilled, onClick)
}

/** A smart playlist in Playlists: its [icon] in an accent cover-sized tile, then its [title]. */
@Composable
internal fun SmartPlaylistRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    Column(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InsetDivider(start = 88.dp)
    }
}

/** "New Playlist…": an accent plus in a cover-sized tile, as at the top of Apple Music's playlist lists. */
@Composable
internal fun NewPlaylistRow(onClick: () -> Unit) {
    Column(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(SonoraIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.playlist_new_row),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        InsetDivider(start = 88.dp)
    }
}

/**
 * One playlist song in the song-row style. Unavailable songs are dimmed and do nothing when tapped. In Edit mode
 * the row shows a remove button and, when [reorderable], a drag handle ([dragHandle] carries the drag gesture)
 * instead of playing. In selection mode [selected] shows its check mark.
 */
@Composable
internal fun PlaylistSongRow(
    item: PlaylistItem,
    editing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: Modifier = Modifier,
    reorderable: Boolean = true,
    pinned: Boolean = false,
    selected: Boolean? = null,
) {
    val colors = MaterialTheme.colorScheme
    val unavailable = stringResource(R.string.playlist_unavailable)
    // On a large screen a wide page lays the songs out as a table: artist, album and length beside the title.
    val table = SongTable.of(LocalContentArea.current.width?.value, artistAndAlbum = true)
    val rowModifier = when {
        editing -> Modifier
        item.isAvailable -> Modifier.pressable(onClick, onLongClick)
        else -> Modifier.semantics(mergeDescendants = true) {}
    }
    Column(modifier.then(rowModifier)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = if (editing) 8.dp else 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editing) {
                Icon(
                    SonoraIcons.RemoveCircle,
                    contentDescription = stringResource(R.string.playlist_remove_entry, item.entry.title),
                    tint = colors.error,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onRemove).padding(10.dp).size(22.dp),
                )
            }
            Row(
                Modifier.weight(1f).alpha(if (item.isAvailable) 1f else UNAVAILABLE_ALPHA),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(item.track?.artworkUri, Modifier.size(48.dp), RoundedCornerShape(6.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(
                        item.track?.title ?: item.entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The artist has its own column in the table; a song no longer in the library still says so.
                    if (!table.artist || !item.isAvailable) {
                        Text(
                            when {
                                item.isAvailable -> item.track!!.artist
                                table.artist -> unavailable
                                else -> "${item.entry.artist} · $unavailable"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SongTableCells(table, item.track?.artist ?: item.entry.artist, item.track?.album.orEmpty())
                if (table.duration) DurationCell(item.track?.durationMs)
            }
            if (editing && reorderable) {
                Icon(
                    SonoraIcons.DragHandle,
                    contentDescription = stringResource(R.string.playlist_reorder),
                    tint = colors.onSurfaceVariant,
                    modifier = dragHandle.size(44.dp).padding(11.dp),
                )
            } else {
                if (pinned) PinnedGlyph(Modifier.padding(start = 8.dp))
                Spacer(Modifier.width(12.dp))
                if (selected != null) {
                    SelectionMark(selected)
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
        InsetDivider(start = if (editing) 120.dp else 80.dp)
    }
}

/** Name and optional description of a new or edited playlist; [onConfirm] gets them as typed (never a blank name). */
@Composable
internal fun PlaylistDetailsDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (name: String, description: String) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialDescription: String = "",
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    // The name is what the dialog asks for: the cursor and the keyboard start there.
    val nameFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // The dialog's window attaches after the first composition: wait a frame, then focus and raise the keyboard.
        withFrameNanos { }
        if (runCatching { nameFocus.requestFocus() }.isSuccess) keyboard?.show()
    }
    TranslucentAlert(
        title = title,
        onDismiss = onDismiss,
        confirm = TranslucentAlertButton(confirmLabel, enabled = name.isNotBlank()) { onConfirm(name, description) },
        cancel = TranslucentAlertButton(stringResource(R.string.playlist_cancel), onClick = onDismiss),
    ) {
        TranslucentAlertTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.playlist_name_label),
            modifier = Modifier.focusRequester(nameFocus),
        )
        TranslucentAlertTextField(
            value = description,
            onValueChange = { description = it },
            label = stringResource(R.string.playlist_description_label),
            singleLine = false,
            maxLines = 3,
        )
    }
}

@Composable
internal fun DeletePlaylistDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    TranslucentAlert(
        title = stringResource(R.string.playlist_delete_title),
        message = stringResource(R.string.playlist_delete_message, name),
        onDismiss = onDismiss,
        confirm = TranslucentAlertButton(stringResource(R.string.playlist_delete_confirm), destructive = true, onClick = onConfirm),
        cancel = TranslucentAlertButton(stringResource(R.string.playlist_cancel), onClick = onDismiss),
    )
}

/**
 * "Add to Playlist" picker: "New Playlist…" then every playlist. A choice slides the sheet away first, then runs
 * once ([SheetActionGate]) and [onDismiss] follows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    playlists: List<ResolvedPlaylist>,
    onNew: () -> Unit,
    onPick: (Playlist) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val gate = remember { SheetActionGate() }
    val choose: (() -> Unit) -> Unit = { action ->
        scope.launch {
            gate.choose(hide = { sheetState.hide() }) {
                action()
                onDismiss()
            }
        }
    }
    SonoraModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        TranslucentSheetHeader(stringResource(R.string.playlist_picker_title))
        // Scrolled or flung up to its top, the list stops there; only a new pull from the top drags the sheet down.
        val listState = rememberLazyListState()
        val scrollStop = rememberSheetScrollStop(listState)
        LazyColumn(Modifier.sheetScrollStop(scrollStop).navigationBarsPadding(), state = listState) {
            item(key = "new") { NewPlaylistRow(onClick = { choose(onNew) }) }
            items(playlists, key = { it.playlist.id }) { playlist ->
                PlaylistRow(playlist, onClick = { choose { onPick(playlist.playlist) } }, showChevron = false)
            }
        }
    }
}

@Preview(name = "Playlist rows, dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlaylistRowsPreview() {
    val playlist = ResolvedPlaylist(Playlist(1, "Road Trip"), emptyList())
    SonoraTheme(darkTheme = true) {
        Column {
            NewPlaylistRow(onClick = {})
            PlaylistRow(playlist, onClick = {})
        }
    }
}
