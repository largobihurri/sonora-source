package io.sonora.feature.settings.watch

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Track
import io.sonora.core.watchport.WatchBusyException
import io.sonora.core.watchport.WatchSyncController
import io.sonora.core.watchport.WatchSyncFailure
import io.sonora.core.watchport.WatchSyncState
import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.WatchNotFoundException
import io.sonora.core.watchsync.sender.SendProgress
import io.sonora.core.watchsync.sender.SendReport
import io.sonora.core.watchsync.sender.WatchState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchViewModelTest {

    private class FakeController : WatchSyncController {
        val sync = MutableStateFlow<WatchSyncState>(WatchSyncState.Idle)
        override val state: StateFlow<WatchSyncState> = sync

        var watch: Result<WatchState> = Result.success(WatchState(0, 0, 0, emptyList()))
        var removal: (List<String>) -> Result<List<String>> = { Result.success(it) }
        /** A read waits for this when set. */
        var readGate: CompletableDeferred<Unit>? = null

        var reads = 0
        var cancels = 0
        var dismissals = 0
        val removed = mutableListOf<List<String>>()

        override fun sendAlbum(albumId: Long) = false
        override fun sendPlaylist(playlistId: Long) = false
        override fun sendTracks(tracks: List<Track>) = false
        override fun cancel() {
            cancels++
            sync.value = WatchSyncState.Idle
        }
        override fun dismissResult() {
            dismissals++
            sync.value = WatchSyncState.Idle
        }
        override suspend fun readWatch(): Result<WatchState> {
            reads++
            if (sync.value is WatchSyncState.Running) return Result.failure(WatchBusyException())
            readGate?.await()
            return watch
        }
        override suspend fun removeFromWatch(trackIds: List<String>): Result<List<String>> {
            if (sync.value is WatchSyncState.Running) return Result.failure(WatchBusyException())
            removed += trackIds
            return removal(trackIds)
        }
    }

    private fun song(id: String, album: String = "Album", size: Long = 1_000) =
        ManifestTrack(id, "Song $id", artist = "Artist", album = album, durationMs = 1_000, sizeBytes = size, sha256 = "0".repeat(64))

    private fun watchWith(vararg songs: ManifestTrack) =
        Result.success(WatchState(freeBytes = 9_000, totalBytes = 23_000, reserveBytes = 1_000, tracks = songs.toList()))

    private val controller = FakeController()

    private fun viewModel() = WatchViewModel(controller)

    private fun running(overall: Float = 0.2f) = WatchSyncState.Running(
        SendProgress(SendProgress.Phase.SENDING, 1, 4, "id", "Halo", 0.5f, overall),
        songCount = 4,
    )

    private fun finished() = WatchSyncState.Finished(SendReport(emptyList(), emptyList(), emptyList(), attempts = 1))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening the page reads the watch and shows its space and songs by album`() = runTest {
        controller.watch = watchWith(song("1", album = "B"), song("2", album = "A"), song("3", album = "A"))
        val state = viewModel().state.value
        assertThat(state.link).isEqualTo(WatchLink.CONNECTED)
        assertThat(state.space?.songCount).isEqualTo(3)
        assertThat(state.space?.freeBytes).isEqualTo(9_000)
        assertThat(state.groups.map { it.album }).containsExactly("A", "B").inOrder()
        assertThat(state.transfer).isEqualTo(WatchTransfer.Idle)
        assertThat(controller.reads).isEqualTo(1)
    }

    @Test
    fun `while the watch is being read the page says it is checking`() = runTest {
        controller.readGate = CompletableDeferred()
        val model = viewModel()
        assertThat(model.state.value.link).isEqualTo(WatchLink.CHECKING)
        controller.readGate!!.complete(Unit)
        assertThat(model.state.value.link).isEqualTo(WatchLink.CONNECTED)
    }

    @Test
    fun `with no watch the page says so and shows no songs`() = runTest {
        controller.watch = Result.failure(WatchNotFoundException())
        val state = viewModel().state.value
        assertThat(state.link).isEqualTo(WatchLink.NO_WATCH)
        assertThat(state.space).isNull()
        assertThat(state.groups).isEmpty()
    }

    @Test
    fun `refreshing after a failure recovers when the watch comes back`() = runTest {
        controller.watch = Result.failure(IOException("closed"))
        val model = viewModel()
        assertThat(model.state.value.link).isEqualTo(WatchLink.UNREACHABLE)
        controller.watch = watchWith(song("1"))
        model.refresh()
        assertThat(model.state.value.link).isEqualTo(WatchLink.CONNECTED)
        assertThat(model.state.value.groups).hasSize(1)
    }

    @Test
    fun `a second refresh while one is reading does not read again`() = runTest {
        controller.readGate = CompletableDeferred()
        val model = viewModel()
        model.refresh()
        model.refresh()
        assertThat(controller.reads).isEqualTo(1)
    }

    @Test
    fun `a send in progress shows its progress, keeps the songs and does not read the watch`() = runTest {
        controller.watch = watchWith(song("1"))
        val model = viewModel()
        controller.sync.value = running(0.2f)
        val state = model.state.value
        assertThat(state.link).isEqualTo(WatchLink.SENDING)
        assertThat((state.transfer as WatchTransfer.Running).overallFraction).isEqualTo(0.2f)
        assertThat(state.groups).hasSize(1)
        assertThat(controller.reads).isEqualTo(1)
        model.refresh()
        assertThat(controller.reads).isEqualTo(1)
    }

    @Test
    fun `when a send ends the watch is read again and the result stays on show`() = runTest {
        controller.watch = watchWith(song("1"))
        val model = viewModel()
        controller.sync.value = running()
        controller.watch = watchWith(song("1"), song("2"))
        controller.sync.value = finished()
        val state = model.state.value
        assertThat(controller.reads).isEqualTo(2)
        assertThat(state.transfer).isInstanceOf(WatchTransfer.Done::class.java)
        assertThat(state.link).isEqualTo(WatchLink.CONNECTED)
        assertThat(state.space?.songCount).isEqualTo(2)
    }

    @Test
    fun `a failed send is shown with its failure and the watch is read again`() = runTest {
        val model = viewModel()
        controller.sync.value = running()
        controller.sync.value = WatchSyncState.Failed(WatchSyncFailure.ConnectionLost)
        assertThat(model.state.value.transfer).isEqualTo(WatchTransfer.Failed(WatchSyncFailure.ConnectionLost))
        assertThat(controller.reads).isEqualTo(2)
    }

    @Test
    fun `cancelling stops the send, returns to idle and reads the watch`() = runTest {
        val model = viewModel()
        controller.sync.value = running()
        model.cancelSend()
        assertThat(controller.cancels).isEqualTo(1)
        assertThat(model.state.value.transfer).isEqualTo(WatchTransfer.Idle)
        assertThat(controller.reads).isEqualTo(2)
    }

    @Test
    fun `dismissing the result asks the controller to clear it`() = runTest {
        val model = viewModel()
        controller.sync.value = finished()
        model.dismissResult()
        assertThat(controller.dismissals).isEqualTo(1)
        assertThat(model.state.value.transfer).isEqualTo(WatchTransfer.Idle)
    }

    @Test
    fun `removing asks first, naming the songs, and cancelling changes nothing`() = runTest {
        controller.watch = watchWith(song("1"), song("2"))
        val model = viewModel()
        model.askRemove(listOf("1", "2"))
        assertThat(model.state.value.pendingRemoval).isEqualTo(PendingRemoval(listOf("1", "2")))
        assertThat(model.state.value.pendingRemoval?.count).isEqualTo(2)
        model.cancelRemove()
        assertThat(model.state.value.pendingRemoval).isNull()
        assertThat(controller.removed).isEmpty()
    }

    @Test
    fun `asking to remove nothing, or songs the watch lacks, asks nothing`() = runTest {
        controller.watch = watchWith(song("1"))
        val model = viewModel()
        model.askRemove(emptyList())
        assertThat(model.state.value.pendingRemoval).isNull()
        model.askRemove(listOf("ghost", "1"))
        assertThat(model.state.value.pendingRemoval).isEqualTo(PendingRemoval(listOf("1")))
    }

    @Test
    fun `confirming removes the songs, reads the watch again and says how many went`() = runTest {
        controller.watch = watchWith(song("1"), song("2"))
        val model = viewModel()
        model.askRemove(listOf("1"))
        controller.watch = watchWith(song("2"))
        model.confirmRemove()
        val state = model.state.value
        assertThat(controller.removed).containsExactly(listOf("1"))
        assertThat(state.pendingRemoval).isNull()
        assertThat(state.removing).isFalse()
        assertThat(state.message).isEqualTo(WatchMessage.Removed(1))
        assertThat(state.groups.single().songIds).containsExactly("2")
    }

    @Test
    fun `a removal the watch only partly confirmed says so`() = runTest {
        controller.watch = watchWith(song("1"), song("2"), song("3"))
        val model = viewModel()
        controller.removal = { Result.success(listOf("1")) }
        model.askRemove(listOf("1", "2", "3"))
        model.confirmRemove()
        assertThat(model.state.value.message).isEqualTo(WatchMessage.PartlyRemoved(removed = 1, requested = 3))
    }

    @Test
    fun `a removal that fails says so and keeps the songs shown`() = runTest {
        controller.watch = watchWith(song("1"))
        val model = viewModel()
        controller.removal = { Result.failure(IOException("closed")) }
        model.askRemove(listOf("1"))
        model.confirmRemove()
        assertThat(model.state.value.message).isEqualTo(WatchMessage.RemoveFailed)
        assertThat(model.state.value.pendingRemoval).isNull()
        assertThat(model.state.value.groups).hasSize(1)
    }

    @Test
    fun `confirming with nothing asked does nothing`() = runTest {
        val model = viewModel()
        model.confirmRemove()
        assertThat(controller.removed).isEmpty()
        assertThat(model.state.value.message).isNull()
    }

    @Test
    fun `the notice goes away when dismissed`() = runTest {
        controller.watch = watchWith(song("1"))
        val model = viewModel()
        model.askRemove(listOf("1"))
        model.confirmRemove()
        model.dismissMessage()
        assertThat(model.state.value.message).isNull()
    }
}
