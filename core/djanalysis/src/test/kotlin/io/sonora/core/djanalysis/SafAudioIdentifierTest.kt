package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.identity.AudioPayloadHasher
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.ByteSource
import io.sonora.core.djmodel.FileStamp
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertThrows
import org.junit.Test

class SafAudioIdentifierTest {

    private val uri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fa.bin"
    private val stamp = FileStamp(sizeBytes = 4_096, lastModifiedMs = 1_700_000_000_000)

    /** Bytes no walker recognises, so the id is the whole-file hash and the test can compute it itself. */
    private val content = ByteArray(4_096) { (it % 251).toByte() }
    private val documents = FakeDocuments(mutableMapOf(uri to content), mutableMapOf(uri to stamp))
    private val index = FakeIndex()
    private val identifier = SafAudioIdentifier(documents, index, AudioPayloadHasher(), Dispatchers.Unconfined)

    @Test
    fun `identifies a byte source with the payload hasher`() = runBlocking<Unit> {
        val id = identifier.identify(ArraySource(content))

        assertThat(id).isEqualTo(wholeFileId(content))
    }

    @Test
    fun `a never seen file is hashed and remembered with its stamp`() = runBlocking<Unit> {
        val id = identifier.identify(uri)

        assertThat(id).isEqualTo(wholeFileId(content))
        assertThat(documents.opened).isEqualTo(1)
        assertThat(index.entries[uri]).isEqualTo(id to stamp)
    }

    @Test
    fun `a file with the remembered stamp is not read again`() = runBlocking<Unit> {
        val remembered = AudioId.of(AudioId.PAYLOAD_V1, "a".repeat(64))
        index.entries[uri] = remembered to stamp

        val id = identifier.identify(uri)

        assertThat(id).isEqualTo(remembered)
        assertThat(documents.opened).isEqualTo(0)
    }

    @Test
    fun `a file whose stamp changed is hashed again and the memo is updated`() = runBlocking<Unit> {
        index.entries[uri] = AudioId.of(AudioId.PAYLOAD_V1, "a".repeat(64)) to stamp
        val newStamp = stamp.copy(lastModifiedMs = stamp.lastModifiedMs + 1_000)
        documents.stamps[uri] = newStamp

        val id = identifier.identify(uri)

        assertThat(id).isEqualTo(wholeFileId(content))
        assertThat(documents.opened).isEqualTo(1)
        assertThat(index.entries[uri]).isEqualTo(id to newStamp)
    }

    @Test
    fun `a file without a stamp is always hashed`() = runBlocking<Unit> {
        documents.stamps.remove(uri)
        index.entries[uri] = AudioId.of(AudioId.PAYLOAD_V1, "a".repeat(64)) to null

        val first = identifier.identify(uri)
        val second = identifier.identify(uri)

        assertThat(first).isEqualTo(wholeFileId(content))
        assertThat(second).isEqualTo(first)
        assertThat(documents.opened).isEqualTo(2)
        assertThat(index.lookupsWithoutStamp).isEqualTo(0)
    }

    @Test
    fun `the source is closed after hashing`() = runBlocking<Unit> {
        identifier.identify(uri)

        assertThat(documents.lastSource?.closed).isTrue()
    }

    @Test
    fun `an unreadable file fails and nothing is remembered`() {
        documents.files.remove(uri)

        assertThrows(FileNotFoundException::class.java) { runBlocking { identifier.identify(uri) } }
        assertThat(index.entries).isEmpty()
    }

    @Test
    fun `a cancelled caller stops the hashing between reads`() {
        val big = ByteArray(1 shl 20) { it.toByte() }
        documents.files[uri] = big
        val job = Job()
        documents.onRead = { reads -> if (reads == 2) job.cancel() }

        assertThrows(CancellationException::class.java) {
            runBlocking { withContext(job) { identifier.identify(uri) } }
        }
        assertThat(documents.lastSource!!.reads).isAtMost(3)
        assertThat(documents.lastSource!!.closed).isTrue()
        assertThat(index.entries).isEmpty()
    }

    private fun wholeFileId(bytes: ByteArray): AudioId {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return AudioId.of(AudioId.WHOLE_FILE_V1, hex)
    }

    private class ArraySource(private val bytes: ByteArray, private val onRead: (Int) -> Unit = {}) : ByteSource {
        var reads = 0
        var closed = false

        override val length: Long get() = bytes.size.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            reads++
            onRead(reads)
            if (position >= bytes.size) return -1
            val n = minOf(count.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            return n
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeDocuments(
        val files: MutableMap<String, ByteArray>,
        val stamps: MutableMap<String, FileStamp>,
    ) : AudioDocuments {
        var opened = 0
        var lastSource: ArraySource? = null
        var onRead: (Int) -> Unit = {}

        override fun stamp(sourceUri: String): FileStamp? = stamps[sourceUri]

        override fun open(sourceUri: String): ByteSource {
            val bytes = files[sourceUri] ?: throw FileNotFoundException(sourceUri)
            opened++
            return ArraySource(bytes, onRead).also { lastSource = it }
        }
    }

    private class FakeIndex : AudioIdIndex {
        val entries = HashMap<String, Pair<AudioId, FileStamp?>>()
        var lookupsWithoutStamp = 0

        override suspend fun idFor(sourceUri: String, stamp: FileStamp?): AudioId? {
            if (stamp == null) lookupsWithoutStamp++
            val (id, remembered) = entries[sourceUri] ?: return null
            return if (stamp == null || stamp == remembered) id else null
        }

        override suspend fun remember(sourceUri: String, id: AudioId, stamp: FileStamp?) {
            entries[sourceUri] = id to stamp
        }
    }
}
