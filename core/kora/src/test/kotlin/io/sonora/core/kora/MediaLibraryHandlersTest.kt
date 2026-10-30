package io.sonora.core.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaylistTracksRequest
import eus.bihurri.kora.contract.media.SearchRequest
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Goes through a real [KoraNode], so a handler missing from the registration fails here, not at app start. */
class MediaLibraryHandlersTest {
    private val songs = listOf(
        track(1, title = "Beta", artist = "The Band"),
        track(2, title = "Alpha", artist = "The Band"),
        track(3, title = "Gamma", artist = "Solo"),
    )
    private val library = FakeLibrary(songs)
    private val playlists = FakePlaylists(
        listOf(
            playlist(10, "Favourites", songs[0], songs[1]),
            playlist(11, "Gone", songs[2].copy(uri = "content://media/audio/missing"), songs[2]),
        ),
    )
    private val node = KoraNode("test.sonora").apply {
        val handlers = MediaLibraryHandlers({ library }, { playlists })
        provide(MediaLibrary) { handlers.register(this) }
    }

    @Test
    fun `playlists are listed in order with their playable counts`() = runBlocking<Unit> {
        val page = node.call(MediaLibrary.ListPlaylists, PageRequest())

        assertThat(page.total).isEqualTo(2)
        assertThat(page.items.map { it.id }).containsExactly("10", "11").inOrder()
        assertThat(page.items.map { it.trackCount }).containsExactly(2, 1).inOrder()
    }

    @Test
    fun `playlists are paged`() = runBlocking<Unit> {
        val page = node.call(MediaLibrary.ListPlaylists, PageRequest(limit = 1, offset = 1))

        assertThat(page.items.map { it.name }).containsExactly("Gone")
        assertThat(page.total).isEqualTo(2)
        assertThat(page.offset).isEqualTo(1)
    }

    @Test
    fun `a playlist lists its tracks in playlist order`() = runBlocking<Unit> {
        val page = node.call(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest("10"))

        assertThat(page.items.map { it.title }).containsExactly("Beta", "Alpha").inOrder()
        assertThat(page.total).isEqualTo(2)
    }

    @Test
    fun `tracks whose file left the library are not listed`() = runBlocking<Unit> {
        val page = node.call(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest("11"))

        assertThat(page.items.map { it.id }).containsExactly("3")
        assertThat(page.total).isEqualTo(1)
    }

    @Test
    fun `playlist tracks are paged`() = runBlocking<Unit> {
        val page = node.call(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest("10", PageRequest(limit = 1, offset = 1)))

        assertThat(page.items.map { it.title }).containsExactly("Alpha")
        assertThat(page.total).isEqualTo(2)
    }

    @Test
    fun `an unknown playlist is an invalid payload`() = runBlocking<Unit> {
        val failure = koraFailure { node.call(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest("99")) }

        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
    }

    @Test
    fun `a 64-bit playlist id is found`() = runBlocking<Unit> {
        playlists.all = playlists.all + playlist(9_000_000_000_000_000_001, "Big", songs[2])

        val page = node.call(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest("9000000000000000001"))

        assertThat(page.items.map { it.id }).containsExactly("3")
    }

    @Test
    fun `a playlist id that is not a number is an invalid payload`() = runBlocking<Unit> {
        val failure = koraFailure { node.call(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest("../x")) }

        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
    }

    @Test
    fun `search matches title, artist and album and sorts by title`() = runBlocking<Unit> {
        val page = node.call(MediaLibrary.SearchTracks, SearchRequest("band"))

        assertThat(page.items.map { it.title }).containsExactly("Alpha", "Beta").inOrder()
        assertThat(page.total).isEqualTo(2)
    }

    @Test
    fun `search results are paged`() = runBlocking<Unit> {
        val page = node.call(MediaLibrary.SearchTracks, SearchRequest("band", PageRequest(limit = 1, offset = 1)))

        assertThat(page.items.map { it.title }).containsExactly("Beta")
        assertThat(page.total).isEqualTo(2)
    }

    @Test
    fun `a blank search matches nothing`() = runBlocking<Unit> {
        assertThat(node.call(MediaLibrary.SearchTracks, SearchRequest("  ")).items).isEmpty()
    }

    @Test
    fun `an over long search text is an invalid payload`() = runBlocking<Unit> {
        val failure = koraFailure { node.call(MediaLibrary.SearchTracks, SearchRequest("a".repeat(201))) }

        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
    }

    @Test
    fun `the library is not touched until a call needs it`() {
        var built = 0
        val lazy = MediaLibraryHandlers({ built++; library }, { built++; playlists })
        KoraNode("test.lazy").provide(MediaLibrary) { lazy.register(this) }

        assertThat(built).isEqualTo(0)
    }
}
