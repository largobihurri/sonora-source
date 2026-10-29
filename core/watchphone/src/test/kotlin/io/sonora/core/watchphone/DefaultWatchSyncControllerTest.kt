package io.sonora.core.watchphone

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.watchport.WatchBusyException
import io.sonora.core.watchport.WatchSyncFailure
import io.sonora.core.watchport.WatchSyncState
import io.sonora.core.watchsync.SyncConnector
import io.sonora.core.watchsync.WatchNotFoundException
import io.sonora.core.watchsync.sender.SendCandidate
import io.sonora.core.watchsync.sender.SenderConfig
import io.sonora.core.watchsync.sender.TrackEncoder
import io.sonora.core.watchsync.sender.WatchSender
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

class DefaultWatchSyncControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val foregroundStarts = AtomicInteger()

    @After
    fun tearDown() = scope.cancel()

    private fun track(id: Long, albumId: Long = 1) = Track(
        id = id, uri = "content://media/$id", title = "Song $id", artist = "A", album = "B", albumArtist = null,
        durationMs = 1000, trackNumber = id.toInt(), discNumber = null, year = null, albumId = albumId,
        artworkUri = null, mimeType = "audio/flac", sizeBytes = 10,
    )

    private class FakeLibrary(private val tracks: List<Track>) : LibraryRepository {
        override fun tracks(sort: TrackSort): Flow<List<Track>> = flowOf(tracks)
        override fun albums(): Flow<List<Album>> = flowOf(emptyList())
        override fun artists(): Flow<List<Artist>> = flowOf(emptyList())
        override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> = flowOf(emptyList())
        override fun tracksForAlbum(albumId: Long): Flow<List<Track>> = flowOf(tracks.filter { it.albumId == albumId })
        override fun albumsForArtist(artistId: Long): Flow<List<Album>> = flowOf(emptyList())
        override fun search(query: String): Flow<List<Track>> = flowOf(emptyList())
    }

    private class FakePlaylists(private val playlist: PlaylistWithEntries?) : PlaylistRepository {
        override fun playlists(): Flow<List<PlaylistWithEntries>> = flowOf(listOfNotNull(playlist))
        override fun playlist(id: Long): Flow<PlaylistWithEntries?> = flowOf(playlist?.takeIf { it.playlist.id == id })
        override suspend fun create(name: String, description: String, entries: List<PlaylistEntry>): Long = 0
        override suspend fun updateDetails(id: Long, name: String, description: String) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun editEntries(id: Long, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>) = Unit
    }

    private val noEncoder = object : TrackEncoder {
        override suspend fun encode(candidate: SendCandidate, onProgress: (Float) -> Unit): File = error("not reached")

        override fun release(candidate: SendCandidate) = Unit
    }

    private fun controller(
        connector: SyncConnector,
        tracks: List<Track> = listOf(track(1), track(2)),
        playlist: PlaylistWithEntries? = null,
    ) = DefaultWatchSyncController(
        library = FakeLibrary(tracks),
        playlists = FakePlaylists(playlist),
        sender = WatchSender(connector, noEncoder, SenderConfig(maxAttempts = 1)),
        foreground = { foregroundStarts.incrementAndGet() },
        scope = scope,
    )

    private suspend fun DefaultWatchSyncController.settled(): WatchSyncState =
        withTimeout(10_000) { state.first { it !is WatchSyncState.Running } }

    @Test
    fun `a send starts the foreground job and ends failed when there is no watch`() = runBlocking<Unit> {
        val controller = controller(SyncConnector { throw WatchNotFoundException() })

        assertThat(controller.sendAlbum(1)).isTrue()

        assertThat(controller.settled()).isEqualTo(WatchSyncState.Failed(WatchSyncFailure.NoWatch))
        assertThat(foregroundStarts.get()).isEqualTo(1)
    }

    @Test
    fun `an album with no songs ends with nothing to send and never connects`() = runBlocking<Unit> {
        val connections = AtomicInteger()
        val controller = controller(SyncConnector { connections.incrementAndGet(); throw WatchNotFoundException() }, tracks = emptyList())

        controller.sendAlbum(99)

        assertThat(controller.settled()).isEqualTo(WatchSyncState.Failed(WatchSyncFailure.NothingToSend))
        assertThat(connections.get()).isEqualTo(0)
    }

    @Test
    fun `a playlist of songs that are all gone has nothing to send`() = runBlocking<Unit> {
        val gone = PlaylistWithEntries(Playlist(5, "Run"), listOf(PlaylistEntry(1, "content://media/404", "x", "y")))
        val controller = controller(SyncConnector { throw WatchNotFoundException() }, playlist = gone)

        controller.sendPlaylist(5)

        assertThat(controller.settled()).isEqualTo(WatchSyncState.Failed(WatchSyncFailure.NothingToSend))
    }

    @Test
    fun `a second send while one runs is ignored`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val controller = controller(SyncConnector { gate.await(); throw WatchNotFoundException() })

        assertThat(controller.sendAlbum(1)).isTrue()
        assertThat(controller.sendAlbum(1)).isFalse()
        assertThat(controller.state.value).isInstanceOf(WatchSyncState.Running::class.java)
        gate.complete(Unit)

        controller.settled()
        assertThat(foregroundStarts.get()).isEqualTo(1)
    }

    @Test
    fun `cancelling returns to idle`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val controller = controller(SyncConnector { gate.await(); throw WatchNotFoundException() })
        controller.sendAlbum(1)

        controller.cancel()

        assertThat(controller.state.value).isEqualTo(WatchSyncState.Idle)
        // The cancelled send's wind-down must not disturb the next one.
        assertThat(controller.sendAlbum(1)).isTrue()
        assertThat(controller.state.value).isInstanceOf(WatchSyncState.Running::class.java)
        Thread.sleep(200)
        assertThat(controller.state.value).isInstanceOf(WatchSyncState.Running::class.java)
    }

    @Test
    fun `dismissing a result returns to idle and does not touch a running send`() = runBlocking<Unit> {
        val controller = controller(SyncConnector { throw WatchNotFoundException() })
        controller.sendAlbum(1)
        controller.settled()

        controller.dismissResult()

        assertThat(controller.state.value).isEqualTo(WatchSyncState.Idle)
    }

    @Test
    fun `reading the watch while a send runs is refused`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val controller = controller(SyncConnector { gate.await(); throw WatchNotFoundException() })
        controller.sendAlbum(1)

        val result = controller.readWatch()

        assertThat(result.exceptionOrNull()).isInstanceOf(WatchBusyException::class.java)
        gate.complete(Unit)
    }

    @Test
    fun `reading the watch reports why it failed`() = runBlocking<Unit> {
        val controller = controller(SyncConnector { throw WatchNotFoundException() })

        val result = controller.readWatch()

        assertThat(result.exceptionOrNull()).isInstanceOf(WatchNotFoundException::class.java)
    }
}
