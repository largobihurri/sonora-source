package io.sonora.core.watchsync.sender

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.Duplex
import io.sonora.core.watchsync.NotEnoughSpaceException
import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.Sha256
import io.sonora.core.watchsync.SyncConnector
import io.sonora.core.watchsync.SyncLimits
import io.sonora.core.watchsync.SyncStreams
import io.sonora.core.watchsync.protocol.FrameWriter
import io.sonora.core.watchsync.protocol.SyncError
import io.sonora.core.watchsync.protocol.SyncMessage
import io.sonora.core.watchsync.receiver.MusicLibraryStore
import io.sonora.core.watchsync.receiver.SyncReceiver
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The phone's [WatchSender] against the real watch receiver, joined by in-memory connections that can drop or damage bytes. */
class WatchSenderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var free = 10_000_000_000L
    private lateinit var watchDir: File
    private lateinit var store: MusicLibraryStore
    private lateinit var encoder: FakeEncoder
    private val connections = AtomicInteger()
    private var contentsPageBytes = SyncLimits.CONTENTS_PAGE_BYTES

    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val c = "cccccccccccccccc"

    @Before
    fun setUp() {
        watchDir = File(folder.root, "music")
        store = MusicLibraryStore(watchDir, freeSpace = { free }, totalSpace = { 23_000_000_000L })
        encoder = FakeEncoder(folder.newFolder("staging"))
    }

    @After
    fun tearDown() = scope.cancel()

    private fun candidate(id: String, durationMs: Long = 200_000) = SendCandidate(
        id = id,
        sourceUri = "content://media/$id",
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        trackNumber = 1,
        durationMs = durationMs,
    )

    private fun config(trackRetries: Int = 2, maxAttempts: Int = 3, replyTimeoutMs: Long = 20_000) = SenderConfig(
        maxAttempts = maxAttempts,
        retryDelaysMs = listOf(0L),
        replyTimeoutMs = replyTimeoutMs,
        trackRetries = trackRetries,
        chunkBytes = 4096,
    )

    private fun watchConnector(
        cutFirstAfterBytes: Long = Long.MAX_VALUE,
        corruptAt: Long? = null,
        failOpening: Int = 0,
    ) = SyncConnector {
        val n = connections.incrementAndGet()
        if (n <= failOpening) throw IOException("The watch is not reachable")
        val duplex = Duplex(if (n == failOpening + 1) cutFirstAfterBytes else Long.MAX_VALUE)
        val receiver = SyncReceiver(store, progressEveryBytes = 2000, contentsPageBytes = contentsPageBytes)
        scope.launch { runCatching { receiver.serve(duplex.watch) } }
        if (corruptAt == null) {
            duplex.phone
        } else {
            SyncStreams(duplex.phone.input, CorruptingOutput(duplex.phone.output, corruptAt), duplex.phone::close)
        }
    }

    private fun sender(connector: SyncConnector, config: SenderConfig = config()) = WatchSender(connector, encoder, config)

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

    @Test
    fun `two songs go to an empty watch and are verified`() {
        val progress = mutableListOf<SendProgress>()

        val report = run { sender(watchConnector()).send(listOf(candidate(a), candidate(b)), progress::add) }

        assertThat(report.sent.map { it.id }).containsExactly(a, b).inOrder()
        assertThat(report.failed).isEmpty()
        assertThat(report.attempts).isEqualTo(1)
        assertThat(File(watchDir, "$a.opus").readBytes()).isEqualTo(encoder.bytesFor(candidate(a)))
        assertThat(File(watchDir, "$b.opus").readBytes()).isEqualTo(encoder.bytesFor(candidate(b)))
        assertThat(store.contents().map { it.id }).containsExactly(a, b)
        assertThat(store.contents().first { it.id == a }.title).isEqualTo("Song $a")
        assertThat(encoder.released).containsExactly(a, b)
        assertThat(report.sent.sumOf { it.transferredBytes }).isEqualTo(2L * encoder.sizeOf(candidate(a)))
        assertThat(progress.map { it.phase }.toSet()).containsExactly(SendProgress.Phase.ENCODING, SendProgress.Phase.SENDING)
        assertThat(progress.last().overallFraction).isEqualTo(1f)
        assertThat(progress.map { it.overallFraction }).isInOrder()
        assertThat(progress.map { it.trackNumber }.toSet()).containsExactly(1, 2)
        assertThat(progress.all { it.trackCount == 2 }).isTrue()
    }

    @Test
    fun `songs already on the watch are skipped without being encoded`() {
        run { sender(watchConnector()).send(listOf(candidate(a))) }
        encoder.encoded.clear()

        val report = run { sender(watchConnector()).send(listOf(candidate(a), candidate(b))) }

        assertThat(report.sent.map { it.id }).containsExactly(b)
        assertThat(report.skipped.map { it.id }).containsExactly(a)
        assertThat(encoder.encoded).containsExactly(b)
    }

    @Test
    fun `nothing is sent when everything is already there`() {
        run { sender(watchConnector()).send(listOf(candidate(a))) }
        encoder.encoded.clear()

        val report = run { sender(watchConnector()).send(listOf(candidate(a))) }

        assertThat(report.sent).isEmpty()
        assertThat(report.skipped.map { it.id }).containsExactly(a)
        assertThat(encoder.encoded).isEmpty()
    }

    @Test
    fun `a selection that cannot fit is refused before anything is encoded`() {
        free = SyncLimits.MIN_FREE_RESERVE_BYTES + 1000

        val error = assertThrows(NotEnoughSpaceException::class.java) {
            run { sender(watchConnector()).send(listOf(candidate(a))) }
        }

        assertThat(error.availableBytes).isEqualTo(1000)
        assertThat(encoder.encoded).isEmpty()
        assertThat(watchDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a song bigger than its estimate is refused by the watch with no room`() {
        encoder.sizeOf = { 1_000_000 }
        val estimate = OpusSizeEstimate.bytesFor(1_000)
        free = SyncLimits.MIN_FREE_RESERVE_BYTES + estimate + 10

        assertThrows(NotEnoughSpaceException::class.java) {
            run { sender(watchConnector()).send(listOf(candidate(a, durationMs = 1_000))) }
        }

        assertThat(store.contents()).isEmpty()
    }

    @Test
    fun `a dropped connection is retried and resumes where the watch stopped`() {
        encoder.sizeOf = { 40_000 }

        val report = run { sender(watchConnector(cutFirstAfterBytes = 15_000)).send(listOf(candidate(a))) }

        val sent = report.sent.single()
        assertThat(report.attempts).isEqualTo(2)
        assertThat(sent.resumedFrom).isGreaterThan(0L)
        assertThat(sent.transferredBytes).isEqualTo(40_000 - sent.resumedFrom)
        assertThat(File(watchDir, "$a.opus").readBytes()).isEqualTo(encoder.bytesFor(candidate(a)))
        assertThat(encoder.encoded).containsExactly(a) // the finished encoding was kept, not redone
    }

    @Test
    fun `a watch that cannot be opened at first is retried`() {
        val report = run { sender(watchConnector(failOpening = 2)).send(listOf(candidate(a))) }

        assertThat(report.attempts).isEqualTo(3)
        assertThat(report.sent.map { it.id }).containsExactly(a)
    }

    @Test
    fun `when every attempt fails the last error is thrown`() {
        val error = assertThrows(IOException::class.java) {
            run { sender(watchConnector(failOpening = 99), config(maxAttempts = 3)).send(listOf(candidate(a))) }
        }

        assertThat(error).hasMessageThat().contains("not reachable")
        assertThat(connections.get()).isEqualTo(3)
    }

    @Test
    fun `a song damaged in transit is sent again on the same connection`() {
        encoder.sizeOf = { 20_000 }

        val report = run { sender(watchConnector(corruptAt = 5_000)).send(listOf(candidate(a))) }

        assertThat(report.sent.map { it.id }).containsExactly(a)
        assertThat(report.attempts).isEqualTo(1)
        assertThat(connections.get()).isEqualTo(1)
        assertThat(File(watchDir, "$a.opus").readBytes()).isEqualTo(encoder.bytesFor(candidate(a)))
    }

    @Test
    fun `a song that fails its checksum with no retries left is reported and the rest still goes`() {
        encoder.sizeOf = { 20_000 }

        val report = run { sender(watchConnector(corruptAt = 5_000), config(trackRetries = 0)).send(listOf(candidate(a), candidate(b))) }

        assertThat(report.failed.map { it.id }).containsExactly(a)
        assertThat(report.failed.single().reason).isEqualTo(SyncError.CHECKSUM_MISMATCH)
        assertThat(report.sent.map { it.id }).containsExactly(b)
        assertThat(store.contents().map { it.id }).containsExactly(b)
    }

    @Test
    fun `a watch of another protocol version is a protocol error and is not retried`() {
        val connector = SyncConnector {
            connections.incrementAndGet()
            val duplex = Duplex()
            scope.launch { FrameWriter(duplex.watch.output).writeControl(SyncMessage.Hello(version = 99)) }
            duplex.phone
        }

        assertThrows(ProtocolException::class.java) { run { sender(connector).send(listOf(candidate(a))) } }

        assertThat(connections.get()).isEqualTo(1)
    }

    @Test
    fun `reading the watch gives its space and its songs`() {
        run { sender(watchConnector()).send(listOf(candidate(a), candidate(b))) }

        val state = run { sender(watchConnector()).readState() }

        assertThat(state.freeBytes).isEqualTo(free)
        assertThat(state.totalBytes).isEqualTo(23_000_000_000L)
        assertThat(state.usableBytes).isEqualTo(free - SyncLimits.MIN_FREE_RESERVE_BYTES)
        assertThat(state.tracks.map { it.id }).containsExactly(a, b)
        assertThat(state.tracks.first().sha256).isEqualTo(Sha256.hex(encoder.bytesFor(candidate(a))))
    }

    @Test
    fun `a watch library listed in several pages is read whole`() {
        val ids = (1..25).map { "%016x".format(it) }
        run { sender(watchConnector()).send(ids.map { candidate(it) }) }
        contentsPageBytes = 1500

        val state = run { sender(watchConnector()).readState() }

        assertThat(state.tracks.map { it.id }).containsExactlyElementsIn(ids).inOrder()
    }

    @Test
    fun `a song that encodes to an empty file fails as an encoding error and is never offered`() {
        encoder.sizeOf = { if (it.id == a) 0 else 20_000 }
        val progress = mutableListOf<SendProgress>()

        val report = run { sender(watchConnector()).send(listOf(candidate(a), candidate(b)), progress::add) }

        assertThat(report.failed.map { it.id to it.reason }).containsExactly(a to SyncError.ENCODING_FAILED)
        assertThat(report.sent.map { it.id }).containsExactly(b)
        assertThat(store.contents().map { it.id }).containsExactly(b)
        assertThat(encoder.released).contains(a)
        assertThat(progress.none { it.phaseFraction.isNaN() || it.overallFraction.isNaN() }).isTrue()
    }

    @Test
    fun `songs are removed from the watch`() {
        run { sender(watchConnector()).send(listOf(candidate(a), candidate(b))) }

        val removed = run { sender(watchConnector()).remove(listOf(a)) }

        assertThat(removed).containsExactly(a)
        assertThat(store.contents().map { it.id }).containsExactly(b)
        assertThat(File(watchDir, "$a.opus").exists()).isFalse()
    }

    @Test
    fun `a watch that never answers times out and is given up on`() {
        val connector = SyncConnector {
            connections.incrementAndGet()
            Duplex().phone // nobody listens at the other end
        }

        val error = assertThrows(IOException::class.java) {
            run { sender(connector, config(maxAttempts = 2, replyTimeoutMs = 200)).readState() }
        }

        assertThat(error).hasMessageThat().contains("answer")
        assertThat(connections.get()).isEqualTo(2)
    }

    @Test
    fun `cancelling a send closes the connection at once`() = runBlocking<Unit> {
        val duplex = Duplex()
        val connector = SyncConnector { duplex.phone }
        val job = async(Dispatchers.IO) { runCatching { sender(connector).readState() } }
        Thread.sleep(150)

        withTimeout(10_000) { job.cancelAndJoin() }

        assertThrows(IOException::class.java) { duplex.phone.output.write(1) }
    }

    @Test
    fun `a song that cannot be encoded is reported and the others still go`() {
        encoder.failFor = setOf(a)

        val report = run { sender(watchConnector()).send(listOf(candidate(a), candidate(b))) }

        assertThat(report.failed.map { it.id }).containsExactly(a)
        assertThat(report.failed.single().reason).isEqualTo(SyncError.ENCODING_FAILED)
        assertThat(report.sent.map { it.id }).containsExactly(b)
    }

    @Test
    fun `a duplicated song in the selection goes once`() {
        val report = run { sender(watchConnector()).send(listOf(candidate(a), candidate(c), candidate(a))) }

        assertThat(report.sent.map { it.id }).containsExactly(a, c).inOrder()
        assertThat(encoder.encoded).containsExactly(a, c)
    }

    /** Flips one bit of the byte at stream position [at]. */
    private class CorruptingOutput(private val target: OutputStream, private val at: Long) : OutputStream() {
        private var position = 0L

        override fun write(b: Int) = target.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val copy = b.copyOfRange(off, off + len)
            if (at in position until position + len) copy[(at - position).toInt()] = (copy[(at - position).toInt()].toInt() xor 1).toByte()
            position += len
            target.write(copy, 0, len)
        }

        override fun flush() = target.flush()

        override fun close() = target.close()
    }
}
