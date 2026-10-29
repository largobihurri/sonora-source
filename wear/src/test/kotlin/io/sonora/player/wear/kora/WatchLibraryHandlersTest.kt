package io.sonora.player.wear.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaylistTracksRequest
import eus.bihurri.kora.contract.media.SearchRequest
import eus.bihurri.kora.core.KoraNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** Goes through a real [KoraNode], so a handler missing from the registration fails here, not at app start. */
class WatchLibraryHandlersTest {
    private var songs = listOf(
        song("b.opus", title = "Beta", artist = "The Band"),
        song("a.opus", title = "Alpha", artist = "The Band"),
        song("g.opus", title = "Gamma", artist = "Solo"),
    )
    private var loads = 0
    private val node = KoraNode("test.watch").apply {
        val handlers = WatchLibraryHandlers(songs = { loads++; songs }, allSongsName = { "Watch music" })
        provide(MediaLibrary) { handlers.register(this) }
    }

    private fun failureOf(block: suspend () -> Unit): KoraException = assertThrows(KoraException::class.java) { runBlocking { block() } }

    @Test
    fun `the watch has one playlist, all its songs`() = runBlocking<Unit> {
        val page = node.call(MediaLibrary.ListPlaylists, PageRequest())

        assertThat(page.total).isEqualTo(1)
        val playlist = page.items.single()
        assertThat(playlist.id).isEqualTo(ALL_SONGS_ID)
        assertThat(playlist.name).isEqualTo("Watch music")
        assertThat(playlist.trackCount).isEqualTo(3)
        assertThat(playlist.artworkId).isNull()
    }

    @Test
    fun `the playlist exists while the watch has no songs`() = runBlocking<Unit> {
        songs = emptyList()

        val playlist = node.call(MediaLibrary.ListPlaylists, PageRequest()).items.single()

        assertThat(playlist.trackCount).isEqualTo(0)
    }

    @Test
    fun `a page past the single playlist is empty`() = runBlocking<Unit> {
        val page = node.call(MediaLibrary.ListPlaylists, PageRequest(limit = 5, offset = 1))

        assertThat(page.items).isEmpty()
        assertThat(page.total).isEqualTo(1)
        assertThat(page.offset).isEqualTo(1)
    }

    @Test
    fun `the playlist lists the songs in the list order, paged`() = runBlocking<Unit> {
        val page = node.call(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest(ALL_SONGS_ID, PageRequest(limit = 2, offset = 1)))

        assertThat(page.items.map { it.title }).containsExactly("Alpha", "Gamma").inOrder()
        assertThat(page.total).isEqualTo(3)
        assertThat(page.offset).isEqualTo(1)
        assertThat(page.items.first().id).isEqualTo(koraTrackId("a.opus"))
    }

    @Test
    fun `any other playlist is an invalid payload`() {
        val failure = failureOf { node.call(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest("7")) }

        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
    }

    @Test
    fun `search finds by title, artist or album and sorts by title`() = runBlocking<Unit> {
        val page = node.call(MediaLibrary.SearchTracks, SearchRequest("band"))

        assertThat(page.items.map { it.title }).containsExactly("Alpha", "Beta").inOrder()
        assertThat(page.total).isEqualTo(2)
    }

    @Test
    fun `a search text over 200 characters is an invalid payload`() {
        val failure = failureOf { node.call(MediaLibrary.SearchTracks, SearchRequest("x".repeat(201))) }

        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
    }

    @Test
    fun `registering reads nothing`() {
        assertThat(loads).isEqualTo(0)
    }
}
