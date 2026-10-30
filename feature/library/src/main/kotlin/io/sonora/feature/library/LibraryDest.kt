package io.sonora.feature.library

import io.sonora.core.designsystem.layout.WindowClass
import io.sonora.core.domain.library.smart.SmartPlaylist
import io.sonora.core.domain.model.FolderRef
import java.net.URLDecoder
import java.net.URLEncoder

/** In-tab destinations of the Library stack. Details carry ids so the stack can be saved and restored. */
internal sealed interface LibraryDest {
    data object Home : LibraryDest
    data object Songs : LibraryDest
    data object Albums : LibraryDest
    data object Artists : LibraryDest
    data object Settings : LibraryDest
    data class AlbumDetail(val albumId: Long) : LibraryDest
    data class ArtistDetail(val artistId: Long, val name: String) : LibraryDest
    data object Folders : LibraryDest
    data class Folder(val folder: FolderRef) : LibraryDest
    data object Converted : LibraryDest
    data object Playlists : LibraryDest
    data class PlaylistDetail(val playlistId: Long) : LibraryDest

    /** The Favourites smart playlist at the top of Playlists. */
    data object Favourites : LibraryDest

    /** A smart playlist listed after Favourites in Playlists (Recently Added, Most Played, Never Played). */
    data class Smart(val playlist: SmartPlaylist) : LibraryDest
    /** Listening statistics: time heard, plays and the top artists, albums and songs. */
    data object Statistics : LibraryDest
}

/**
 * A page of the Library the app shell can open directly (the large-screen sidebar lists them under Library), and the
 * page a sidebar row highlights while the Library shows it or one of its details.
 */
enum class LibraryPage { Playlists, Songs, Albums, Artists, Folders, Converted, Statistics, Settings }

/** The page this destination belongs to (a detail belongs to its list); `null` for the Library's home. */
internal val LibraryDest.page: LibraryPage?
    get() = when (this) {
        LibraryDest.Home -> null
        LibraryDest.Songs -> LibraryPage.Songs
        LibraryDest.Albums, is LibraryDest.AlbumDetail -> LibraryPage.Albums
        LibraryDest.Artists, is LibraryDest.ArtistDetail -> LibraryPage.Artists
        LibraryDest.Playlists, is LibraryDest.PlaylistDetail, LibraryDest.Favourites, is LibraryDest.Smart -> LibraryPage.Playlists
        LibraryDest.Folders, is LibraryDest.Folder -> LibraryPage.Folders
        LibraryDest.Converted -> LibraryPage.Converted
        LibraryDest.Statistics -> LibraryPage.Statistics
        LibraryDest.Settings -> LibraryPage.Settings
    }

/** The destination that opens [this] page (the Library's one Settings page included). */
private val LibraryPage.dest: LibraryDest
    get() = when (this) {
        LibraryPage.Playlists -> LibraryDest.Playlists
        LibraryPage.Songs -> LibraryDest.Songs
        LibraryPage.Albums -> LibraryDest.Albums
        LibraryPage.Artists -> LibraryDest.Artists
        LibraryPage.Folders -> LibraryDest.Folders
        LibraryPage.Converted -> LibraryDest.Converted
        LibraryPage.Statistics -> LibraryDest.Statistics
        LibraryPage.Settings -> LibraryDest.Settings
    }

/** What a page shows when the phone turns sideways: the visual collection of [list], opened on [target] (the first cover when null). */
internal data class LibraryVisualCollectionView(val list: LibraryDest, val target: VisualCollectionTarget?)

/**
 * The visual collection a page turns into sideways: a list its own, an album, artist or playlist page the visual collection of its
 * kind's list centred on itself (wherever the page was opened from); `null` for the pages that stay upright (Home,
 * Settings, folders, converted music, the smart playlists (Favourites too), Statistics).
 */
internal val LibraryDest.visualCollection: LibraryVisualCollectionView?
    get() = when (this) {
        LibraryDest.Songs, LibraryDest.Albums, LibraryDest.Artists, LibraryDest.Playlists -> LibraryVisualCollectionView(this, null)
        is LibraryDest.AlbumDetail -> LibraryVisualCollectionView(LibraryDest.Albums, VisualCollectionTarget.Album(albumId))
        is LibraryDest.ArtistDetail -> LibraryVisualCollectionView(LibraryDest.Artists, VisualCollectionTarget.Artist(artistId, name))
        is LibraryDest.PlaylistDetail -> LibraryVisualCollectionView(LibraryDest.Playlists, VisualCollectionTarget.Playlist(playlistId))
        else -> null
    }

/** The pages shown as a visual collection when the phone turns sideways (see [visualCollection]). */
internal val LibraryDest.hasVisualCollection: Boolean
    get() = visualCollection != null

/**
 * Whether this page offers the visual collection button on [windowClass]: Albums, Artists and Playlists on a large screen, where the
 * Visual collection is no longer a takeover of the sideways turn but a view opened from the page (a phone keeps the turn).
 */
internal fun LibraryDest.hasVisualCollectionButton(windowClass: WindowClass): Boolean =
    windowClass.isLarge && (this == LibraryDest.Albums || this == LibraryDest.Artists || this == LibraryDest.Playlists)

private const val ALBUM = "album:"
private const val ARTIST = "artist:"
private const val FOLDER = "folder:"
private const val PLAYLIST = "playlist:"
private const val SMART = "smart:"
private const val UTF_8 = "UTF-8" // the Charset overloads need API 33

private fun encodePart(value: String): String = URLEncoder.encode(value, UTF_8)

private fun decodePart(value: String): String? =
    runCatching { URLDecoder.decode(value, UTF_8) }.getOrNull()?.takeIf { it.isNotEmpty() }

/** Encodes the stack as strings for `rememberSaveable` (Bundle-friendly). */
internal fun encodeLibraryStack(stack: List<LibraryDest>): List<String> = stack.map { dest ->
    when (dest) {
        LibraryDest.Home -> "home"
        LibraryDest.Songs -> "songs"
        LibraryDest.Albums -> "albums"
        LibraryDest.Artists -> "artists"
        LibraryDest.Settings -> "settings"
        is LibraryDest.AlbumDetail -> "$ALBUM${dest.albumId}"
        is LibraryDest.ArtistDetail -> "$ARTIST${dest.artistId}:${dest.name}"
        LibraryDest.Folders -> "folders"
        LibraryDest.Converted -> "converted"
        LibraryDest.Playlists -> "playlists"
        is LibraryDest.PlaylistDetail -> "$PLAYLIST${dest.playlistId}"
        LibraryDest.Favourites -> "favourites"
        is LibraryDest.Smart -> "$SMART${dest.playlist.name}"
        LibraryDest.Statistics -> "statistics"
        // Both parts are URL-encoded, so neither contains the ':' between them.
        is LibraryDest.Folder -> "$FOLDER${encodePart(dest.folder.treeUri)}:${encodePart(dest.folder.path)}"
    }
}

/** Restores an encoded stack, dropping unreadable entries; the result always starts at [LibraryDest.Home]. */
internal fun decodeLibraryStack(encoded: List<String>): List<LibraryDest> {
    val decoded = encoded.mapNotNull(::decodeDest).filter { it != LibraryDest.Home }
    return listOf(LibraryDest.Home) + decoded
}

private fun decodeDest(value: String): LibraryDest? = when {
    value == "home" -> LibraryDest.Home
    value == "songs" -> LibraryDest.Songs
    value == "albums" -> LibraryDest.Albums
    value == "artists" -> LibraryDest.Artists
    value == "settings" -> LibraryDest.Settings
    value == "folders" -> LibraryDest.Folders
    value == "converted" -> LibraryDest.Converted
    value == "playlists" -> LibraryDest.Playlists
    value == "favourites" -> LibraryDest.Favourites
    value.startsWith(SMART) -> SmartPlaylist.entries.firstOrNull { it.name == value.removePrefix(SMART) }?.let(LibraryDest::Smart)
    value == "statistics" -> LibraryDest.Statistics
    value.startsWith(PLAYLIST) -> value.removePrefix(PLAYLIST).toLongOrNull()?.let(LibraryDest::PlaylistDetail)
    value.startsWith(FOLDER) -> {
        val parts = value.removePrefix(FOLDER).split(':')
        val treeUri = parts.getOrNull(0)?.let(::decodePart)
        val path = parts.getOrNull(1)?.let(::decodePart)
        if (parts.size == 2 && treeUri != null && path != null) LibraryDest.Folder(FolderRef(treeUri, path)) else null
    }
    value.startsWith(ALBUM) -> value.removePrefix(ALBUM).toLongOrNull()?.let(LibraryDest::AlbumDetail)
    value.startsWith(ARTIST) -> {
        val parts = value.removePrefix(ARTIST).split(':', limit = 2)
        val id = parts[0].toLongOrNull()
        if (id != null && parts.size == 2) LibraryDest.ArtistDetail(id, parts[1]) else null
    }
    else -> null
}

/**
 * Closes [dest]'s page: removes it from [stack] wherever it is (a page closing itself, e.g. a deleted playlist, must
 * not pop whatever page is on top). [LibraryDest.Home] always stays.
 */
internal fun closeDestination(stack: MutableList<LibraryDest>, dest: LibraryDest) {
    if (dest != LibraryDest.Home) stack.removeAll { it == dest }
}

/**
 * Opens the page another tab asked for ([link]): an album or artist is pushed on top of [stack]. Settings is pushed too,
 * so back returns to the page it covered, unless it is already open: then the pages above it close, so there is only
 * ever one Settings page. A [LibraryLink.Page] (a sidebar row) replaces whatever the Library shows with that page on top
 * of Home, or just closes the pages above it when it is already there; its Settings is the Settings link.
 */
internal fun openLink(stack: MutableList<LibraryDest>, link: LibraryLink) {
    when (link) {
        is LibraryLink.Album -> stack += LibraryDest.AlbumDetail(link.albumId)
        is LibraryLink.Artist -> stack += LibraryDest.ArtistDetail(link.artistId, link.name)
        LibraryLink.Settings -> openSettings(stack)
        is LibraryLink.Page -> if (link.page == LibraryPage.Settings) openSettings(stack) else openPage(stack, link.page.dest)
    }
}

private fun openPage(stack: MutableList<LibraryDest>, dest: LibraryDest) {
    if (stack.getOrNull(1) == dest) {
        while (stack.lastIndex > 1) stack.removeAt(stack.lastIndex)
    } else {
        while (stack.lastIndex > 0) stack.removeAt(stack.lastIndex)
        stack += dest
    }
}

private fun openSettings(stack: MutableList<LibraryDest>) {
    val open = stack.indexOf(LibraryDest.Settings)
    if (open < 0) stack += LibraryDest.Settings else while (stack.lastIndex > open) stack.removeAt(stack.lastIndex)
}
