package io.sonora.core.watchsync.receiver

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.Duplex
import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.Sha256
import io.sonora.core.watchsync.SyncLimits
import io.sonora.core.watchsync.protocol.Frame
import io.sonora.core.watchsync.protocol.FrameReader
import io.sonora.core.watchsync.protocol.FrameWriter
import io.sonora.core.watchsync.protocol.SyncError
import io.sonora.core.watchsync.protocol.SyncMessage
import io.sonora.core.watchsync.protocol.SyncMessageCodec
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Drives a [SyncReceiver] from the phone's end of an in-memory connection. */
class SyncReceiverTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var free = 10_000_000_000L
    private lateinit var dir: File
    private lateinit var store: MusicLibraryStore
    private val changes = AtomicInteger()
    private val id = "0123456789abcdef"

    @Before
    fun setUp() {
        dir = File(folder.root, "music")
        store = MusicLibraryStore(dir, freeSpace = { free }, totalSpace = { 23_000_000_000L })
    }

    private fun receiver(progressEveryBytes: Long = 256 * 1024L, contentsPageBytes: Int = SyncLimits.CONTENTS_PAGE_BYTES) = SyncReceiver(
        store,
        onLibraryChanged = { changes.incrementAndGet() },
        progressEveryBytes = progressEveryBytes,
        contentsPageBytes = contentsPageBytes,
    )

    private fun song(seed: Int, size: Int = 1000) = ByteArray(size) { (it * seed).toByte() }

    private fun manifest(bytes: ByteArray, trackId: String = id) = ManifestTrack(
        id = trackId,
        title = "Song",
        durationMs = 3000,
        sizeBytes = bytes.size.toLong(),
        sha256 = Sha256.hex(bytes),
    )

    /** The phone's side of one connection: [block] talks to the receiver, which runs until the connection ends. */
    private fun session(
        receiver: SyncReceiver = receiver(),
        duplex: Duplex = Duplex(),
        block: (FrameWriter, FrameReader) -> Unit,
    ): Result<Unit> = runBlocking {
        withTimeout(20_000) {
            val serving: Deferred<Result<Unit>> = async(Dispatchers.IO) { runCatching { receiver.serve(duplex.watch) } }
            val writer = FrameWriter(duplex.phone.output)
            val reader = FrameReader(duplex.phone.input)
            try {
                block(writer, reader)
            } finally {
                duplex.phone.close()
            }
            serving.await()
        }
    }

    private fun FrameReader.message(): SyncMessage = (read() as Frame.Control).message

    private fun FrameWriter.send(bytes: ByteArray, from: Int = 0, to: Int = bytes.size, chunk: Int = 300) {
        var at = from
        while (at < to) {
            val count = minOf(chunk, to - at)
            writeData(bytes, at, count)
            at += count
        }
        flush()
    }

    @Test
    fun `hello is answered with hello`() {
        val result = session { w, r ->
            w.writeControl(SyncMessage.Hello())
            assertThat(r.message()).isEqualTo(SyncMessage.Hello(SyncLimits.PROTOCOL_VERSION))
            w.writeControl(SyncMessage.End)
        }

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `a peer of another version is answered and the session ends`() {
        val result = session { w, r ->
            w.writeControl(SyncMessage.Hello(version = 99))
            assertThat(r.message()).isEqualTo(SyncMessage.Hello(SyncLimits.PROTOCOL_VERSION))
            assertThat(r.read()).isNull()
        }

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `a query gets the free space and then the contents`() {
        val bytes = song(7)
        store.beginIncoming(manifest(bytes), 0).use {
            it.write(bytes, 0, bytes.size)
            it.commit()
        }

        session { w, r ->
            w.writeControl(SyncMessage.Query)
            assertThat(r.message()).isEqualTo(SyncMessage.FreeSpace(free, 23_000_000_000L))
            assertThat(r.message()).isEqualTo(SyncMessage.Contents(listOf(manifest(bytes))))
            w.writeControl(SyncMessage.End)
        }
    }

    /** Puts [count] complete songs, each with long tags, straight into the folder (what earlier sessions left). */
    private fun fillLibrary(count: Int, textChars: Int): List<ManifestTrack> {
        dir.mkdirs()
        return (1..count).map { n ->
            val text = "x".repeat(textChars)
            val track = ManifestTrack(
                id = "%016x".format(n),
                title = text,
                artist = text,
                albumArtist = text,
                album = text,
                durationMs = 1000,
                sizeBytes = 1,
                sha256 = "b".repeat(64),
            )
            File(dir, "${track.id}.opus").writeBytes(byteArrayOf(1))
            File(dir, "${track.id}.json").writeText(SyncMessageCodec.json.encodeToString(ManifestTrack.serializer(), track))
            track
        }
    }

    private fun FrameReader.contents(): List<ManifestTrack> {
        val all = ArrayList<ManifestTrack>()
        while (true) {
            val page = message() as SyncMessage.Contents
            all += page.tracks
            if (!page.more) return all
        }
    }

    @Test
    fun `a library too big for one control frame is sent in pages`() {
        // 2 200 songs of about 4 KB of tags: about 8.8 MB of JSON, more than SyncLimits.MAX_CONTROL_FRAME_BYTES.
        val held = fillLibrary(count = 2200, textChars = 1000)

        var received = emptyList<ManifestTrack>()
        val result = session { w, r ->
            w.writeControl(SyncMessage.Query)
            r.message() as SyncMessage.FreeSpace
            received = r.contents()
            w.writeControl(SyncMessage.End)
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(received).containsExactlyElementsIn(held).inOrder()
    }

    @Test
    fun `contents pages follow the budget and the session carries on after them`() {
        val held = fillLibrary(count = 30, textChars = 40)

        val pages = ArrayList<SyncMessage.Contents>()
        val result = session(receiver(contentsPageBytes = 2000)) { w, r ->
            w.writeControl(SyncMessage.Query)
            r.message() as SyncMessage.FreeSpace
            do {
                val page = r.message() as SyncMessage.Contents
                pages += page
            } while (page.more)
            w.writeControl(SyncMessage.Query)
            r.message() as SyncMessage.FreeSpace
            assertThat(r.contents()).hasSize(30)
            w.writeControl(SyncMessage.End)
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(pages.size).isGreaterThan(3)
        assertThat(pages.flatMap { it.tracks }).containsExactlyElementsIn(held).inOrder()
    }

    @Test
    fun `an offered song is requested from zero, received, verified and announced`() {
        val bytes = song(7)

        val result = session { w, r ->
            w.writeControl(SyncMessage.Offer(manifest(bytes)))
            assertThat(r.message()).isEqualTo(SyncMessage.Request(id, 0))
            w.send(bytes)
            assertThat(r.message()).isEqualTo(SyncMessage.Done(id))
            w.writeControl(SyncMessage.End)
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(File(dir, "$id.opus").readBytes()).isEqualTo(bytes)
        assertThat(changes.get()).isEqualTo(1)
    }

    @Test
    fun `progress is reported as the bytes arrive`() {
        val bytes = song(7, size = 3000)
        val progress = mutableListOf<SyncMessage.Progress>()

        session(receiver(progressEveryBytes = 500)) { w, r ->
            w.writeControl(SyncMessage.Offer(manifest(bytes)))
            r.message()
            w.send(bytes)
            while (true) {
                val message = r.message()
                if (message is SyncMessage.Progress) progress += message else break
            }
            w.writeControl(SyncMessage.End)
        }

        assertThat(progress).isNotEmpty()
        assertThat(progress.map { it.receivedBytes }).isInOrder()
        assertThat(progress.all { it.trackId == id && it.receivedBytes in 1..3000 }).isTrue()
    }

    @Test
    fun `a song the watch already holds is not requested again`() {
        val bytes = song(7)
        store.beginIncoming(manifest(bytes), 0).use {
            it.write(bytes, 0, bytes.size)
            it.commit()
        }

        session { w, r ->
            w.writeControl(SyncMessage.Offer(manifest(bytes)))
            assertThat(r.message()).isEqualTo(SyncMessage.Have(id))
            w.writeControl(SyncMessage.End)
        }

        assertThat(changes.get()).isEqualTo(0)
    }

    @Test
    fun `a transfer cut short is requested from where it stopped`() {
        val bytes = song(7)
        store.beginIncoming(manifest(bytes), 0).use { it.write(bytes, 0, 600) }

        session { w, r ->
            w.writeControl(SyncMessage.Offer(manifest(bytes)))
            assertThat(r.message()).isEqualTo(SyncMessage.Request(id, 600))
            w.send(bytes, from = 600)
            assertThat(r.message()).isEqualTo(SyncMessage.Done(id))
            w.writeControl(SyncMessage.End)
        }

        assertThat(File(dir, "$id.opus").readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `a partial file that is already complete finishes without any data`() {
        val bytes = song(7)
        store.beginIncoming(manifest(bytes), 0).use { it.write(bytes, 0, bytes.size) }

        session { w, r ->
            w.writeControl(SyncMessage.Offer(manifest(bytes)))
            assertThat(r.message()).isEqualTo(SyncMessage.Request(id, bytes.size.toLong()))
            assertThat(r.message()).isEqualTo(SyncMessage.Done(id))
            w.writeControl(SyncMessage.End)
        }

        assertThat(File(dir, "$id.opus").readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `an offer that does not fit is rejected and nothing is created`() {
        val bytes = song(7, size = 3000)
        free = SyncLimits.MIN_FREE_RESERVE_BYTES + 2999

        session { w, r ->
            w.writeControl(SyncMessage.Offer(manifest(bytes)))
            assertThat(r.message()).isEqualTo(SyncMessage.Reject(id, SyncError.NO_SPACE))
            w.writeControl(SyncMessage.End)
        }

        assertThat(dir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `only the bytes still to come need room`() {
        val bytes = song(7, size = 3000)
        store.beginIncoming(manifest(bytes), 0).use { it.write(bytes, 0, 2000) }
        free = SyncLimits.MIN_FREE_RESERVE_BYTES + 1000

        session { w, r ->
            w.writeControl(SyncMessage.Offer(manifest(bytes)))
            assertThat(r.message()).isEqualTo(SyncMessage.Request(id, 2000))
            w.send(bytes, from = 2000)
            assertThat(r.message()).isEqualTo(SyncMessage.Done(id))
            w.writeControl(SyncMessage.End)
        }
    }

    @Test
    fun `a malformed offer is rejected`() {
        val bad = manifest(song(7)).copy(id = "../../x")

        session { w, r ->
            w.writeControl(SyncMessage.Offer(bad))
            assertThat(r.message()).isEqualTo(SyncMessage.Reject("../../x", SyncError.INVALID_TRACK))
            w.writeControl(SyncMessage.End)
        }

        assertThat(dir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a wrong checksum fails the song and the next try starts from zero`() {
        val bytes = song(7)
        val wrong = song(8)
        val track = manifest(bytes)

        session { w, r ->
            w.writeControl(SyncMessage.Offer(track))
            assertThat(r.message()).isEqualTo(SyncMessage.Request(id, 0))
            w.send(wrong)
            assertThat(r.message()).isEqualTo(SyncMessage.Failed(id, SyncError.CHECKSUM_MISMATCH))

            w.writeControl(SyncMessage.Offer(track))
            assertThat(r.message()).isEqualTo(SyncMessage.Request(id, 0))
            w.send(bytes)
            assertThat(r.message()).isEqualTo(SyncMessage.Done(id))
            w.writeControl(SyncMessage.End)
        }

        assertThat(File(dir, "$id.opus").readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `remove deletes the songs and announces the change`() {
        val bytes = song(7)
        store.beginIncoming(manifest(bytes), 0).use {
            it.write(bytes, 0, bytes.size)
            it.commit()
        }

        session { w, r ->
            w.writeControl(SyncMessage.Remove(listOf(id)))
            assertThat(r.message()).isEqualTo(SyncMessage.Removed(listOf(id)))
            w.writeControl(SyncMessage.End)
        }

        assertThat(dir.listFiles().orEmpty()).isEmpty()
        assertThat(changes.get()).isEqualTo(1)
    }

    @Test
    fun `data with no offer is a protocol error`() {
        val result = session { w, _ ->
            w.send(song(7))
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(ProtocolException::class.java)
    }

    @Test
    fun `more data than the offered size is a protocol error and drops the partial file`() {
        val bytes = song(7)

        val result = session { w, r ->
            w.writeControl(SyncMessage.Offer(manifest(bytes)))
            r.message()
            w.send(bytes + byteArrayOf(1, 2, 3), chunk = 1003)
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(ProtocolException::class.java)
        assertThat(dir.listFiles().orEmpty().filter { it.name.endsWith(".opus") }).isEmpty()
    }

    @Test
    fun `a connection that drops mid song keeps what arrived for the next try`() {
        val bytes = song(7)
        val track = manifest(bytes)
        // The offer and a few data frames cross, then the link dies inside a frame.
        val duplex = Duplex(cutPhoneToWatchAfterBytes = 700)

        val result = session(duplex = duplex) { w, r ->
            w.writeControl(SyncMessage.Offer(track))
            r.message()
            runCatching { w.send(bytes) }
        }

        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        val kept = store.partialLength(track)
        assertThat(kept).isGreaterThan(0)
        assertThat(kept % 300).isEqualTo(0) // only whole frames were written
        assertThat(store.contents()).isEmpty()
    }

    @Test
    fun `the end of the stream between frames ends the session normally`() {
        val result = session { _, _ -> }

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `cancelling a session that waits for the phone returns promptly`() = runBlocking<Unit> {
        val duplex = Duplex()
        val serving = async(Dispatchers.IO) { runCatching { receiver().serve(duplex.watch) } }

        withTimeout(10_000) { serving.cancelAndJoin() }

        assertThat(serving.isCancelled).isTrue()
        assertThrows(IOException::class.java) { duplex.watch.output.write(1) }
    }

    @Test
    fun `a new session supersedes one that is stuck`() = runBlocking<Unit> {
        val receiver = receiver()
        val stuck = Duplex()
        val first = async(Dispatchers.IO) { runCatching { receiver.serve(stuck.watch) } }
        Thread.sleep(100)

        val second = Duplex()
        val serving = async(Dispatchers.IO) { runCatching { receiver.serve(second.watch) } }
        withTimeout(10_000) { first.await() }
        val writer = FrameWriter(second.phone.output)
        writer.writeControl(SyncMessage.Hello())
        assertThat((FrameReader(second.phone.input).read() as Frame.Control).message).isEqualTo(SyncMessage.Hello())
        writer.writeControl(SyncMessage.End)

        assertThat(withTimeout(10_000) { serving.await() }.isSuccess).isTrue()
    }
}
