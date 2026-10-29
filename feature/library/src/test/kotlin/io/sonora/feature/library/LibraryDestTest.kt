package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.smart.SmartPlaylist
import io.sonora.core.domain.model.FolderRef
import org.junit.Test

class LibraryDestTest {

    @Test
    fun `every destination survives an encode and decode round trip`() {
        val stack = listOf(
            LibraryDest.Home,
            LibraryDest.Songs,
            LibraryDest.Albums,
            LibraryDest.Artists,
            LibraryDest.Settings,
            LibraryDest.AlbumDetail(albumId = 42L),
            LibraryDest.ArtistDetail(artistId = 7L, name = "AC/DC: Live"),
            LibraryDest.Folders,
            LibraryDest.Folder(FolderRef("content://com.android.externalstorage.documents/tree/primary%3AMusic", "Music")),
            LibraryDest.Folder(FolderRef("content://tree/1234-ABCD%3A", "SD card/Rock: 100% Live/día+1")),
            LibraryDest.Converted,
            LibraryDest.Playlists,
            LibraryDest.PlaylistDetail(playlistId = 12L),
            LibraryDest.Favourites,
            LibraryDest.Smart(SmartPlaylist.RECENTLY_ADDED),
            LibraryDest.Smart(SmartPlaylist.MOST_PLAYED),
            LibraryDest.Smart(SmartPlaylist.NEVER_PLAYED),
            LibraryDest.Statistics,
        )
        assertThat(decodeLibraryStack(encodeLibraryStack(stack))).isEqualTo(stack)
    }

    @Test
    fun `unknown or malformed entries are dropped`() {
        val restored = decodeLibraryStack(listOf("home", "album:abc", "bogus", "artist:3", "playlist:x", "smart:BOGUS", "smart:", "songs"))
        assertThat(restored).containsExactly(LibraryDest.Home, LibraryDest.Songs).inOrder()
    }

    @Test
    fun `a restored stack always starts at home`() {
        assertThat(decodeLibraryStack(emptyList())).containsExactly(LibraryDest.Home)
        assertThat(decodeLibraryStack(listOf("albums", "album:5")))
            .containsExactly(LibraryDest.Home, LibraryDest.Albums, LibraryDest.AlbumDetail(5L)).inOrder()
    }

    @Test
    fun `a folder entry with a missing or unreadable part is dropped`() {
        val restored = decodeLibraryStack(listOf("folders", "folder:", "folder:content%3A%2F%2Ftree", "folder:%zz:Music"))

        assertThat(restored).containsExactly(LibraryDest.Home, LibraryDest.Folders).inOrder()
    }

    @Test
    fun `closing a deleted playlist removes only its own page, wherever it is in the stack`() {
        val playlist = LibraryDest.PlaylistDetail(7L)
        val stack = mutableListOf(LibraryDest.Home, LibraryDest.Playlists, playlist, LibraryDest.AlbumDetail(5L))

        closeDestination(stack, playlist)

        assertThat(stack).containsExactly(LibraryDest.Home, LibraryDest.Playlists, LibraryDest.AlbumDetail(5L)).inOrder()
    }

    @Test
    fun `closing a page that is no longer in the stack changes nothing`() {
        val stack = mutableListOf(LibraryDest.Home, LibraryDest.Playlists)

        closeDestination(stack, LibraryDest.PlaylistDetail(7L))
        closeDestination(stack, LibraryDest.Home)

        assertThat(stack).containsExactly(LibraryDest.Home, LibraryDest.Playlists).inOrder()
    }

    @Test
    fun `the Songs, Albums, Artists and Playlists lists and the album, artist and playlist pages turn into a Cover Flow`() {
        val coverFlow = listOf(
            LibraryDest.Songs,
            LibraryDest.Albums,
            LibraryDest.Artists,
            LibraryDest.Playlists,
            LibraryDest.AlbumDetail(1L),
            LibraryDest.ArtistDetail(2L, "A"),
            LibraryDest.PlaylistDetail(3L),
        )
        val upright = listOf(
            LibraryDest.Home,
            LibraryDest.Settings,
            LibraryDest.Folders,
            LibraryDest.Folder(FolderRef("content://tree/music", "Music")),
            LibraryDest.Converted,
            LibraryDest.Favourites,
            LibraryDest.Smart(SmartPlaylist.RECENTLY_ADDED),
            LibraryDest.Smart(SmartPlaylist.MOST_PLAYED),
            LibraryDest.Smart(SmartPlaylist.NEVER_PLAYED),
            LibraryDest.Statistics,
        )

        assertThat(coverFlow.all { it.hasCoverFlow }).isTrue()
        assertThat(upright.none { it.hasCoverFlow }).isTrue()
        assertThat(upright.map { it.coverFlow }.distinct()).containsExactly(null)
    }

    @Test
    fun `a list shows its own Cover Flow from its first cover`() {
        listOf(LibraryDest.Songs, LibraryDest.Albums, LibraryDest.Artists, LibraryDest.Playlists).forEach { list ->
            assertThat(list.coverFlow).isEqualTo(LibraryCoverFlowView(list, target = null))
        }
    }

    @Test
    fun `a detail page shows the Cover Flow of its kind's list, centred on itself`() {
        assertThat(LibraryDest.AlbumDetail(5L).coverFlow)
            .isEqualTo(LibraryCoverFlowView(LibraryDest.Albums, CoverFlowTarget.Album(5L)))
        assertThat(LibraryDest.ArtistDetail(7L, "Nina").coverFlow)
            .isEqualTo(LibraryCoverFlowView(LibraryDest.Artists, CoverFlowTarget.Artist(7L, "Nina")))
        assertThat(LibraryDest.PlaylistDetail(12L).coverFlow)
            .isEqualTo(LibraryCoverFlowView(LibraryDest.Playlists, CoverFlowTarget.Playlist(12L)))
    }

    @Test
    fun `a linked album or artist is pushed on top of the current page`() {
        val stack = mutableListOf(LibraryDest.Home, LibraryDest.Songs)

        openLink(stack, LibraryLink.Album(5L))
        openLink(stack, LibraryLink.Artist(7L, "Band"))

        assertThat(stack).containsExactly(
            LibraryDest.Home, LibraryDest.Songs, LibraryDest.AlbumDetail(5L), LibraryDest.ArtistDetail(7L, "Band"),
        ).inOrder()
    }

    @Test
    fun `linked Settings is pushed on the current page, so back returns to it`() {
        val stack = mutableListOf(LibraryDest.Home, LibraryDest.AlbumDetail(5L))

        openLink(stack, LibraryLink.Settings)

        assertThat(stack).containsExactly(LibraryDest.Home, LibraryDest.AlbumDetail(5L), LibraryDest.Settings).inOrder()
    }

    @Test
    fun `linked Settings already on top stays the one page`() {
        val stack = mutableListOf(LibraryDest.Home, LibraryDest.Settings)

        openLink(stack, LibraryLink.Settings)

        assertThat(stack).containsExactly(LibraryDest.Home, LibraryDest.Settings).inOrder()
    }

    @Test
    fun `linked Settings under other pages is brought back by closing the pages above it`() {
        val stack = mutableListOf(LibraryDest.Home, LibraryDest.Settings, LibraryDest.AlbumDetail(5L))

        openLink(stack, LibraryLink.Settings)

        assertThat(stack).containsExactly(LibraryDest.Home, LibraryDest.Settings).inOrder()
    }

    @Test
    fun `a linked page replaces whatever the Library shows with that page on top of Home`() {
        val stack = mutableListOf(LibraryDest.Home, LibraryDest.Albums, LibraryDest.AlbumDetail(5L))

        openLink(stack, LibraryLink.Page(LibraryPage.Songs))

        assertThat(stack).containsExactly(LibraryDest.Home, LibraryDest.Songs).inOrder()
    }

    @Test
    fun `a linked page already open at the top of its list keeps its stack, a detail above it is closed`() {
        val onTheList = mutableListOf(LibraryDest.Home, LibraryDest.Albums)
        openLink(onTheList, LibraryLink.Page(LibraryPage.Albums))
        assertThat(onTheList).containsExactly(LibraryDest.Home, LibraryDest.Albums).inOrder()

        val onADetail = mutableListOf(LibraryDest.Home, LibraryDest.Albums, LibraryDest.AlbumDetail(5L))
        openLink(onADetail, LibraryLink.Page(LibraryPage.Albums))
        assertThat(onADetail).containsExactly(LibraryDest.Home, LibraryDest.Albums).inOrder()
    }

    @Test
    fun `every sidebar page opens its own destination`() {
        val destinations = mapOf(
            LibraryPage.Playlists to LibraryDest.Playlists,
            LibraryPage.Songs to LibraryDest.Songs,
            LibraryPage.Albums to LibraryDest.Albums,
            LibraryPage.Artists to LibraryDest.Artists,
            LibraryPage.Folders to LibraryDest.Folders,
            LibraryPage.Converted to LibraryDest.Converted,
            LibraryPage.Statistics to LibraryDest.Statistics,
        )
        assertThat(destinations.keys + LibraryPage.Settings).containsExactlyElementsIn(LibraryPage.entries)
        destinations.forEach { (page, dest) ->
            val stack = mutableListOf<LibraryDest>(LibraryDest.Home)
            openLink(stack, LibraryLink.Page(page))
            assertThat(stack).containsExactly(LibraryDest.Home, dest).inOrder()
        }
    }

    @Test
    fun `a linked Settings page is the one Settings page, as the Settings link opens it`() {
        val stack = mutableListOf(LibraryDest.Home, LibraryDest.Settings, LibraryDest.AlbumDetail(5L))

        openLink(stack, LibraryLink.Page(LibraryPage.Settings))

        assertThat(stack).containsExactly(LibraryDest.Home, LibraryDest.Settings).inOrder()
    }

    @Test
    fun `the page a destination belongs to is the sidebar row to highlight`() {
        assertThat(LibraryDest.Home.page).isNull()
        assertThat(LibraryDest.Songs.page).isEqualTo(LibraryPage.Songs)
        assertThat(LibraryDest.Albums.page).isEqualTo(LibraryPage.Albums)
        assertThat(LibraryDest.AlbumDetail(5L).page).isEqualTo(LibraryPage.Albums)
        assertThat(LibraryDest.Artists.page).isEqualTo(LibraryPage.Artists)
        assertThat(LibraryDest.ArtistDetail(7L, "Nina").page).isEqualTo(LibraryPage.Artists)
        assertThat(LibraryDest.Playlists.page).isEqualTo(LibraryPage.Playlists)
        assertThat(LibraryDest.PlaylistDetail(12L).page).isEqualTo(LibraryPage.Playlists)
        assertThat(LibraryDest.Favourites.page).isEqualTo(LibraryPage.Playlists)
        assertThat(LibraryDest.Smart(SmartPlaylist.MOST_PLAYED).page).isEqualTo(LibraryPage.Playlists)
        assertThat(LibraryDest.Folders.page).isEqualTo(LibraryPage.Folders)
        assertThat(LibraryDest.Folder(FolderRef("content://tree/1", "Music")).page).isEqualTo(LibraryPage.Folders)
        assertThat(LibraryDest.Converted.page).isEqualTo(LibraryPage.Converted)
        assertThat(LibraryDest.Statistics.page).isEqualTo(LibraryPage.Statistics)
        assertThat(LibraryDest.Settings.page).isEqualTo(LibraryPage.Settings)
    }
}
