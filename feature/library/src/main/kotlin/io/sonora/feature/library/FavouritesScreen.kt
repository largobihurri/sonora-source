package io.sonora.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.PillButton
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.designsystem.layout.ContentWidth
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.feature.library.ui.EmptyMessage
import io.sonora.feature.library.ui.TranslucentBarPage
import io.sonora.feature.library.ui.TranslucentTopBarSpace
import io.sonora.feature.library.ui.PlaylistRow
import io.sonora.feature.library.ui.SongRow
import io.sonora.feature.library.ui.pressable

/**
 * The Favourites smart playlist: Play / Shuffle and the favourite songs, then the favourite albums and playlists,
 * each opening its page; long-press opens the context menu.
 */
@Composable
internal fun FavouritesScreen(
    onBack: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onSongMenu: (Track) -> Unit,
    onAlbumMenu: (Album) -> Unit,
    onPlaylistMenu: (ResolvedPlaylist) -> Unit,
    viewModel: FavouritesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasSongs = state.songs.isNotEmpty()
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    TranslucentBarPage(bar = { BackBar(stringResource(R.string.playlists_title), onBack, onMore = null) }) {
        ContentWidthLazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = BottomChromePadding),
            max = ContentWidth.TABLE_DP.dp,
        ) {
            item(key = "header") {
                TranslucentTopBarSpace()
                LargeTitle(stringResource(R.string.favourites_title))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton(stringResource(R.string.library_play), SonoraIcons.Play, { viewModel.play(0) }, Modifier.weight(1f), enabled = hasSongs)
                    PillButton(stringResource(R.string.library_shuffle), SonoraIcons.Shuffle, viewModel::shuffle, Modifier.weight(1f), enabled = hasSongs)
                }
                InsetDivider(start = 20.dp)
                if (!state.loading && state.songs.isEmpty() && state.albums.isEmpty() && state.playlists.isEmpty()) {
                    EmptyMessage(stringResource(R.string.favourites_empty))
                }
            }
            itemsIndexed(state.songs, key = { _, t -> "song:${t.id}" }) { index, track ->
                SongRow(track, onClick = { viewModel.play(index) }, onLongClick = { onSongMenu(track) })
            }
            if (state.albums.isNotEmpty()) {
                item(key = "albumsTitle") { SectionHeading(stringResource(R.string.library_albums)) }
                items(state.albums, key = { "album:${it.id}" }) { album ->
                    AlbumRow(album, onClick = { onOpenAlbum(album) }, onLongClick = { onAlbumMenu(album) })
                }
            }
            if (state.playlists.isNotEmpty()) {
                item(key = "playlistsTitle") { SectionHeading(stringResource(R.string.playlists_title)) }
                items(state.playlists, key = { "playlist:${it.playlist.id}" }) { playlist ->
                    PlaylistRow(playlist, onClick = { onOpenPlaylist(playlist.playlist.id) }, onLongClick = { onPlaylistMenu(playlist) })
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.pressable(onClick, onLongClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(album.artworkUri, Modifier.size(56.dp), RoundedCornerShape(6.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(album.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        InsetDivider(start = 88.dp)
    }
}
