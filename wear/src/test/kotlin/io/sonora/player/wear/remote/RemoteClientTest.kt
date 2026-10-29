package io.sonora.player.wear.remote

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.WatchNode
import io.sonora.core.watchsync.remote.QueueDirection
import io.sonora.core.watchsync.remote.RemoteCommand
import io.sonora.core.watchsync.remote.RemoteLimits
import io.sonora.core.watchsync.remote.RemoteQueueItem
import io.sonora.core.watchsync.remote.RemoteState
import io.sonora.core.watchsync.remote.RemoteTrack
import io.sonora.player.wear.playback.QueueEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteClientTest {
    private val link = FakeRemoteLink()
    private val track = RemoteTrack(id = 102, title = "Song", artist = "Band", album = "Record", durationMs = 200_000)
    private val playing = RemoteState(track = track, isPlaying = true, positionMs = 10_000, volume = 5, volumeMax = 15, queueIndex = 2, queueSize = 4)

    /** The client's loops run on the test's background scope, which the test cancels when it ends (they never finish by themselves). */
    private fun TestScope.client() = RemoteClient(link, backgroundScope, now = { testScheduler.currentTime })

    private fun entry(index: Int = 3, id: String = "103") = QueueEntry(
        key = "$id#1", index = index, mediaId = id, title = "T", artist = null, isCurrent = false, canMoveUp = true, canMoveDown = true,
    )

    @Test
    fun `opening the remote subscribes to the phone that declares the capability`() = runTest {
        val client = client()

        client.setMode(RemoteMode.NowPlaying)
        runCurrent()

        assertThat(link.sent).containsExactly("phone" to RemoteCommand.Subscribe(withQueue = false))
        assertThat(link.listeners).isEqualTo(1)
    }

    @Test
    fun `without a phone nothing is sent and the state says so`() = runTest {
        link.phones = emptyList()
        val client = client()

        client.setMode(RemoteMode.NowPlaying)
        runCurrent()

        assertThat(link.sent).isEmpty()
        assertThat(client.state.value).isEqualTo(RemoteUiState.NoPhone)
    }

    @Test
    fun `a failed lookup counts as no phone`() = runTest {
        link.failLookup = true
        val client = client()

        client.setMode(RemoteMode.NowPlaying)
        runCurrent()

        assertThat(client.state.value).isEqualTo(RemoteUiState.NoPhone)
    }

    @Test
    fun `the phone's state is shown, and a state from any other node is not`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()

        link.deliver(playing, from = "stranger")
        assertThat(client.state.value).isEqualTo(RemoteUiState.Connecting)

        link.deliver(playing, from = "phone")
        assertThat(client.state.value).isEqualTo(RemoteUiState.Ready(playing, receivedAt = 0))
    }

    @Test
    fun `garbage from the phone is ignored`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()

        link.listener!!.invoke("phone", "nonsense".toByteArray())

        assertThat(client.state.value).isEqualTo(RemoteUiState.Connecting)
    }

    @Test
    fun `the position moves on with the clock while playing`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        link.deliver(playing)

        advanceTimeBy(3_000)

        assertThat(client.positionMs()).isEqualTo(13_000)
    }

    @Test
    fun `the subscription is renewed so the phone keeps pushing and the picture stays fresh`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        link.deliver(playing)
        link.sent.clear()

        advanceTimeBy(RemoteLimits.RENEW_MS + 1)
        runCurrent()

        assertThat(link.commands).containsExactly(RemoteCommand.Subscribe(withQueue = false))
    }

    @Test
    fun `a phone that stops answering is reported unreachable`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        link.deliver(playing)

        advanceTimeBy(RemoteLimits.STALE_MS + 6_000)
        runCurrent()

        assertThat(client.state.value).isEqualTo(RemoteUiState.NoPhone)
    }

    @Test
    fun `a phone that appears later is found without reopening the screen`() = runTest {
        link.phones = emptyList()
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        link.phones = listOf(WatchNode("phone", "Phone", isNearby = true))

        advanceTimeBy(6_000)
        runCurrent()

        assertThat(link.commands).contains(RemoteCommand.Subscribe(withQueue = false))
        assertThat(client.state.value).isEqualTo(RemoteUiState.Connecting)
    }

    @Test
    fun `opening the queue asks for it, leaving it asks again without`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        client.setMode(RemoteMode.Queue)
        runCurrent()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()

        assertThat(link.commands).containsExactly(
            RemoteCommand.Subscribe(withQueue = false),
            RemoteCommand.Subscribe(withQueue = true),
            RemoteCommand.Subscribe(withQueue = false),
        ).inOrder()
        assertThat(link.listeners).isEqualTo(1)
    }

    @Test
    fun `closing the remote unsubscribes, lets go of the listener and forgets the phone`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        link.deliver(playing)

        client.setMode(RemoteMode.Off)
        runCurrent()

        assertThat(link.commands.last()).isEqualTo(RemoteCommand.Unsubscribe)
        assertThat(link.listeners).isEqualTo(0)
        assertThat(client.state.value).isEqualTo(RemoteUiState.NoPhone)
        link.sent.clear()
        advanceTimeBy(RemoteLimits.RENEW_MS * 3)
        runCurrent()
        assertThat(link.sent).isEmpty()
    }

    @Test
    fun `switching off a remote that never opened sends nothing`() = runTest {
        val client = client()

        client.setMode(RemoteMode.Off)
        runCurrent()

        assertThat(link.sent).isEmpty()
    }

    @Test
    fun `transport commands go to the phone`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        link.sent.clear()

        client.setPlaying(false)
        client.next()
        client.previous()
        runCurrent()

        assertThat(link.commands).containsExactly(RemoteCommand.SetPlaying(false), RemoteCommand.Next, RemoteCommand.Previous).inOrder()
    }

    @Test
    fun `queue commands carry the entry's place and id`() = runTest {
        val client = client()
        client.setMode(RemoteMode.Queue)
        runCurrent()
        link.sent.clear()

        client.jump(entry(index = 3, id = "103"))
        client.remove(entry(index = 4, id = "104"))
        client.move(entry(index = 3, id = "103"), QueueDirection.Up)
        runCurrent()

        assertThat(link.commands).containsExactly(
            RemoteCommand.Jump(3, 103),
            RemoteCommand.Remove(4, 104),
            RemoteCommand.Move(3, 103, QueueDirection.Up),
        ).inOrder()
    }

    @Test
    fun `a row that is not a phone queue entry sends nothing`() = runTest {
        val client = client()
        client.setMode(RemoteMode.Queue)
        runCurrent()
        link.sent.clear()

        client.jump(entry(id = "local-song.opus"))
        runCurrent()

        assertThat(link.sent).isEmpty()
    }

    @Test
    fun `commands with no phone found go nowhere`() = runTest {
        link.phones = emptyList()
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()

        client.next()
        runCurrent()

        assertThat(link.sent).isEmpty()
    }

    @Test
    fun `a phone that cannot be reached does not crash a command`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        link.failSends = true

        client.next()
        runCurrent()

        assertThat(link.sent).hasSize(1) // only the subscribe from before
    }

    @Test
    fun `a quick crown turn goes out as few messages, the first at once`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        link.deliver(playing)
        link.sent.clear()

        client.volumeSteps(1)
        client.volumeSteps(1)
        client.volumeSteps(1)
        runCurrent()
        assertThat(link.commands).containsExactly(RemoteCommand.VolumeSteps(1))

        advanceTimeBy(VOLUME_INTERVAL_MS + 1)
        runCurrent()
        assertThat(link.commands).containsExactly(RemoteCommand.VolumeSteps(1), RemoteCommand.VolumeSteps(2)).inOrder()
    }

    @Test
    fun `the crown shows the phone's volume as it will be`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        link.deliver(playing) // 5 of 15

        assertThat(client.volumeSteps(3)).isEqualTo(53) // 8 of 15
        assertThat(client.volumeSteps(-1)).isEqualTo(46) // 7 of 15
    }

    @Test
    fun `the phone's own volume replaces the estimate`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        link.deliver(playing)
        client.volumeSteps(3)

        link.deliver(playing.copy(volume = 6)) // the phone applied less, e.g. at its cap

        assertThat(client.volumeSteps(1)).isEqualTo(46) // 7 of 15
    }

    @Test
    fun `the crown does nothing while no state has arrived`() = runTest {
        val client = client()
        client.setMode(RemoteMode.NowPlaying)
        runCurrent()
        link.sent.clear()

        assertThat(client.volumeSteps(2)).isEqualTo(0)
        runCurrent()
        assertThat(link.sent).isEmpty()
    }

    @Test
    fun `the queue window arrives with the state`() = runTest {
        val client = client()
        client.setMode(RemoteMode.Queue)
        runCurrent()

        link.deliver(playing.copy(queue = listOf(RemoteQueueItem(102, "Song", "Band"))))

        val ready = client.state.value as RemoteUiState.Ready
        assertThat(ready.state.queue).hasSize(1)
    }

    private companion object {
        const val VOLUME_INTERVAL_MS = 120L
    }
}
