package io.sonora.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.PillButton
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.designsystem.layout.ContentWidth
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.model.FolderContents
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.sort.SortList
import io.sonora.feature.library.ui.EmptyMessage
import io.sonora.feature.library.ui.FolderRow
import io.sonora.feature.library.ui.TranslucentBarPage
import io.sonora.feature.library.ui.TranslucentTopBar
import io.sonora.feature.library.ui.TranslucentTopBarSpace
import io.sonora.feature.library.ui.MenuOpener
import io.sonora.feature.library.ui.MenuPlace
import io.sonora.feature.library.ui.MenuTarget
import io.sonora.feature.library.ui.SelectionBar
import io.sonora.feature.library.ui.SelectionBarSpace
import io.sonora.feature.library.ui.SelectionHeader
import io.sonora.feature.library.ui.SongRow
import io.sonora.feature.library.ui.SortControl
import io.sonora.feature.library.ui.keyedByUri
import io.sonora.feature.library.ui.dragSelect
import io.sonora.feature.library.ui.dragSelectRow
import io.sonora.feature.library.ui.rememberSongSelection
import io.sonora.feature.library.ui.songCount

/** Library > Folders: the library folders the user added, by their display path. */
@Composable
internal fun FoldersScreen(
    viewModel: FolderViewModel,
    onBack: () -> Unit,
    onOpen: (FolderRef) -> Unit,
    menu: MenuOpener,
) {
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    TranslucentBarPage(bar = { TranslucentTopBar(backLabel = stringResource(R.string.library_back_library), onBack = onBack) }) {
        FoldersList(roots, listState, onOpen, menu)
    }
}

@Composable
private fun FoldersList(roots: List<FolderEntry>?, listState: LazyListState, onOpen: (FolderRef) -> Unit, menu: MenuOpener) {
    ContentWidthLazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item {
            TranslucentTopBarSpace()
            LargeTitle(stringResource(R.string.library_folders))
        }
        val list = roots
        if (list != null && list.isEmpty()) item { EmptyMessage(stringResource(R.string.library_folders_empty)) }
        items(list.orEmpty(), key = { it.folder.treeUri }) { entry ->
            FolderRow(
                label = entry.folder.path,
                trackCount = entry.trackCount,
                onClick = { onOpen(entry.folder) },
                onLongClick = { menu.open(MenuTarget.FolderItem(entry), MenuPlace(PinContext.FOLDERS)) },
            )
        }
    }
}

/**
 * One folder: Play / Shuffle for everything inside (subfolders included, in folder order), its subfolders, then its
 * own songs, both in the folder sort; a tapped song plays the folder's own songs as listed.
 */
@Composable
internal fun FolderScreen(
    folder: FolderRef,
    viewModel: FolderViewModel,
    onBack: () -> Unit,
    onOpen: (FolderRef) -> Unit,
    menu: MenuOpener,
) {
    val place = MenuPlace(PinContext.folder(folder))
    val sorted by remember(folder) { viewModel.contents(folder) }.collectAsStateWithLifecycle(null)
    val contents = sorted?.items?.contents
    val pinned = sorted?.items?.pinned.orEmpty()
    FolderContentsList(
        folder = folder,
        contents = contents,
        pinned = pinned,
        sort = SortControl(SortList.FOLDER, sorted?.order ?: SortList.FOLDER.default, viewModel::setSort),
        onBack = onBack,
        onMore = { contents?.let { menu.open(MenuTarget.FolderItem(FolderEntry(folder, it.trackCount)), MenuPlace.Unpinnable) } },
        onPlay = { viewModel.play(folder) },
        onShuffle = { viewModel.shuffle(folder) },
        onOpen = onOpen,
        onFolderMenu = { menu.open(MenuTarget.FolderItem(it), place) },
        onPlayTrack = viewModel::playTracks,
        onSongMenu = { menu.open(MenuTarget.Song(it), place) },
    )
}

@Composable
private fun FolderContentsList(
    folder: FolderRef,
    contents: FolderContents?,
    pinned: Set<String>,
    sort: SortControl,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onOpen: (FolderRef) -> Unit,
    onFolderMenu: (FolderEntry) -> Unit,
    onPlayTrack: (List<Track>, Int) -> Unit,
    onSongMenu: (Track) -> Unit,
) {
    val count = contents?.trackCount ?: 0
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    val selection = rememberSongSelection()
    val shown = remember(contents) { contents?.tracks.orEmpty().keyedByUri() }
    val selecting = selection?.active == true
    TranslucentBarPage(
        bar = {
            if (selecting) {
                SelectionHeader(selection, shown.map { it.first })
            } else {
                BackBar(
                    stringResource(R.string.library_back), onBack, onMore.takeIf { contents != null }, sort,
                    onSelect = selection?.takeIf { shown.isNotEmpty() }?.let { { it.start() } },
                )
            }
        },
        scrollEdge = selecting,
        overlay = {
            if (selection != null) SelectionBar(selection, shown, folder.name, Modifier.align(Alignment.BottomCenter).padding(bottom = BottomChromePadding))
        },
    ) {
        ContentWidthLazyColumn(
            Modifier.fillMaxSize().dragSelect(selection, listState, shown),
            state = listState,
            contentPadding = PaddingValues(bottom = BottomChromePadding + if (selecting) SelectionBarSpace else 0.dp),
            max = ContentWidth.TABLE_DP.dp,
        ) {
            item {
                TranslucentTopBarSpace()
                Text(
                    folder.name,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (contents != null) {
                    Text(
                        songCount(count, rememberStrings()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PillButton(stringResource(R.string.library_play), SonoraIcons.Play, onPlay, Modifier.weight(1f), enabled = count > 0)
                        PillButton(stringResource(R.string.library_shuffle), SonoraIcons.Shuffle, onShuffle, Modifier.weight(1f), enabled = count > 0)
                    }
                }
                InsetDivider(start = 20.dp)
                if (contents != null && count == 0) EmptyMessage(stringResource(R.string.library_folder_empty))
            }
            if (contents == null) return@ContentWidthLazyColumn
            items(contents.subfolders, key = { "folder:${it.folder.path}" }) { entry ->
                FolderRow(
                    label = entry.folder.name,
                    trackCount = entry.trackCount,
                    onClick = { onOpen(entry.folder) },
                    onLongClick = { onFolderMenu(entry) },
                    pinned = ItemRef.folder(entry.folder).key in pinned,
                )
            }
            if (contents.subfolders.isNotEmpty() && contents.tracks.isNotEmpty()) item { Spacer(Modifier.height(8.dp)) }
            itemsIndexed(contents.tracks, key = { _, t -> "track:${t.id}" }) { index, track ->
                SongRow(
                    track,
                    onClick = selection?.click(track.uri) { onPlayTrack(contents.tracks, index) } ?: { onPlayTrack(contents.tracks, index) },
                    onLongClick = selection?.longClick(track.uri) { onSongMenu(track) } ?: { onSongMenu(track) },
                    pinned = track.uri in pinned,
                    selected = selection?.mark(track.uri),
                    modifier = Modifier.dragSelectRow(selection, track.uri),
                )
            }
        }
    }
}
