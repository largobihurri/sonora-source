package io.sonora.core.watchphone.remote

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.remote.RemoteCodec
import io.sonora.core.watchsync.remote.RemoteCommand
import io.sonora.core.watchsync.remote.RemoteState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

class RemoteHostTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = AtomicLong(0)
    private val playback = FakePlayback(playbackState(count = 5, current = 1))
    private val volume = FakeVolume()
    private val link = FakeLink(watches = listOf("watch"))
    private val host = RemoteHost(controller = { playback }, volume = volume, link = link, scope = scope, now = clock::get)

    @After
    fun tearDown() = scope.cancel()

    private suspend fun send(nodeId: String, command: RemoteCommand) = host.handle(nodeId, RemoteCodec.encodeCommand(command))

    private fun pushedTo(nodeId: String): List<RemoteState> =
        link.sent.filter { it.first == nodeId }.map { RemoteCodec.decodeState(it.second) }

    private suspend fun awaitPushes(nodeId: String, count: Int) = withTimeout(5_000) {
        while (pushedTo(nodeId).size < count) delay(10)
    }

    @Test
    fun `a watch that subscribes gets the picture of the phone at once`() = runBlocking {
        send("watch", RemoteCommand.Subscribe())

        val state = pushedTo("watch").single()
        assertThat(state.track!!.id).isEqualTo(101)
        assertThat(state.isPlaying).isTrue()
        assertThat(state.volume).isEqualTo(7)
        assertThat(state.queue).isNull()
    }

    @Test
    fun `the queue is included only for a watch that asked for it`() = runBlocking {
        send("watch", RemoteCommand.Subscribe(withQueue = true))

        assertThat(pushedTo("watch").single().queue!!.map { it.id }).containsExactly(101L, 102L, 103L, 104L).inOrder()
    }

    @Test
    fun `a sender that is not one of the user's watches is ignored`() = runBlocking {
        send("stranger", RemoteCommand.Subscribe())
        send("stranger", RemoteCommand.Next)
        send("stranger", RemoteCommand.VolumeSteps(4))

        assertThat(link.sent).isEmpty()
        assertThat(playback.calls).isEmpty()
        assertThat(volume.steps).isEmpty()
    }

    @Test
    fun `when the watch list cannot be read nobody is obeyed`() = runBlocking {
        link.failLookup = true

        send("watch", RemoteCommand.Next)

        assertThat(playback.calls).isEmpty()
    }

    @Test
    fun `bytes that are not a command are dropped`() = runBlocking {
        host.handle("watch", "not json".toByteArray())
        host.handle("watch", ByteArray(0))

        assertThat(link.sent).isEmpty()
        assertThat(playback.calls).isEmpty()
    }

    @Test
    fun `commands from a trusted watch reach the player`() = runBlocking {
        send("watch", RemoteCommand.SetPlaying(false))
        send("watch", RemoteCommand.Jump(index = 3, id = 103))

        assertThat(playback.calls).containsExactly("pause", "skipTo(3)").inOrder()
    }

    @Test
    fun `a volume command is applied and its result is pushed back right away`() = runBlocking {
        send("watch", RemoteCommand.Subscribe())
        send("watch", RemoteCommand.VolumeSteps(2))

        assertThat(volume.steps).containsExactly(2)
        assertThat(pushedTo("watch").last().volume).isEqualTo(9)
    }

    @Test
    fun `a change in the player is pushed to a subscriber, the clock ticking is not`() = runBlocking {
        send("watch", RemoteCommand.Subscribe())

        playback.emit(playbackState(count = 5, current = 1, positionMs = 5_250))
        playback.emit(playbackState(count = 5, current = 1, positionMs = 5_500))
        playback.emit(playbackState(count = 5, current = 2))
        awaitPushes("watch", 2)

        assertThat(pushedTo("watch")).hasSize(2)
        assertThat(pushedTo("watch").last().track!!.id).isEqualTo(102)
    }

    @Test
    fun `a watch that unsubscribed gets no more pushes`() = runBlocking {
        send("watch", RemoteCommand.Subscribe())
        send("watch", RemoteCommand.Unsubscribe)

        playback.emit(playbackState(count = 5, current = 2))
        delay(200)

        assertThat(pushedTo("watch")).hasSize(1)
    }

    @Test
    fun `a lease that was not renewed lapses`() = runBlocking {
        send("watch", RemoteCommand.Subscribe())
        clock.set(61_000)

        playback.emit(playbackState(count = 5, current = 2))
        delay(200)

        assertThat(pushedTo("watch")).hasSize(1)
    }

    @Test
    fun `renewing the subscription sends the picture again`() = runBlocking {
        playback.emit(playbackState(count = 5, current = 1, playing = false)) // paused: the clock alone changes nothing
        send("watch", RemoteCommand.Subscribe())
        clock.set(20_000)
        send("watch", RemoteCommand.Subscribe())

        assertThat(pushedTo("watch")).hasSize(2)
    }

    @Test
    fun `a watch that cannot be reached does not stop the host, which sends again on a later try`() = runBlocking {
        link.failSends = true
        send("watch", RemoteCommand.Subscribe())
        assertThat(link.sent).isEmpty()
        link.failSends = false

        send("watch", RemoteCommand.Subscribe())

        awaitPushes("watch", 1)
        assertThat(pushedTo("watch")).isNotEmpty()
    }
}
