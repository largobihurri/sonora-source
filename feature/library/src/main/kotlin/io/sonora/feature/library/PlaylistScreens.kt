package io.sonora.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.ActionSheet
import io.sonora.core.designsystem.component.ArtworkMosaic
import io.sonora.core.designsystem.component.TranslucentBarCapsule
import io.sonora.core.designsystem.component.TranslucentBarCircle
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.DragAutoScrollEffect
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.PillButton
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.component.SheetAction
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.ContentWidth
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.domain.library.smart.SmartPlaylist
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.PlaylistItem
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.core.domain.sort.SortList
import io.sonora.core.domain.sort.SortOrder
import io.sonora.feature.library.ui.VisualCollectionButton
import io.sonora.feature.library.ui.DeletePlaylistDialog
import io.sonora.feature.library.ui.DetailHeaderLayout
import io.sonora.feature.library.ui.SideHeader
import io.sonora.feature.library.ui.rememberDetailHeaderLayout
import io.sonora.feature.library.ui.DragReorder
import io.sonora.feature.library.ui.EmptyMessage
import io.sonora.feature.library.ui.FavouritesRow
import io.sonora.feature.library.ui.TranslucentBarPage
import io.sonora.feature.library.ui.TranslucentTopBar
import io.sonora.feature.library.ui.TranslucentTopBarSpace
import io.sonora.feature.library.ui.SmartPlaylistRow
import io.sonora.feature.library.ui.NewPlaylistRow
import io.sonora.feature.library.ui.PlaylistDetailsDialog
import io.sonora.feature.library.ui.PlaylistRow
import io.sonora.feature.library.ui.PlaylistSongRow
import io.sonora.feature.library.ui.SelectionBar
import io.sonora.feature.library.ui.SelectionBarSpace
import io.sonora.feature.library.ui.SelectionHeader
import io.sonora.feature.library.ui.SongSelection
import io.sonora.feature.library.ui.SortButton
import io.sonora.feature.library.ui.SortControl
import io.sonora.feature.library.ui.dragSelect
import io.sonora.feature.library.ui.dragSelectRow
import io.sonora.feature.library.ui.rememberSongSelection

/** Library > Playlists: the smart playlists (Favourites first), "New Playlist…", then every playlist by name. */
@Composable
internal fun PlaylistsScreen(
    state: PlaylistsUiState,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onMenu: (ResolvedPlaylist) -> Unit = {},
    onFavourites: () -> Unit = {},
    onSmart: (SmartPlaylist) -> Unit = {},
    onVisualCollection: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    TranslucentBarPage(
        bar = {
            TranslucentTopBar(backLabel = stringResource(R.string.playlists_back_library), onBack = onBack) {
                if (onVisualCollection != null) VisualCollectionButton(onVisualCollection)
            }
        },
    ) {
        ContentWidthLazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = BottomChromePadding)) {
            item(key = "header") {
                TranslucentTopBarSpace()
                LargeTitle(stringResource(R.string.playlists_title))
                FavouritesRow(onClick = onFavourites)
                SmartPlaylistsInOrder.forEach { smart -> SmartPlaylistRow(smart.title(), smart.icon, onClick = { onSmart(smart) }) }
                NewPlaylistRow(onClick = onNew)
            }
            items(state.playlists, key = { it.playlist.id }) { playlist ->
                PlaylistRow(
                    playlist,
                    onClick = { onOpen(playlist.playlist.id) },
                    onLongClick = { onMenu(playlist) },
                    pinned = playlist.playlist.id.toString() in state.pinned,
                )
            }
        }
    }
}

/**
 * Container of one playlist's page: its view model (keyed by [playlistId]), menu and dialogs; "Convert…" hands the
 * available songs to [onConvert], "Compress…" its name and those songs to [onCompress], "Send to Watch" its id and name to [onSendToWatch] (no such item when it is null). Once the playlist is deleted, [onDeleted] closes this page only (not whatever page
 * is on top by then).
 */
@Composable
internal fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onSongMenu: (Track) -> Unit,
    onConvert: (List<Track>) -> Unit,
    onCompress: (name: String, tracks: List<Track>) -> Unit,
    onSendToWatch: ((playlistId: Long, name: String) -> Unit)?,
    viewModel: PlaylistDetailViewModel = hiltViewModel<PlaylistDetailViewModel, PlaylistDetailViewModel.Factory>(
        key = "playlist:$playlistId",
    ) { it.create(playlistId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var editingDetails by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    val latestOnDeleted by rememberUpdatedState(onDeleted)
    LaunchedEffect(state.deleted) { if (state.deleted) latestOnDeleted() }

    val selection = rememberSongSelection()
    Box(Modifier.fillMaxSize()) {
        PlaylistDetailContent(
            state = state,
            selection = selection,
            onBack = onBack,
            onMenu = { menuOpen = true },
            onDone = viewModel::finishEditing,
            onPlay = viewModel::play,
            onShuffle = viewModel::shuffle,
            onPlayEntry = viewModel::play,
            onSongMenu = onSongMenu,
            onRemove = viewModel::remove,
            onMove = viewModel::move,
            onSort = viewModel::setSort,
        )
    }

    val playlist = state.playlist?.playlist ?: return
    if (menuOpen) {
        ActionSheet(
            actions = listOf(
                SheetAction(stringResource(R.string.playlist_edit), SonoraIcons.DragHandle, viewModel::startEditing),
                *listOfNotNull(
                    selection?.takeIf { state.items.any { it.isAvailable } }?.let {
                        SheetAction(stringResource(R.string.select_songs), SonoraIcons.Checkmark) { it.start() }
                    },
                ).toTypedArray(),
                SheetAction(stringResource(R.string.playlist_edit_details), SonoraIcons.Info) { editingDetails = true },
                SheetAction(stringResource(R.string.convert_action), SonoraIcons.Convert) {
                    onConvert(state.playlist?.playableTracks.orEmpty())
                },
                SheetAction(stringResource(R.string.compress_action), SonoraIcons.Archive) {
                    onCompress(playlist.name, state.playlist?.playableTracks.orEmpty())
                },
                *listOfNotNull(
                    onSendToWatch?.let { send ->
                        SheetAction(stringResource(R.string.menu_send_to_watch), SonoraIcons.Watch) { send(playlist.id, playlist.name) }
                    },
                ).toTypedArray(),
                SheetAction(stringResource(R.string.playlist_delete), SonoraIcons.Trash) { confirmingDelete = true },
            ),
            onDismiss = { menuOpen = false },
            title = playlist.name,
        ) {
            // On the menu's translucent surface: the text takes the translucent colour.
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
    if (editingDetails) {
        PlaylistDetailsDialog(
            title = stringResource(R.string.playlist_edit_title),
            confirmLabel = stringResource(R.string.playlist_save),
            initialName = playlist.name,
            initialDescription = playlist.description,
            onConfirm = { name, description ->
                viewModel.editDetails(name, description)
                editingDetails = false
            },
            onDismiss = { editingDetails = false },
        )
    }
    if (confirmingDelete) {
        DeletePlaylistDialog(
            name = playlist.name,
            onConfirm = {
                confirmingDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/** A playlist's page: cover, details, Play / Shuffle and its songs; in Edit mode songs are removed and dragged. */
@Composable
internal fun PlaylistDetailContent(
    state: PlaylistDetailUiState,
    selection: SongSelection?,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onDone: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayEntry: (Long) -> Unit,
    onSongMenu: (Track) -> Unit,
    onRemove: (Long) -> Unit,
    onMove: (entryId: Long, toIndex: Int) -> Unit,
    onSort: (SortOrder) -> Unit = {},
) {
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    val reorder = remember(listState) { DragReorder(listState, firstRowIndex = 1) }
    val playlist = state.playlist
    // Listed in the playlist's sort; dragging is offered only in Playlist Order.
    val items = state.items
    val reorderable = state.editing && state.canReorder
    // Gesture callbacks outlive recompositions; they read the latest entries through this.
    val latestKeys by rememberUpdatedState(items.map { it.entry.id })
    val byId = items.associateBy { it.entry.id }
    // While dragging, and after a drop until the saved order arrives, the rows follow the drag (no snap-back). A save
    // that never lands (it failed) shows the stored order again.
    val storedKeys = items.map { it.entry.id }
    val shown = reorder.shownOrder(storedKeys).mapNotNull(byId::get)
    LaunchedEffect(reorder.awaitingSave, storedKeys) { reorder.awaitSave(storedKeys) }
    val moveUp = stringResource(R.string.playlist_move_up)
    val moveDown = stringResource(R.string.playlist_move_down)
    // A row held at the list's top or bottom edge scrolls it, and keeps passing the rows that scroll under it.
    DragAutoScrollEffect(listState, active = reorder.draggedKey != null, probe = reorder::draggedMiddle, onScrolled = reorder::scrolled)

    val selecting = selection?.active == true && !state.editing
    val selectable = remember(items) { selectableRows(items) }
    TranslucentBarPage(
        bar = {
            if (selecting && selection != null) {
                SelectionHeader(selection, selectable.map { it.first })
            } else {
                DetailTopBar(state.editing, onBack, onMenu, onDone, SortControl(SortList.PLAYLIST, state.order, onSort).takeIf { playlist != null })
            }
        },
        scrollEdge = selecting,
        overlay = {
            if (selection != null) {
                SelectionBar(
                    selection,
                    selectable,
                    playlist?.playlist?.name.orEmpty(),
                    Modifier.align(Alignment.BottomCenter).padding(bottom = BottomChromePadding),
                )
            }
        },
    ) {
        ContentWidthLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().dragSelect(selection.takeIf { selecting }, listState, selectable),
            contentPadding = PaddingValues(bottom = BottomChromePadding + if (selecting) SelectionBarSpace else 0.dp),
            max = ContentWidth.TABLE_DP.dp,
        ) {
            item(key = "header") {
                TranslucentTopBarSpace()
                if (playlist != null) PlaylistHeader(playlist, onPlay, onShuffle)
                if (state.editing && !state.canReorder && items.isNotEmpty()) {
                    Text(
                        stringResource(R.string.sort_reorder_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            if (playlist != null && items.isEmpty()) item(key = "empty") { EmptyMessage(stringResource(R.string.playlist_empty)) }
            itemsIndexed(shown, key = { _, item -> item.entry.id }) { position, item ->
                val id = item.entry.id
                val removeLabel = stringResource(R.string.playlist_remove_entry, item.entry.title)
                val dragged = id == reorder.draggedKey
                val rowModifier = if (dragged) {
                    Modifier
                        .zIndex(1f)
                        .graphicsLayer {
                            translationY = reorder.translationOf(id)
                            shadowElevation = 16f
                            shape = RoundedCornerShape(10.dp)
                            clip = true
                        }
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    Modifier.animateItem()
                }
                val handle = Modifier.pointerInput(id) {
                    detectDragGestures(
                        onDragStart = { reorder.start(id, latestKeys) },
                        onDrag = { change, amount ->
                            change.consume()
                            reorder.drag(amount.y)
                        },
                        onDragEnd = { reorder.end(latestKeys)?.let { to -> onMove(id, to) } },
                        onDragCancel = reorder::cancel,
                    )
                }
                val editActions = if (state.editing) {
                    buildList {
                        add(CustomAccessibilityAction(removeLabel) { onRemove(id); true })
                        if (reorderable && position > 0) add(CustomAccessibilityAction(moveUp) { onMove(id, position - 1); true })
                        if (reorderable && position < shown.lastIndex) add(CustomAccessibilityAction(moveDown) { onMove(id, position + 1); true })
                    }
                } else {
                    emptyList()
                }
                val key = entryKey(id)
                val selectingRow = selecting && item.isAvailable
                PlaylistSongRow(
                    item = item,
                    editing = state.editing,
                    onClick = if (selectingRow) { { selection?.toggle(key) } } else { { onPlayEntry(id) } },
                    onLongClick = if (selectingRow) { { selection?.toggle(key) } } else { { item.track?.let(onSongMenu) } },
                    selected = if (selectingRow) selection?.mark(key) else null,
                    onRemove = { onRemove(id) },
                    modifier = rowModifier
                        .dragSelectRow(if (selectingRow) selection else null, key)
                        .semantics { if (editActions.isNotEmpty()) customActions = editActions },
                    dragHandle = handle,
                    reorderable = reorderable,
                    pinned = item.entry.trackUri in state.pinned,
                )
            }
        }
    }
}

/** A playlist row's selection key: its entry, so a song listed twice is two rows. */
private fun entryKey(entryId: Long): String = "entry:$entryId"

/** The rows a selection can pick (songs still in the library), keyed by entry, in the order shown. */
private fun selectableRows(items: List<PlaylistItem>): List<Pair<String, Track>> =
    items.mapNotNull { item -> item.track?.let { entryKey(item.entry.id) to it } }

/** The floating bar: back to Playlists, and Sort and "…" (or "Done" while editing) at the trailing edge, all translucent. */
@Composable
private fun DetailTopBar(editing: Boolean, onBack: () -> Unit, onMenu: () -> Unit, onDone: () -> Unit, sort: SortControl?) {
    TranslucentTopBar(backLabel = stringResource(R.string.playlists_title), onBack = onBack) {
        if (editing) {
            TranslucentBarCapsule(stringResource(R.string.playlist_done), onDone, emphasized = true)
        } else {
            if (sort != null) SortButton(sort)
            TranslucentBarCircle(SonoraIcons.More, stringResource(R.string.playlist_more), onMenu)
        }
    }
}

@Composable
private fun PlaylistHeader(playlist: ResolvedPlaylist, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val playable = playlist.playableTracks.isNotEmpty()
    val summary = stringResource(
        R.string.playlist_summary,
        pluralStringResource(R.plurals.playlist_song_count, playlist.songCount, playlist.songCount),
        durationText(playlist.totalDurationMs),
    )
    when (val layout = rememberDetailHeaderLayout()) {
        is DetailHeaderLayout.Beside -> SideHeader(
            layout.coverDp,
            cover = {
                ArtworkMosaic(
                    playlist.coverArtworkUris,
                    Modifier.size(layout.coverDp.dp),
                    RoundedCornerShape(12.dp),
                    contentDescription = playlist.playlist.name,
                )
            },
        ) {
            Text(
                playlist.playlist.name,
                style = MaterialTheme.typography.headlineLarge,
                color = colors.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (playlist.playlist.description.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(playlist.playlist.description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.widthIn(max = 360.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(stringResource(R.string.playlist_play), SonoraIcons.Play, onPlay, Modifier.weight(1f), enabled = playable)
                PillButton(stringResource(R.string.playlist_shuffle), SonoraIcons.Shuffle, onShuffle, Modifier.weight(1f), enabled = playable)
            }
        }
        DetailHeaderLayout.Centred -> Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            ArtworkMosaic(
                playlist.coverArtworkUris,
                Modifier.size(240.dp),
                RoundedCornerShape(12.dp),
                contentDescription = playlist.playlist.name,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                playlist.playlist.name,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
            )
            if (playlist.playlist.description.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    playlist.playlist.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(stringResource(R.string.playlist_play), SonoraIcons.Play, onPlay, Modifier.weight(1f), enabled = playable)
                PillButton(stringResource(R.string.playlist_shuffle), SonoraIcons.Shuffle, onShuffle, Modifier.weight(1f), enabled = playable)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    InsetDivider(start = 20.dp)
}

/** "45 minutes", "1 hour", "1 hour, 5 minutes", rounded to the minute. */
@Composable
private fun durationText(totalMs: Long): String {
    val minutes = ((totalMs + 30_000) / 60_000).toInt()
    val hours = minutes / 60
    val rest = minutes % 60
    val minutesText = pluralStringResource(R.plurals.playlist_minutes, rest, rest)
    if (hours == 0) return minutesText
    val hoursText = pluralStringResource(R.plurals.playlist_hours, hours, hours)
    return if (rest == 0) hoursText else stringResource(R.string.playlist_hours_minutes, hoursText, minutesText)
}

private val previewItems = listOf(
    PlaylistItem(
        PlaylistEntry(1, "u1", "Midnight City", "M83"),
        Track(1, "u1", "Midnight City", "M83", "Hurry Up", null, 243_000, 1, 1, 2011, 1, artworkUri = null, mimeType = "audio/flac", sizeBytes = 1),
    ),
    PlaylistItem(PlaylistEntry(2, "u2", "Gone Song", "Someone"), null),
)

@Preview(name = "Playlist detail, dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlaylistDetailPreview() {
    val state = PlaylistDetailUiState(
        loading = false,
        playlist = ResolvedPlaylist(Playlist(1, "Road Trip", "Songs for the drive"), previewItems),
    )
    SonoraTheme(darkTheme = true) {
        PlaylistDetailContent(state, null, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> })
    }
}

@Preview(name = "Playlist detail, editing", showBackground = true)
@Composable
private fun PlaylistDetailEditingPreview() {
    val state = PlaylistDetailUiState(
        loading = false,
        playlist = ResolvedPlaylist(Playlist(1, "Road Trip"), previewItems),
        editing = true,
    )
    SonoraTheme(darkTheme = false) {
        PlaylistDetailContent(state, null, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> })
    }
}

@Preview(name = "Playlists, dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlaylistsPreview() {
    val state = PlaylistsUiState(loading = false, playlists = listOf(ResolvedPlaylist(Playlist(1, "Road Trip"), previewItems)))
    SonoraTheme(darkTheme = true) { PlaylistsScreen(state, onBack = {}, onOpen = {}, onNew = {}) }
}
