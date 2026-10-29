package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.PlaylistItem
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.feature.library.CompressTarget
import io.sonora.feature.library.PlaylistSource
import org.junit.Test

class CompressModelTest {

    private val track = Track(
        id = 1, uri = "content://song", title = "Bandido", artist = "Myke Towers", album = "Bandido", albumArtist = null,
        durationMs = 1, trackNumber = 1, discNumber = 1, year = 2020, albumId = 10, artistId = 20, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )

    @Test
    fun `a song is packed alone, named after its artist and title`() {
        assertThat(MenuTarget.Song(track).compressTarget())
            .isEqualTo(CompressTarget(ArchiveKind.SONG, "Myke Towers - Bandido", PlaylistSource.Tracks(listOf(track))))
    }

    @Test
    fun `a song without an artist is named after its title`() {
        assertThat(MenuTarget.Song(track.copy(artist = " ")).compressTarget()?.name).isEqualTo("Bandido")
    }

    @Test
    fun `an album is packed in album order under its title`() {
        val album = Album(10, "Bandido", "Myke Towers", 2020, 1, null, artistId = 20)
        assertThat(MenuTarget.AlbumItem(album).compressTarget())
            .isEqualTo(CompressTarget(ArchiveKind.ALBUM, "Bandido", PlaylistSource.Album(10)))
    }

    @Test
    fun `a playlist is packed with its playable songs in list order`() {
        val playlist = ResolvedPlaylist(Playlist(3, "Road Trip"), listOf(PlaylistItem(PlaylistEntry(1, "u", "t", "a"), track)))
        assertThat(MenuTarget.PlaylistItem(playlist).compressTarget())
            .isEqualTo(CompressTarget(ArchiveKind.PLAYLIST, "Road Trip", PlaylistSource.Tracks(listOf(track))))
    }

    @Test
    fun `artists and folders have nothing to compress`() {
        assertThat(MenuTarget.ArtistItem(20, "Myke Towers").compressTarget()).isNull()
        assertThat(MenuTarget.FolderItem(FolderEntry(FolderRef("tree", "Rock"), 4)).compressTarget()).isNull()
    }

    @Test
    fun `the password hint follows the strength rule`() {
        assertThat(passwordHint("")).isEqualTo(PasswordHint.NONE)
        assertThat(passwordHint("short1A")).isEqualTo(PasswordHint.TOO_SHORT)
        assertThat(passwordHint("onlylowercaseletters")).isEqualTo(PasswordHint.TOO_FEW_KINDS)
        assertThat(passwordHint("Correct-horse-battery")).isEqualTo(PasswordHint.STRONG)
    }
}
