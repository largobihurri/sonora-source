package io.sonora.feature.library

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.ArtworkMosaic
import io.sonora.core.designsystem.component.TranslucentBarCapsule
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.PillButton
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Track
import io.sonora.feature.library.ui.EmptyMessage
import io.sonora.feature.library.ui.TranslucentBarPage
import io.sonora.feature.library.ui.TranslucentTopBar
import io.sonora.feature.library.ui.TranslucentTopBarSpace
import io.sonora.feature.library.ui.SongRow
import io.sonora.feature.library.ui.songCount
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The library lists the picker opens on. */
internal enum class PickerScope(@StringRes val label: Int) {
    Songs(R.string.picker_songs),
    Albums(R.string.picker_albums),
    Artists(R.string.picker_artists),
    Folders(R.string.picker_folders),
    Playlists(R.string.picker_playlists),
    Converted(R.string.picker_converted),
}

/** A page opened from a list: an album's songs, an artist's albums, a folder or a playlist. */
internal sealed interface PickerPage {
    val title: String

    data class Album(val albumId: Long, override val title: String) : PickerPage
    data class Artist(val artistId: Long, override val title: String) : PickerPage
    data class Folder(val folder: FolderRef) : PickerPage {
        override val title: String get() = folder.name
    }
    data class Playlist(val playlistId: Long, override val title: String) : PickerPage
}

/** A row that opens a [page]: an album, artist, folder or playlist, with its cover(s) or an [icon]. */
internal data class PickerItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val page: PickerPage,
    val artworkUris: List<String> = emptyList(),
    val icon: ImageVector? = null,
)

/** One picker list: rows that open further pages, then songs to pick. */
internal data class PickerList(val items: List<PickerItem> = emptyList(), val songs: List<Track> = emptyList())

/**
 * Converter › "Add Music…": the library to pick songs from (Songs, Albums, Artists, Folders, Playlists, Converted),
 * with search, multi-select and "Select All" per list. Add sends the picked songs to the Converter's sources (shared
 * with the Converter tab), then [onDone] closes the picker; Cancel forgets the picks. The lists come from the Library's
 * own view models.
 */
@Composable
fun LibraryPicker(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryPickerViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel(),
    folderViewModel: FolderViewModel = hiltViewModel(),
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(),
    convertedViewModel: ConvertedMusicViewModel = hiltViewModel(),
) {
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val library by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val roots by folderViewModel.roots.collectAsStateWithLifecycle()
    val playlists by playlistsViewModel.state.collectAsStateWithLifecycle()
    val converted by convertedViewModel.state.collectAsStateWithLifecycle()

    var scope by rememberSaveable { mutableStateOf(PickerScope.Songs) }
    // Each opening starts with nothing picked, however the last one was left: Cancel and Add clear the picks, but Back or
    // a pull at the top level closes the picker through the enclosing stack, past this screen. Saved, so a
    // configuration change keeps the picks; the stack drops the saved state of a closed screen.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!opened) {
            opened = true
            viewModel.clearSelection()
        }
    }
    val pages = remember { emptyList<PickerPage>().toMutableStateList() }
    val page = pages.lastOrNull()
    BackHandler(enabled = page != null) { pages.removeAt(pages.lastIndex) }

    val albumTracks by remember(page) {
        (page as? PickerPage.Album)?.let { libraryViewModel.albumTracks(it.albumId).map { pinned -> pinned.items } } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val artistAlbums by remember(page) {
        (page as? PickerPage.Artist)?.let { libraryViewModel.artistAlbums(it.artistId) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val folderContents by remember(page) {
        (page as? PickerPage.Folder)?.let { folder -> folderViewModel.contents(folder.folder).map { it.items.contents } } ?: flowOf(null)
    }.collectAsStateWithLifecycle(null)

    val strings = rememberStrings()
    val albumItem: (Album) -> PickerItem = { album ->
        PickerItem("album:${album.id}", album.title, album.artist, PickerPage.Album(album.id, album.title), listOfNotNull(album.artworkUri))
    }
    val folderItem: (FolderEntry, String) -> PickerItem = { entry, label ->
        PickerItem("folder:${entry.folder.treeUri}:${entry.folder.path}", label, songCount(entry.trackCount, strings), PickerPage.Folder(entry.folder), icon = SonoraIcons.Folder)
    }
    val list = when (page) {
        is PickerPage.Album -> PickerList(songs = albumTracks)
        is PickerPage.Artist -> PickerList(items = artistAlbums.map(albumItem))
        is PickerPage.Folder -> PickerList(
            items = folderContents?.subfolders.orEmpty().map { folderItem(it, it.folder.name) },
            songs = folderContents?.tracks.orEmpty(),
        )
        is PickerPage.Playlist -> PickerList(
            songs = playlists.playlists.firstOrNull { it.playlist.id == page.playlistId }?.playableTracks.orEmpty(),
        )
        null -> when {
            query.isNotBlank() -> PickerList(songs = results)
            else -> when (scope) {
                PickerScope.Songs -> PickerList(songs = library.tracks)
                PickerScope.Albums -> PickerList(items = library.albums.map(albumItem))
                PickerScope.Artists -> PickerList(
                    items = library.artists.map { artist ->
                        PickerItem("artist:${artist.id}:${artist.name}", artist.name, null, PickerPage.Artist(artist.id, artist.name), icon = SonoraIcons.Person)
                    },
                )
                PickerScope.Folders -> PickerList(items = roots.orEmpty().map { folderItem(it, it.folder.path) })
                PickerScope.Playlists -> PickerList(
                    items = playlists.playlists.map { playlist ->
                        PickerItem(
                            "playlist:${playlist.playlist.id}",
                            playlist.playlist.name,
                            songCount(playlist.songCount, strings),
                            PickerPage.Playlist(playlist.playlist.id, playlist.playlist.name),
                            playlist.coverArtworkUris,
                        )
                    },
                )
                PickerScope.Converted -> PickerList(songs = converted.tracks)
            }
        }
    }

    LibraryPickerContent(
        list = list,
        page = page,
        scope = scope,
        query = query,
        selection = selection,
        onScope = { scope = it },
        onQueryChange = viewModel::onQueryChange,
        onOpen = { pages.add(it) },
        onBack = { pages.removeAt(pages.lastIndex) },
        onCancel = {
            viewModel.clearSelection()
            onDone()
        },
        onToggle = viewModel::toggle,
        onToggleAll = viewModel::toggleAll,
        onAdd = { if (viewModel.add()) onDone() },
        modifier = modifier,
    )
}

/** The picker's screen: top bar, search and list chooser (top level only), the list, and the selection bar. */
@Composable
internal fun LibraryPickerContent(
    list: PickerList,
    page: PickerPage?,
    scope: PickerScope,
    query: String,
    selection: TrackSelection,
    onScope: (PickerScope) -> Unit,
    onQueryChange: (String) -> Unit,
    onOpen: (PickerPage) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onToggle: (Track) -> Unit,
    onToggleAll: (List<Track>) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TranslucentBarPage(bar = { PickerTopBar(page, onBack, onCancel) }, modifier) {
        Column(Modifier.fillMaxSize()) {
            TranslucentTopBarSpace()
            if (page == null) {
                SearchField(query, onQueryChange, placeholder = stringResource(R.string.picker_search))
                if (query.isBlank()) ScopeChips(scope, onScope)
                Spacer(Modifier.height(4.dp))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(list.items, key = { it.key }) { item -> PickerItemRow(item, onClick = { onOpen(item.page) }) }
                if (list.songs.isNotEmpty()) {
                    item(key = "select-all") { SelectAllRow(selection.allSelected(list.songs), onClick = { onToggleAll(list.songs) }) }
                }
                items(list.songs, key = { "song:${it.uri}" }) { track ->
                    SongRow(
                        track,
                        onClick = { onToggle(track) },
                        onLongClick = { onToggle(track) },
                        selected = selection.isSelected(track),
                    )
                }
                if (list.items.isEmpty() && list.songs.isEmpty()) {
                    item(key = "empty") {
                        EmptyMessage(stringResource(if (query.isNotBlank() && page == null) R.string.picker_no_results else R.string.picker_nothing_here))
                    }
                }
            }
            SelectionBar(selection.count, onAdd)
        }
    }
}

/** The picker's floating bar: Cancel (Back on a list's page) and the page's title, centred, with translucent buttons. */
@Composable
private fun PickerTopBar(page: PickerPage?, onBack: () -> Unit, onCancel: () -> Unit) {
    TranslucentTopBar(
        backLabel = stringResource(R.string.picker_back),
        onBack = onBack.takeIf { page != null },
        leading = { TranslucentBarCapsule(stringResource(R.string.picker_cancel), onCancel) },
        title = {
            Text(
                page?.title ?: stringResource(R.string.picker_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        },
    )
}

/** The lists to pick from, as a scrolling row of capsules (six do not fit a segmented control on a phone). */
@Composable
private fun ScopeChips(selected: PickerScope, onSelect: (PickerScope) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PickerScope.entries.forEach { scope ->
            val isSelected = scope == selected
            Text(
                stringResource(scope.label),
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) colors.onPrimary else colors.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) colors.primary else colors.surfaceVariant)
                    .clickable(role = Role.Tab) { onSelect(scope) }
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun PickerItemRow(item: PickerItem, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.icon != null) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(item.icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
                }
            } else {
                ArtworkMosaic(item.artworkUris, Modifier.size(48.dp), RoundedCornerShape(6.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
        InsetDivider(start = 80.dp)
    }
}

/** "Select All" (or "Deselect All" once every song of the list is picked) for the songs shown. */
@Composable
private fun SelectAllRow(allSelected: Boolean, onClick: () -> Unit) {
    Text(
        stringResource(if (allSelected) R.string.picker_deselect_all else R.string.picker_select_all),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** Sticky bar above the floating chrome: how many songs are picked, and Add. */
@Composable
private fun SelectionBar(count: Int, onAdd: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        InsetDivider(start = 0.dp)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pluralStringResource(R.plurals.picker_selected, count, count),
                style = MaterialTheme.typography.bodyLarge,
                color = if (count > 0) colors.onSurface else colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            PillButton(stringResource(R.string.picker_add), SonoraIcons.Add, onAdd, enabled = count > 0)
        }
        // The floating mini player and tab bar sit below; the bar stays clear of them.
        Spacer(Modifier.height(BottomChromePadding))
    }
}

private val previewSongs = (1L..4L).map {
    Track(it, "u$it", "Song $it", "Artist", "Album", null, 200_000, it.toInt(), 1, 2024, 1, artworkUri = null, mimeType = "audio/flac", sizeBytes = 1)
}

@Preview(name = "Library picker, dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LibraryPickerPreview() {
    SonoraTheme(darkTheme = true) {
        LibraryPickerContent(
            list = PickerList(songs = previewSongs),
            page = null,
            scope = PickerScope.Songs,
            query = "",
            selection = TrackSelection(previewSongs.take(2)),
            onScope = {}, onQueryChange = {}, onOpen = {}, onBack = {}, onCancel = {}, onToggle = {}, onToggleAll = {}, onAdd = {},
        )
    }
}
