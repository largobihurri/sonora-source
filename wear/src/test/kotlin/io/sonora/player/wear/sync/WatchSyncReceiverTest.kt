package io.sonora.player.wear.sync

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.SyncStreams
import io.sonora.core.watchsync.protocol.FrameWriter
import io.sonora.core.watchsync.protocol.SyncMessage
import io.sonora.player.wear.library.TrackRepository
import io.sonora.player.wear.library.WatchTrack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchSyncReceiverTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class CountingTracks : TrackRepository {
        val refreshes = AtomicInteger()
        override val tracks: StateFlow<List<WatchTrack>?> = MutableStateFlow(null)

        override suspend fun refresh() {
            refreshes.incrementAndGet()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tracks = CountingTracks()
    private lateinit var music: File
    private lateinit var receiver: WatchSyncReceiver

    @Before
    fun setUp() {
        music = folder.newFolder("music")
        receiver = WatchSyncReceiver(music, tracks, scope, channelClient = { error("no Data Layer in a unit test") })
    }

    @After
    fun tearDown() = scope.cancel()

    private fun streamsOf(vararg messages: SyncMessage): SyncStreams {
        val bytes = ByteArrayOutputStream()
        FrameWriter(bytes).also { writer -> messages.forEach(writer::writeControl) }
        return SyncStreams(ByteArrayInputStream(bytes.toByteArray()), ByteArrayOutputStream())
    }

    private fun session(open: suspend () -> SyncStreams) = runBlocking { withTimeout(10_000) { receiver.serve(open).join() } }

    @Test
    fun `a session that changes the music folder refreshes the song list once, at its end`() {
        session { streamsOf(SyncMessage.Hello(), SyncMessage.Remove(listOf("aaaaaaaaaaaaaaaa")), SyncMessage.End) }

        assertThat(tracks.refreshes.get()).isEqualTo(1)
        assertThat(receiver.activeSessions.value).isEqualTo(0)
    }

    @Test
    fun `a session that changes nothing does not refresh`() {
        session { streamsOf(SyncMessage.Hello(), SyncMessage.Query, SyncMessage.End) }

        assertThat(tracks.refreshes.get()).isEqualTo(0)
        assertThat(receiver.activeSessions.value).isEqualTo(0)
    }

    @Test
    fun `a connection that cannot be opened ends the session quietly`() {
        session { throw IOException("channel gone") }

        assertThat(tracks.refreshes.get()).isEqualTo(0)
        assertThat(receiver.activeSessions.value).isEqualTo(0)
    }

    @Test
    fun `a session counts as active while it runs`() {
        val release = CountDownLatch(1)
        val blocked = object : InputStream() {
            override fun read(): Int {
                release.await()
                return -1
            }
        }

        val job = receiver.serve { SyncStreams(blocked, ByteArrayOutputStream()) }
        Thread.sleep(100)
        assertThat(receiver.activeSessions.value).isEqualTo(1)

        release.countDown()
        runBlocking { withTimeout(10_000) { job.join() } }
        assertThat(receiver.activeSessions.value).isEqualTo(0)
    }

    @Test
    fun `a new connection supersedes the one before it and the song list is refreshed`() {
        val closed = CountDownLatch(1)
        val firstFrames = ByteArrayOutputStream()
        FrameWriter(firstFrames).also { it.writeControl(SyncMessage.Hello()); it.writeControl(SyncMessage.Remove(listOf("aaaaaaaaaaaaaaaa"))) }
        val stuckAfterFrames = object : InputStream() {
            private val frames = ByteArrayInputStream(firstFrames.toByteArray())

            override fun read(): Int {
                val next = frames.read()
                if (next >= 0) return next
                closed.await()
                return -1
            }
        }

        val first = receiver.serve { SyncStreams(stuckAfterFrames, ByteArrayOutputStream(), onClose = closed::countDown) }
        Thread.sleep(200)
        assertThat(receiver.activeSessions.value).isEqualTo(1)
        val second = receiver.serve {
            streamsOf(SyncMessage.Hello(), SyncMessage.Remove(listOf("bbbbbbbbbbbbbbbb")), SyncMessage.End)
        }

        // The first one only ends because the second closed it; a timeout here means it was not superseded.
        runBlocking { withTimeout(10_000) { first.join(); second.join() } }
        assertThat(receiver.activeSessions.value).isEqualTo(0)
        assertThat(tracks.refreshes.get()).isAtLeast(1)
    }
}
