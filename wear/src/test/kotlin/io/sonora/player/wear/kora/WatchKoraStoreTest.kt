package io.sonora.player.wear.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlayPlaylistRequest
import eus.bihurri.kora.contract.media.PlaylistTracksRequest
import eus.bihurri.kora.contract.media.SearchRequest
import eus.bihurri.kora.core.KoraNode
import io.sonora.player.wear.library.FilesTrackRepository
import io.sonora.player.wear.library.RawTags
import io.sonora.player.wear.library.loaded
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The node over the watch's real song store: a music folder scanned by [FilesTrackRepository] and read the way the app wires
 * it (`loaded()`), so what the screens list is what a peer sees, and a finished transfer shows up without a restart.
 */
class WatchKoraStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tags = mutableMapOf<String, RawTags>()

    private val repository = FilesTrackRepository(
        directory = { folder.root },
        readTags = { file: File -> tags[file.name] },
        dispatcher = Dispatchers.Unconfined,
    )
    private val player = FakePlayerPort()
    private val node = KoraNode("test.watch").apply {
        provide(MediaLibrary) { WatchLibraryHandlers({ repository.loaded() }, { "Watch music" }).register(this) }
        provide(MediaPlayback) { WatchPlaybackHandlers(player, { repository.loaded() }, { 1L }, Random(1)).register(this) }
    }

    private fun addSong(name: String, title: String? = null, artist: String? = null) {
        folder.newFile(name)
        if (title != null || artist != null) tags[name] = RawTags(title, artist, null, null, null, "120000")
    }

    private suspend fun playlistIds() = node.call(MediaLibrary.GetPlaylistTracks, PlaylistTracksRequest(ALL_SONGS_ID, PageRequest(limit = 50)))
        .items.map { it.id }

    @Test
    fun `the playlist lists the playable files in the screens order, never a transfer in progress`() = runBlocking<Unit> {
        addSong("b.opus", title = "Beta", artist = "Zed")
        addSong("a.opus", title = "Alpha", artist = "Ann")
        addSong("c.opus.part")
        addSong("cover.jpg")

        assertThat(playlistIds()).containsExactly(koraTrackId("a.opus"), koraTrackId("b.opus")).inOrder()
        val playlists = node.call(MediaLibrary.ListPlaylists, PageRequest(limit = 10))
        assertThat(playlists.items.single().trackCount).isEqualTo(2)
    }

    @Test
    fun `a song with no tags is still listed, named after its file, and found by that name`() = runBlocking<Unit> {
        addSong("03 - Untagged Song.opus")

        val found = node.call(MediaLibrary.SearchTracks, SearchRequest("untagged", PageRequest(limit = 10)))

        assertThat(found.items.map { it.title }).containsExactly("Untagged Song")
        assertThat(found.items.single().durationMs).isEqualTo(0)
    }

    @Test
    fun `a song that arrives after the first call is listed once the repository refreshes`() = runBlocking<Unit> {
        addSong("a.opus", title = "Alpha", artist = "Ann")
        assertThat(playlistIds()).hasSize(1)

        addSong("b.opus", title = "Beta", artist = "Ann")
        repository.refresh() // what a finished transfer does (LibraryRefresher)

        assertThat(playlistIds()).containsExactly(koraTrackId("a.opus"), koraTrackId("b.opus")).inOrder()
    }

    @Test
    fun `playing the playlist queues the songs of the store in the list order`() = runBlocking<Unit> {
        addSong("b.opus", title = "Beta", artist = "Zed")
        addSong("a.opus", title = "Alpha", artist = "Ann")

        node.call(MediaPlayback.PlayPlaylist, PlayPlaylistRequest(ALL_SONGS_ID, startIndex = 1, shuffle = false))

        assertThat(player.calls).containsExactly("playQueue [a.opus, b.opus] start=1")
        assertThat(node.call(MediaPlayback.GetState, NoInput).status).isNotNull()
    }
}
