package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.PlaylistItem
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.feature.library.ui.MenuAction.ADD_TO_PLAYLIST
import io.sonora.feature.library.ui.MenuAction.COMPRESS
import io.sonora.feature.library.ui.MenuAction.CONVERT
import io.sonora.feature.library.ui.MenuAction.EXPORT_REKORDBOX
import io.sonora.feature.library.ui.MenuAction.FAVOURITE
import io.sonora.feature.library.ui.MenuAction.GO_TO_ALBUM
import io.sonora.feature.library.ui.MenuAction.GO_TO_ARTIST
import io.sonora.feature.library.ui.MenuAction.PIN
import io.sonora.feature.library.ui.MenuAction.PLAY
import io.sonora.feature.library.ui.MenuAction.PLAY_LAST
import io.sonora.feature.library.ui.MenuAction.PLAY_NEXT
import io.sonora.feature.library.ui.MenuAction.PLAY_NOW
import io.sonora.feature.library.ui.MenuAction.REMOVE
import io.sonora.feature.library.ui.MenuAction.SEND_TO_WATCH
import io.sonora.feature.library.ui.MenuAction.SHARE
import io.sonora.feature.library.ui.MenuAction.SHUFFLE
import io.sonora.feature.library.ui.MenuAction.VIEW_INFO
import org.junit.Test

class MenuModelTest {

    private val track = Track(
        id = 1, uri = "content://song", title = "Bandido", artist = "Myke Towers", album = "Bandido", albumArtist = null,
        durationMs = 1, trackNumber = 1, discNumber = 1, year = 2020, albumId = 10, artistId = 20, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )
    private val album = Album(10, "Bandido", "Myke Towers", 2020, 1, null, artistId = 20)
    private val song = MenuTarget.Song(track)
    private val all = MenuOptions(canShowInfo = true, canExport = true)
    private val songs = MenuPlace(pins = PinContext.SONGS)

    @Test
    fun `a song in a list offers the iOS items in order`() {
        val menu = LibraryMenuModel.of(song, songs, all)
        assertThat(menu.top).containsExactly(FAVOURITE, SHARE).inOrder()
        assertThat(menu.sections).containsExactly(
            listOf(PIN, ADD_TO_PLAYLIST),
            listOf(PLAY_NOW, PLAY_NEXT, PLAY_LAST),
            listOf(GO_TO_ALBUM, GO_TO_ARTIST),
            listOf(VIEW_INFO, CONVERT, COMPRESS),
            listOf(REMOVE),
        ).inOrder()
        assertThat(menu.disabled).isEmpty()
    }

    @Test
    fun `where nothing can be pinned, a song has no Pin`() {
        val menu = LibraryMenuModel.of(song, MenuPlace.Unpinnable, all)
        assertThat(menu.sections.first()).containsExactly(ADD_TO_PLAYLIST)
    }

    @Test
    fun `on its own album page a song does not offer Go to Album`() {
        val menu = LibraryMenuModel.of(song, MenuPlace(PinContext.album(10), albumId = 10), all)
        assertThat(menu.sections).contains(listOf(GO_TO_ARTIST))
        assertThat(menu.sections.flatten()).doesNotContain(GO_TO_ALBUM)
    }

    @Test
    fun `on its artist page a song offers only Go to Album`() {
        val menu = LibraryMenuModel.of(song, MenuPlace(PinContext.artist(20), artistId = 20), all)
        assertThat(menu.sections).contains(listOf(GO_TO_ALBUM))
        assertThat(menu.sections.flatten()).doesNotContain(GO_TO_ARTIST)
    }

    @Test
    fun `a song outside the library index cannot go to an album or artist`() {
        val loose = MenuTarget.Song(track.copy(albumId = 0, artistId = 0))
        val menu = LibraryMenuModel.of(loose, MenuPlace(PinContext.CONVERTED), all)
        assertThat(menu.sections.flatten()).containsNoneOf(GO_TO_ALBUM, GO_TO_ARTIST)
    }

    @Test
    fun `without File Info, View Credits is left out`() {
        val menu = LibraryMenuModel.of(song, songs, all.copy(canShowInfo = false))
        assertThat(menu.sections).contains(listOf(CONVERT, COMPRESS))
        assertThat(menu.sections.flatten()).doesNotContain(VIEW_INFO)
    }

    @Test
    fun `an album has Export under Share and the collection actions`() {
        val menu = LibraryMenuModel.of(MenuTarget.AlbumItem(album), MenuPlace(PinContext.ALBUMS), all)
        assertThat(menu.top).containsExactly(FAVOURITE, SHARE, EXPORT_REKORDBOX).inOrder()
        assertThat(menu.sections).containsExactly(
            listOf(PIN, ADD_TO_PLAYLIST),
            listOf(PLAY, SHUFFLE, PLAY_NEXT, PLAY_LAST),
            listOf(GO_TO_ARTIST),
            listOf(CONVERT, COMPRESS, SEND_TO_WATCH),
            listOf(REMOVE),
        ).inOrder()
    }

    @Test
    fun `Export stays visible but disabled until the CDJ export accepts songs`() {
        val menu = LibraryMenuModel.of(MenuTarget.AlbumItem(album), MenuPlace(PinContext.ALBUMS), all.copy(canExport = false))
        assertThat(menu.top).contains(EXPORT_REKORDBOX)
        assertThat(menu.disabled).containsExactly(EXPORT_REKORDBOX)
    }

    @Test
    fun `an album on its artist's page does not go to that artist`() {
        val menu = LibraryMenuModel.of(MenuTarget.AlbumItem(album), MenuPlace(artistId = 20), all)
        assertThat(menu.sections.flatten()).containsNoneOf(GO_TO_ARTIST, PIN)
    }

    @Test
    fun `an artist has no Export and no Go to`() {
        val menu = LibraryMenuModel.of(MenuTarget.ArtistItem(20, "Myke Towers"), MenuPlace(PinContext.ARTISTS), all)
        assertThat(menu.top).containsExactly(FAVOURITE, SHARE).inOrder()
        assertThat(menu.sections).containsExactly(
            listOf(PIN, ADD_TO_PLAYLIST),
            listOf(PLAY, SHUFFLE, PLAY_NEXT, PLAY_LAST),
            listOf(CONVERT),
            listOf(REMOVE),
        ).inOrder()
    }

    @Test
    fun `a playlist exports, and an empty one cannot play, share or export`() {
        val empty = MenuTarget.PlaylistItem(ResolvedPlaylist(Playlist(3, "Mix"), emptyList()))
        val menu = LibraryMenuModel.of(empty, MenuPlace(PinContext.PLAYLISTS), all)
        assertThat(menu.top).containsExactly(FAVOURITE, SHARE, EXPORT_REKORDBOX).inOrder()
        assertThat(menu.sections).contains(listOf(CONVERT, COMPRESS, SEND_TO_WATCH))
        assertThat(menu.disabled)
            .containsExactly(SHARE, EXPORT_REKORDBOX, PLAY, SHUFFLE, PLAY_NEXT, PLAY_LAST, ADD_TO_PLAYLIST, CONVERT, COMPRESS, SEND_TO_WATCH)

        val full = MenuTarget.PlaylistItem(ResolvedPlaylist(Playlist(3, "Mix"), listOf(PlaylistItem(PlaylistEntry(1, "u", "t", "a"), track))))
        assertThat(LibraryMenuModel.of(full, MenuPlace(PinContext.PLAYLISTS), all).disabled).isEmpty()
    }

    @Test
    fun `a folder plays, pins and converts but is neither favourited nor removed`() {
        val folder = MenuTarget.FolderItem(FolderEntry(FolderRef("tree", "Music/Rock"), trackCount = 4))
        val menu = LibraryMenuModel.of(folder, MenuPlace(PinContext.FOLDERS), all)
        assertThat(menu.top).isEmpty()
        assertThat(menu.sections).containsExactly(
            listOf(PIN, ADD_TO_PLAYLIST),
            listOf(PLAY, SHUFFLE, PLAY_NEXT, PLAY_LAST),
            listOf(CONVERT),
        ).inOrder()
    }

    @Test
    fun `without the watch link nothing is sent to the watch, and the rest of the section stays`() {
        val noWatch = all.copy(canSendToWatch = false)
        val playlist = MenuTarget.PlaylistItem(ResolvedPlaylist(Playlist(3, "Mix"), listOf(PlaylistItem(PlaylistEntry(1, "u", "t", "a"), track))))
        val albumMenu = LibraryMenuModel.of(MenuTarget.AlbumItem(album), MenuPlace(PinContext.ALBUMS), noWatch)
        val playlistMenu = LibraryMenuModel.of(playlist, MenuPlace(PinContext.PLAYLISTS), noWatch)
        assertThat(albumMenu.sections).contains(listOf(CONVERT, COMPRESS))
        assertThat(playlistMenu.sections).contains(listOf(CONVERT, COMPRESS))
        assertThat(albumMenu.sections.flatten()).doesNotContain(SEND_TO_WATCH)
        assertThat(playlistMenu.sections.flatten()).doesNotContain(SEND_TO_WATCH)
    }

    @Test
    fun `artists and folders are not compressed`() {
        val artist = LibraryMenuModel.of(MenuTarget.ArtistItem(20, "Myke Towers"), MenuPlace(PinContext.ARTISTS), all)
        val folder = LibraryMenuModel.of(MenuTarget.FolderItem(FolderEntry(FolderRef("tree", "Rock"), 4)), MenuPlace.Unpinnable, all)
        assertThat(artist.sections.flatten()).doesNotContain(COMPRESS)
        assertThat(folder.sections.flatten()).doesNotContain(COMPRESS)
    }

    @Test
    fun `only albums and playlists can be sent to the watch, and an empty album cannot`() {
        val playlist = MenuTarget.PlaylistItem(ResolvedPlaylist(Playlist(3, "Mix"), listOf(PlaylistItem(PlaylistEntry(1, "u", "t", "a"), track))))
        assertThat(LibraryMenuModel.of(MenuTarget.AlbumItem(album), MenuPlace(PinContext.ALBUMS), all).sections.flatten()).contains(SEND_TO_WATCH)
        assertThat(LibraryMenuModel.of(playlist, MenuPlace(PinContext.PLAYLISTS), all).sections.flatten()).contains(SEND_TO_WATCH)

        val others = listOf(
            song,
            MenuTarget.ArtistItem(20, "Myke Towers"),
            MenuTarget.FolderItem(FolderEntry(FolderRef("tree", "Rock"), 4)),
        )
        for (target in others) {
            val menu = LibraryMenuModel.of(target, MenuPlace.Unpinnable, all)
            assertThat(menu.top + menu.sections.flatten()).doesNotContain(SEND_TO_WATCH)
        }

        val emptyAlbum = LibraryMenuModel.of(MenuTarget.AlbumItem(album.copy(trackCount = 0)), MenuPlace.Unpinnable, all)
        assertThat(emptyAlbum.disabled).contains(SEND_TO_WATCH)
        assertThat(LibraryMenuModel.of(MenuTarget.AlbumItem(album), MenuPlace.Unpinnable, all).disabled).isEmpty()
    }
}
