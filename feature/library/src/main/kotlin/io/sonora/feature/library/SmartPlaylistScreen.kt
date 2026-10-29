package io.sonora.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.PillButton
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.designsystem.layout.ContentWidth
import io.sonora.core.domain.library.smart.SmartPlaylist
import io.sonora.core.domain.library.smart.SmartPlaylistRules
import io.sonora.core.domain.model.Track
import io.sonora.feature.library.ui.EmptyMessage
import io.sonora.feature.library.ui.TranslucentBarPage
import io.sonora.feature.library.ui.TranslucentTopBarSpace
import io.sonora.feature.library.ui.SongRow

/** The smart playlists Playlists lists after Favourites, in this order. */
internal val SmartPlaylistsInOrder = listOf(SmartPlaylist.RECENTLY_ADDED, SmartPlaylist.MOST_PLAYED, SmartPlaylist.NEVER_PLAYED)

/** The playlist's name, as Playlists and its page show it. */
@Composable
internal fun SmartPlaylist.title(): String = stringResource(
    when (this) {
        SmartPlaylist.RECENTLY_ADDED -> R.string.smart_recently_added_title
        SmartPlaylist.MOST_PLAYED -> R.string.smart_most_played_title
        SmartPlaylist.NEVER_PLAYED -> R.string.smart_never_played_title
    },
)

/** The playlist's tile icon in Playlists. */
internal val SmartPlaylist.icon: ImageVector
    get() = when (this) {
        SmartPlaylist.RECENTLY_ADDED -> SonoraIcons.History
        SmartPlaylist.MOST_PLAYED -> SonoraIcons.Repeat
        SmartPlaylist.NEVER_PLAYED -> SonoraIcons.MusicNote
    }

@Composable
private fun SmartPlaylist.emptyMessage(): String = when (this) {
    SmartPlaylist.RECENTLY_ADDED -> pluralStringResource(
        R.plurals.smart_recently_added_empty,
        SmartPlaylistRules.RECENTLY_ADDED_DAYS,
        SmartPlaylistRules.RECENTLY_ADDED_DAYS,
    )
    SmartPlaylist.MOST_PLAYED -> stringResource(R.string.smart_most_played_empty)
    SmartPlaylist.NEVER_PLAYED -> stringResource(R.string.smart_never_played_empty)
}

/**
 * A smart playlist's page (Recently Added, Most Played, Never Played): Play / Shuffle and its songs in the playlist's
 * order, kept live; tapping a song plays the list from it, long-press opens the context menu.
 */
@Composable
internal fun SmartPlaylistScreen(
    playlist: SmartPlaylist,
    onBack: () -> Unit,
    onSongMenu: (Track) -> Unit,
    viewModel: SmartPlaylistViewModel = hiltViewModel<SmartPlaylistViewModel, SmartPlaylistViewModel.Factory>(
        key = "smart:${playlist.name}",
    ) { it.create(playlist) },
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
                LargeTitle(playlist.title())
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton(stringResource(R.string.library_play), SonoraIcons.Play, { viewModel.play(0) }, Modifier.weight(1f), enabled = hasSongs)
                    PillButton(stringResource(R.string.library_shuffle), SonoraIcons.Shuffle, viewModel::shuffle, Modifier.weight(1f), enabled = hasSongs)
                }
                InsetDivider(start = 20.dp)
                if (!state.loading && !hasSongs) EmptyMessage(playlist.emptyMessage())
            }
            itemsIndexed(state.songs, key = { _, t -> "song:${t.id}" }) { index, track ->
                SongRow(track, onClick = { viewModel.play(index) }, onLongClick = { onSongMenu(track) })
            }
        }
    }
}
