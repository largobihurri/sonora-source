package io.sonora.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BannerState
import io.sonora.core.designsystem.component.ConfirmationBanner
import io.sonora.core.designsystem.component.rememberBannerState
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.domain.model.Track
import io.sonora.feature.library.ui.AddToPlaylistSheet
import io.sonora.feature.library.ui.PlaylistDetailsDialog

/** A banner the "Add to Playlist" flow shows: [text] (with [arg] when it takes one), confirming or warning. */
internal data class PlaylistBanner(@StringRes val text: Int, val arg: String? = null, val success: Boolean)

/** What the user is told about [this] outcome; `null` for a playlist made elsewhere, which opens instead. */
internal fun PlaylistEvent.banner(): PlaylistBanner? = when (this) {
    is PlaylistEvent.Added -> PlaylistBanner(R.string.playlist_added_to, playlistName, success = true)
    PlaylistEvent.NothingToAdd -> PlaylistBanner(R.string.playlist_nothing_to_add, success = false)
    PlaylistEvent.AddFailed -> PlaylistBanner(R.string.playlist_add_failed, success = false)
    is PlaylistEvent.Created -> null
}

internal enum class AddToPlaylistStep { Picking, Naming }

/** Where one "Add to Playlist" request is: picking a playlist, then naming a new one after "New Playlist…". */
@Stable
internal class AddToPlaylistFlow {
    var step by mutableStateOf(AddToPlaylistStep.Picking)
        private set

    fun newPlaylist() {
        step = AddToPlaylistStep.Naming
    }

    /** The picker went away; true when that ends the flow (it does not after "New Playlist…", which names next). */
    fun pickerDismissed(): Boolean = step == AddToPlaylistStep.Picking
}

/**
 * The "Add to Playlist" flow over a screen: while [source] is set, the playlist picker, then the "New Playlist"
 * dialog when chosen ([onClose] runs once the flow ends). It also turns [viewModel]'s events into [banner] messages
 * ([banner]: added, nothing to add, failed) and [onCreated] (a playlist made elsewhere, to open). One host per
 * [viewModel]: events go to one collector.
 */
@Composable
internal fun AddToPlaylistHost(
    source: PlaylistSource?,
    onClose: () -> Unit,
    banner: BannerState,
    viewModel: PlaylistsViewModel,
    onCreated: (Long) -> Unit = {},
) {
    val resources = LocalResources.current
    val latestOnCreated by rememberUpdatedState(onCreated)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is PlaylistEvent.Created) latestOnCreated(event.playlistId)
            event.banner()?.let { shown ->
                val text = if (shown.arg == null) resources.getString(shown.text) else resources.getString(shown.text, shown.arg)
                banner.show(text, if (shown.success) SonoraIcons.Checkmark else SonoraIcons.Info)
            }
        }
    }

    if (source == null) return
    val flow = remember(source) { AddToPlaylistFlow() }
    when (flow.step) {
        AddToPlaylistStep.Naming -> PlaylistDetailsDialog(
            title = stringResource(R.string.playlist_new_title),
            confirmLabel = stringResource(R.string.playlist_create),
            onConfirm = { name, description ->
                viewModel.createAndAdd(name, description, source)
                onClose()
            },
            onDismiss = onClose,
        )
        AddToPlaylistStep.Picking -> {
            val state by viewModel.state.collectAsStateWithLifecycle()
            AddToPlaylistSheet(
                playlists = state.playlists,
                onNew = flow::newPlaylist,
                onPick = { playlist -> viewModel.addTo(playlist.id, playlist.name, source) },
                onDismiss = { if (flow.pickerDismissed()) onClose() },
            )
        }
    }
}

/**
 * "Add to Playlist" for [tracks] from outside the Library tab (Now Playing): the playlist picker, "New Playlist…" and
 * the "Added to …" banner at the top of the screen. It has its own [viewModel] (keyed apart from the Library's), so
 * its outcomes never reach the Library's banner. [onClose] runs once the picker or the dialog is done.
 */
@Composable
fun AddToPlaylistOverlay(
    tracks: List<Track>?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = hiltViewModel(key = "add-to-playlist-overlay"),
) {
    val banner = rememberBannerState()
    val source = remember(tracks) { tracks?.takeIf { it.isNotEmpty() }?.let(PlaylistSource::Tracks) }
    Box(modifier) {
        ConfirmationBanner(
            banner,
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp),
            backdrop = null,
        )
    }
    AddToPlaylistHost(source = source, onClose = onClose, banner = banner, viewModel = viewModel)
}
