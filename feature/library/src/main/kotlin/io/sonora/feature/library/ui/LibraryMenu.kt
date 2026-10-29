package io.sonora.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.ArtworkMosaic
import io.sonora.core.designsystem.component.ContextMenuContent
import io.sonora.core.designsystem.component.ContextMenuItem
import io.sonora.core.designsystem.component.ContextMenuPanel
import io.sonora.core.designsystem.component.ContextMenuPreviewCard
import io.sonora.core.designsystem.component.ContextMenuScope
import io.sonora.core.designsystem.component.LocalContextMenuHost
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.model.QueueAction
import io.sonora.core.domain.model.Track
import io.sonora.feature.library.CompressTarget
import io.sonora.feature.library.LibraryMenuViewModel
import io.sonora.feature.library.PlaylistSource
import io.sonora.feature.library.R
import kotlinx.coroutines.flow.flowOf

/** "No Songs", "1 Song", "1,260 Songs", with the number grouped as the language does. */
internal fun songCount(count: Int, strings: Strings): String =
    if (count == 0) {
        strings.get(R.string.library_no_songs)
    } else {
        strings.plural(R.plurals.library_song_count, count, LocaleFormats.integer(count, strings.locale))
    }

/** Banner text confirming an action that does not visibly start playback. */
internal fun QueueAction.confirmationText(strings: Strings): String? = when (this) {
    QueueAction.PLAY_NOW -> null
    QueueAction.PLAY_NEXT -> strings.get(R.string.queue_playing_next)
    QueueAction.ADD_TO_QUEUE -> strings.get(R.string.queue_added_to_queue)
}

/** Opens the context menu for a long-pressed item listed in [MenuPlace]. */
internal fun interface MenuOpener {
    fun open(target: MenuTarget, place: MenuPlace)
}

/** What the menu hands to its screen; a `null` destination leaves its items out (Search cannot open pages yet). */
internal class MenuHandlers(
    val onAddToPlaylist: (PlaylistSource) -> Unit,
    val onConvert: (PlaylistSource) -> Unit,
    val onCompress: (CompressTarget) -> Unit,
    val onOpenAlbum: ((albumId: Long) -> Unit)?,
    val onOpenArtist: ((artistId: Long, name: String) -> Unit)?,
    val onShowInfo: ((Track) -> Unit)?,
)

/** The name a target is shown, hidden and exported by. */
internal val MenuTarget.title: String
    get() = when (this) {
        is MenuTarget.Song -> track.title
        is MenuTarget.AlbumItem -> album.title
        is MenuTarget.ArtistItem -> name
        is MenuTarget.PlaylistItem -> playlist.playlist.name
        is MenuTarget.FolderItem -> entry.folder.name
    }

/** The line Settings › Hidden Items lists a target with. */
internal val MenuTarget.subtitle: String
    get() = when (this) {
        is MenuTarget.Song -> track.artist
        is MenuTarget.AlbumItem -> album.artist
        else -> ""
    }

/**
 * The iOS 26 context menu of the library (see [LibraryMenuModel]), shown by the enclosing context menu host. Items
 * act through [viewModel]; navigation and sheets wait until the menu has closed back into the row.
 */
@Composable
internal fun rememberLibraryMenu(viewModel: LibraryMenuViewModel, handlers: MenuHandlers): MenuOpener {
    val host = LocalContextMenuHost.current
    val latest by rememberUpdatedState(handlers)
    return remember(host, viewModel) {
        MenuOpener { target, place ->
            host?.show(
                ContextMenuContent(
                    card = { MenuPreview(target) },
                    menu = { scope -> LibraryMenuPanel(target, place, viewModel, latest, scope) },
                ),
            )
        }
    }
}

@Composable
private fun MenuPreview(target: MenuTarget) {
    val strings = rememberStrings()
    when (target) {
        is MenuTarget.Song -> {
            val track = target.track
            val albumLine = track.year?.let { strings.get(R.string.menu_album_year, track.album, it.toString()) } ?: track.album
            ContextMenuPreviewCard(track.artworkUri, track.title, listOf(track.artist, albumLine).filter { it.isNotBlank() })
        }
        is MenuTarget.AlbumItem -> {
            val album = target.album
            val count = songCount(album.trackCount, strings)
            val line = album.year?.let { strings.get(R.string.menu_album_year, it.toString(), count) } ?: count
            ContextMenuPreviewCard(album.artworkUri, album.title, listOf(album.artist, line))
        }
        is MenuTarget.ArtistItem -> ContextMenuPreviewCard(target.artworkUri, target.name, emptyList(), artworkShape = RoundedCornerShape(50))
        is MenuTarget.PlaylistItem -> ContextMenuPreviewCard(
            null,
            target.playlist.playlist.name,
            listOf(songCount(target.playlist.songCount, strings)),
            artwork = { modifier -> ArtworkMosaic(target.playlist.coverArtworkUris, modifier, RoundedCornerShape(10.dp)) },
        )
        is MenuTarget.FolderItem -> ContextMenuPreviewCard(
            null,
            target.entry.folder.name,
            listOf(songCount(target.entry.trackCount, strings)),
            artwork = { modifier ->
                Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                    Icon(SonoraIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                }
            },
        )
    }
}

@Composable
private fun LibraryMenuPanel(
    target: MenuTarget,
    place: MenuPlace,
    viewModel: LibraryMenuViewModel,
    handlers: MenuHandlers,
    scope: ContextMenuScope,
) {
    val strings = rememberStrings()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val pinsFlow = remember(place.pins) { place.pins?.let(viewModel::pins) ?: flowOf(emptyList()) }
    val pins by pinsFlow.collectAsStateWithLifecycle(emptyList())
    val model = remember(target, place, handlers.onShowInfo != null) {
        val options = MenuOptions(
            canShowInfo = handlers.onShowInfo != null,
            canExport = viewModel.canExport,
            canSendToWatch = viewModel.canSendToWatch,
        )
        LibraryMenuModel.of(target, place, options)
    }
    val available: (MenuAction) -> Boolean = { action ->
        when (action) {
            MenuAction.GO_TO_ALBUM -> handlers.onOpenAlbum != null
            MenuAction.GO_TO_ARTIST -> handlers.onOpenArtist != null
            else -> true
        }
    }

    fun item(action: MenuAction): ContextMenuItem {
        fun entry(label: Int, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) =
            ContextMenuItem(strings.get(label), icon, enabled = action !in model.disabled, destructive = destructive, onClick = onClick)
        fun closeThen(block: () -> Unit) = scope.close(block)
        fun now(block: () -> Unit) {
            block()
            scope.close(null)
        }
        return when (action) {
            MenuAction.FAVOURITE -> {
                val favourite = target.ref in favourites
                entry(
                    if (favourite) R.string.menu_remove_favourite else R.string.menu_add_favourite,
                    if (favourite) SonoraIcons.StarFilled else SonoraIcons.Star,
                ) { now { viewModel.toggleFavourite(target) } }
            }
            MenuAction.SHARE -> entry(R.string.menu_share, SonoraIcons.Share) { now { viewModel.share(target) } }
            MenuAction.EXPORT_REKORDBOX -> entry(R.string.menu_export_rekordbox, SonoraIcons.Usb) {
                now { viewModel.exportToRekordbox(target, target.title) }
            }
            MenuAction.PIN -> {
                val pinned = target.ref.key in pins
                entry(pinLabel(target.ref.kind, pinned), SonoraIcons.Pin) {
                    now { place.pins?.let { viewModel.setPinned(it, target, !pinned) } }
                }
            }
            MenuAction.ADD_TO_PLAYLIST -> entry(R.string.playlist_add_to, SonoraIcons.Add) {
                closeThen { handlers.onAddToPlaylist(target.playlistSource()) }
            }
            MenuAction.PLAY -> entry(R.string.library_play, SonoraIcons.Play) { now { viewModel.queue(target, action) } }
            MenuAction.SHUFFLE -> entry(R.string.library_shuffle, SonoraIcons.Shuffle) { now { viewModel.queue(target, action) } }
            MenuAction.PLAY_NOW -> entry(R.string.queue_play_now, SonoraIcons.Play) { now { viewModel.queue(target, action) } }
            MenuAction.PLAY_NEXT -> entry(R.string.queue_play_next, SonoraIcons.PlayNext) { now { viewModel.queue(target, action) } }
            MenuAction.PLAY_LAST -> entry(R.string.menu_play_last, SonoraIcons.PlayLast) { now { viewModel.queue(target, action) } }
            MenuAction.GO_TO_ALBUM -> entry(R.string.menu_go_to_album, SonoraIcons.Album) {
                val track = (target as? MenuTarget.Song)?.track
                closeThen { if (track != null) handlers.onOpenAlbum?.invoke(track.albumId) }
            }
            MenuAction.GO_TO_ARTIST -> entry(R.string.menu_go_to_artist, SonoraIcons.Person) {
                val artist = when (target) {
                    is MenuTarget.Song -> target.track.artistId to target.track.artist
                    is MenuTarget.AlbumItem -> target.album.artistId to target.album.artist
                    else -> null
                }
                closeThen { if (artist != null) handlers.onOpenArtist?.invoke(artist.first, artist.second) }
            }
            MenuAction.VIEW_INFO -> entry(R.string.menu_view_info, SonoraIcons.Info) {
                val track = (target as? MenuTarget.Song)?.track
                closeThen { if (track != null) handlers.onShowInfo?.invoke(track) }
            }
            MenuAction.CONVERT -> entry(R.string.convert_action, SonoraIcons.Convert) {
                closeThen { handlers.onConvert(target.playlistSource()) }
            }
            MenuAction.COMPRESS -> entry(R.string.compress_action, SonoraIcons.Archive) {
                val compress = target.compressTarget()
                closeThen { if (compress != null) handlers.onCompress(compress) }
            }
            MenuAction.SEND_TO_WATCH -> entry(R.string.menu_send_to_watch, SonoraIcons.Watch) { now { viewModel.sendToWatch(target) } }
            MenuAction.REMOVE -> entry(R.string.menu_remove, SonoraIcons.Trash, destructive = true) {
                closeThen { viewModel.remove(target) }
            }
        }
    }

    ContextMenuPanel(
        top = model.top.filter(available).map(::item),
        sections = model.sections.map { section -> section.filter(available).map(::item) }.filter { it.isNotEmpty() },
    )
}

private fun pinLabel(kind: MarkKind, pinned: Boolean): Int = when (kind) {
    MarkKind.SONG -> if (pinned) R.string.menu_unpin_song else R.string.menu_pin_song
    MarkKind.ALBUM -> if (pinned) R.string.menu_unpin_album else R.string.menu_pin_album
    MarkKind.ARTIST -> if (pinned) R.string.menu_unpin_artist else R.string.menu_pin_artist
    MarkKind.PLAYLIST -> if (pinned) R.string.menu_unpin_playlist else R.string.menu_pin_playlist
    MarkKind.FOLDER -> if (pinned) R.string.menu_unpin_folder else R.string.menu_pin_folder
}
