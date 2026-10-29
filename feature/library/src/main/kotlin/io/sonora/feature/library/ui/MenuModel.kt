package io.sonora.feature.library.ui

import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.feature.library.PlaylistSource

/** What a long-press opened the context menu for. */
internal sealed interface MenuTarget {
    /** How favourites, pins and hidden items name it. */
    val ref: ItemRef

    data class Song(val track: Track) : MenuTarget {
        override val ref get() = ItemRef.song(track)
    }

    data class AlbumItem(val album: Album) : MenuTarget {
        override val ref get() = ItemRef.album(album)
    }

    data class ArtistItem(val artistId: Long, val name: String, val artworkUri: String? = null) : MenuTarget {
        override val ref get() = ItemRef.artist(artistId)
    }

    data class PlaylistItem(val playlist: ResolvedPlaylist) : MenuTarget {
        override val ref get() = ItemRef.playlist(playlist.playlist.id)
    }

    /** Every song in the folder and its subfolders. */
    data class FolderItem(val entry: FolderEntry) : MenuTarget {
        override val ref get() = ItemRef.folder(entry.folder)
    }
}

/** The songs [this] target stands for, resolved when an action runs. */
internal fun MenuTarget.playlistSource(): PlaylistSource = when (this) {
    is MenuTarget.Song -> PlaylistSource.Tracks(listOf(track))
    is MenuTarget.AlbumItem -> PlaylistSource.Album(album.id)
    is MenuTarget.ArtistItem -> PlaylistSource.Artist(artistId)
    is MenuTarget.PlaylistItem -> PlaylistSource.Tracks(playlist.playableTracks)
    is MenuTarget.FolderItem -> PlaylistSource.Folder(entry.folder)
}

/**
 * Where the menu was opened: the list whose pins it changes ([pins], `null` where nothing can be pinned, such as
 * search results), and the album or artist page shown, whose own "Go to" item would lead nowhere new.
 */
internal data class MenuPlace(val pins: PinContext? = null, val albumId: Long? = null, val artistId: Long? = null) {
    companion object {
        val Unpinnable = MenuPlace()
    }
}

/** One context menu entry. */
internal enum class MenuAction {
    FAVOURITE,
    SHARE,
    EXPORT_REKORDBOX,
    PIN,
    ADD_TO_PLAYLIST,
    PLAY,
    SHUFFLE,
    PLAY_NOW,
    PLAY_NEXT,
    PLAY_LAST,
    GO_TO_ALBUM,
    GO_TO_ARTIST,
    VIEW_INFO,
    CONVERT,
    COMPRESS,
    SEND_TO_WATCH,
    REMOVE,
}

/** A context menu: the row of equal buttons on top, then the grouped items; [disabled] ones show dimmed. */
internal data class MenuModel(
    val top: List<MenuAction>,
    val sections: List<List<MenuAction>>,
    val disabled: Set<MenuAction> = emptySet(),
)

/**
 * What the app can do right now: show File Info ([canShowInfo]), hand songs to the CDJ export ([canExport]) and send them
 * to the watch ([canSendToWatch]; false in a build without the watch link).
 */
internal data class MenuOptions(val canShowInfo: Boolean, val canExport: Boolean, val canSendToWatch: Boolean = true)

/** The iOS 26 Music context menus for songs, albums, artists, playlists and folders. */
internal object LibraryMenuModel {
    fun of(target: MenuTarget, place: MenuPlace, options: MenuOptions): MenuModel {
        val pin = listOfNotNull(MenuAction.PIN.takeIf { place.pins != null })
        val collection = listOf(MenuAction.PLAY, MenuAction.SHUFFLE, MenuAction.PLAY_NEXT, MenuAction.PLAY_LAST)
        val (top, sections) = when (target) {
            is MenuTarget.Song -> {
                val track = target.track
                val goTo = listOfNotNull(
                    MenuAction.GO_TO_ALBUM.takeIf { track.albumId != 0L && track.albumId != place.albumId },
                    MenuAction.GO_TO_ARTIST.takeIf { track.artistId != 0L && track.artistId != place.artistId },
                )
                listOf(MenuAction.FAVOURITE, MenuAction.SHARE) to listOf(
                    pin + MenuAction.ADD_TO_PLAYLIST,
                    listOf(MenuAction.PLAY_NOW, MenuAction.PLAY_NEXT, MenuAction.PLAY_LAST),
                    goTo,
                    listOfNotNull(MenuAction.VIEW_INFO.takeIf { options.canShowInfo }, MenuAction.CONVERT, MenuAction.COMPRESS),
                    listOf(MenuAction.REMOVE),
                )
            }
            is MenuTarget.AlbumItem -> EXPORTABLE_TOP to listOf(
                pin + MenuAction.ADD_TO_PLAYLIST,
                collection,
                listOfNotNull(MenuAction.GO_TO_ARTIST.takeIf { target.album.artistId != 0L && target.album.artistId != place.artistId }),
                listOfNotNull(MenuAction.CONVERT, MenuAction.COMPRESS, MenuAction.SEND_TO_WATCH.takeIf { options.canSendToWatch }),
                listOf(MenuAction.REMOVE),
            )
            is MenuTarget.ArtistItem -> listOf(MenuAction.FAVOURITE, MenuAction.SHARE) to listOf(
                pin + MenuAction.ADD_TO_PLAYLIST,
                collection,
                listOf(MenuAction.CONVERT),
                listOf(MenuAction.REMOVE),
            )
            is MenuTarget.PlaylistItem -> EXPORTABLE_TOP to listOf(
                pin + MenuAction.ADD_TO_PLAYLIST,
                collection,
                listOfNotNull(MenuAction.CONVERT, MenuAction.COMPRESS, MenuAction.SEND_TO_WATCH.takeIf { options.canSendToWatch }),
                listOf(MenuAction.REMOVE),
            )
            is MenuTarget.FolderItem -> emptyList<MenuAction>() to listOf(
                pin + MenuAction.ADD_TO_PLAYLIST,
                collection,
                listOf(MenuAction.CONVERT),
            )
        }
        val shown = top + sections.flatten()
        val disabled = buildSet {
            if (!options.canExport) add(MenuAction.EXPORT_REKORDBOX)
            if (target.isEmpty) addAll(NEEDS_SONGS)
        }
        return MenuModel(top, sections.filter { it.isNotEmpty() }, disabled.filterTo(LinkedHashSet()) { it in shown })
    }

    private val EXPORTABLE_TOP = listOf(MenuAction.FAVOURITE, MenuAction.SHARE, MenuAction.EXPORT_REKORDBOX)

    /** Items that do something with the target's songs. */
    private val NEEDS_SONGS = listOf(
        MenuAction.SHARE, MenuAction.EXPORT_REKORDBOX, MenuAction.PLAY, MenuAction.SHUFFLE, MenuAction.PLAY_NOW,
        MenuAction.PLAY_NEXT, MenuAction.PLAY_LAST, MenuAction.ADD_TO_PLAYLIST, MenuAction.CONVERT, MenuAction.COMPRESS,
        MenuAction.SEND_TO_WATCH,
    )

    /** Known to hold no songs (an artist's count is not at hand, so it never is). */
    private val MenuTarget.isEmpty: Boolean
        get() = when (this) {
            is MenuTarget.Song -> false
            is MenuTarget.AlbumItem -> album.trackCount == 0
            is MenuTarget.ArtistItem -> false
            is MenuTarget.PlaylistItem -> playlist.playableTracks.isEmpty()
            is MenuTarget.FolderItem -> entry.trackCount == 0
        }
}
