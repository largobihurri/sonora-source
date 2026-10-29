package io.sonora.core.watchsync.receiver

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.Sha256
import io.sonora.core.watchsync.protocol.SyncMessage
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicLibraryStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var free = 10_000_000_000L
    private var now = System.currentTimeMillis()
    private lateinit var dir: File
    private lateinit var store: MusicLibraryStore

    @Before
    fun setUp() {
        dir = File(folder.root, "music")
        store = MusicLibraryStore(dir, freeSpace = { free }, totalSpace = { 23_000_000_000L }, clock = { now })
    }

    private val id = "0123456789abcdef"

    private fun song(seed: Int, size: Int = 1000) = ByteArray(size) { (it * seed).toByte() }

    private fun manifest(bytes: ByteArray, trackId: String = id) = ManifestTrack(
        id = trackId,
        title = "Song $trackId",
        artist = "Artist",
        durationMs = 3000,
        sizeBytes = bytes.size.toLong(),
        sha256 = Sha256.hex(bytes),
    )

    private fun receive(bytes: ByteArray, track: ManifestTrack = manifest(bytes), chunk: Int = 300): CommitResult {
        store.beginIncoming(track, store.partialLength(track)).use { incoming ->
            var from = incoming.received.toInt()
            while (from < bytes.size) {
                val count = minOf(chunk, bytes.size - from)
                incoming.write(bytes, from, count)
                from += count
            }
            return incoming.commit()
        }
    }

    @Test
    fun `an empty library has no songs and reports its space`() {
        assertThat(store.contents()).isEmpty()
        assertThat(store.space()).isEqualTo(SyncMessage.FreeSpace(freeBytes = free, totalBytes = 23_000_000_000L))
    }

    @Test
    fun `a received song is committed under its id and listed`() {
        val bytes = song(7)

        assertThat(receive(bytes)).isEqualTo(CommitResult.Committed)

        assertThat(File(dir, "$id.opus").readBytes()).isEqualTo(bytes)
        assertThat(store.contents()).containsExactly(manifest(bytes))
        assertThat(dir.listFiles().orEmpty().map { it.name }).containsExactly("$id.opus", "$id.json")
    }

    @Test
    fun `a song being received is not a playable file yet`() {
        val bytes = song(7)
        val track = manifest(bytes)

        store.beginIncoming(track, 0).use { it.write(bytes, 0, 500) }

        val names = dir.listFiles().orEmpty().map { it.name }
        assertThat(names).hasSize(1)
        assertThat(names.single()).endsWith(".opus.part")
        assertThat(store.contents()).isEmpty()
    }

    @Test
    fun `a wrong checksum drops the partial file and leaves the library untouched`() {
        val bytes = song(7)
        val wrong = manifest(bytes).copy(sha256 = Sha256.hex(song(8)))

        assertThat(receive(bytes, wrong)).isEqualTo(CommitResult.ChecksumMismatch)

        assertThat(dir.listFiles().orEmpty()).isEmpty()
        assertThat(store.contents()).isEmpty()
    }

    @Test
    fun `a transfer cut short resumes from the bytes already on disk`() {
        val bytes = song(7)
        val track = manifest(bytes)
        store.beginIncoming(track, 0).use { it.write(bytes, 0, 400) }

        assertThat(store.partialLength(track)).isEqualTo(400)

        assertThat(receive(bytes, track)).isEqualTo(CommitResult.Committed)
        assertThat(File(dir, "$id.opus").readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `a resumed transfer is still checked as a whole`() {
        val bytes = song(7)
        val track = manifest(bytes)
        val damaged = bytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        store.beginIncoming(track, 0).use { it.write(damaged, 0, 400) }

        assertThat(receive(bytes, track)).isEqualTo(CommitResult.ChecksumMismatch)
        assertThat(store.partialLength(track)).isEqualTo(0)
    }

    @Test
    fun `there is no partial length without a partial file or with one that is too long`() {
        val bytes = song(7)
        val track = manifest(bytes)
        assertThat(store.partialLength(track)).isEqualTo(0)

        store.beginIncoming(track, 0).use { it.write(ByteArray(1000), 0, 1000) }
        val shorter = track.copy(sizeBytes = 900)
        // The same id and checksum prefix but a smaller size: the partial cannot belong to it.
        assertThat(store.partialLength(shorter)).isEqualTo(0)
        assertThat(dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }).isEmpty()
    }

    @Test
    fun `a partial file of other content with the same id is not resumed`() {
        val track = manifest(song(7))
        val other = manifest(song(8))
        store.beginIncoming(other, 0).use { it.write(song(8), 0, 400) }

        assertThat(store.partialLength(track)).isEqualTo(0)
    }

    @Test
    fun `beginning at an offset that is not the partial length is refused`() {
        val track = manifest(song(7))
        store.beginIncoming(track, 0).use { it.write(song(7), 0, 400) }

        assertThrows(IllegalArgumentException::class.java) { store.beginIncoming(track, 100) }
        assertThrows(IllegalArgumentException::class.java) { store.beginIncoming(track, 401) }
    }

    @Test
    fun `writing past the song's size is refused`() {
        val track = manifest(song(7, size = 100))

        store.beginIncoming(track, 0).use {
            assertThrows(IllegalArgumentException::class.java) { it.write(ByteArray(101), 0, 101) }
        }
    }

    @Test
    fun `committing before all the bytes are in is refused`() {
        val track = manifest(song(7))

        store.beginIncoming(track, 0).use {
            it.write(song(7), 0, 10)
            assertThrows(IllegalStateException::class.java) { it.commit() }
        }
    }

    @Test
    fun `a song is held only when id, checksum and file all match`() {
        val bytes = song(7)
        val track = manifest(bytes)
        assertThat(store.has(track)).isFalse()

        receive(bytes)

        assertThat(store.has(track)).isTrue()
        assertThat(store.has(track.copy(sha256 = Sha256.hex(song(8))))).isFalse()
        File(dir, "$id.opus").writeBytes(ByteArray(5))
        assertThat(store.has(track)).isFalse()
    }

    @Test
    fun `contents leaves out entries without their audio, with a different size, or unreadable`() {
        val a = song(7)
        receive(a, manifest(a, "aaaaaaaaaaaaaaaa"))
        val b = song(8)
        receive(b, manifest(b, "bbbbbbbbbbbbbbbb"))
        val c = song(9)
        receive(c, manifest(c, "cccccccccccccccc"))
        File(dir, "aaaaaaaaaaaaaaaa.opus").delete()
        File(dir, "bbbbbbbbbbbbbbbb.opus").appendBytes(byteArrayOf(1))
        File(dir, "dddddddddddddddd.json").writeText("not json")

        assertThat(store.contents().map { it.id }).containsExactly("cccccccccccccccc")
    }

    @Test
    fun `contents are in id order`() {
        listOf("cccccccccccccccc", "aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb").forEach {
            val bytes = song(5)
            receive(bytes, manifest(bytes, it))
        }

        assertThat(store.contents().map { it.id })
            .containsExactly("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", "cccccccccccccccc").inOrder()
    }

    @Test
    fun `a new version of a song replaces the old one only when it is complete`() {
        val old = song(7)
        receive(old)
        val updated = song(9, size = 1500)
        val track = manifest(updated)

        store.beginIncoming(track, 0).use { it.write(updated, 0, 700) }
        assertThat(File(dir, "$id.opus").readBytes()).isEqualTo(old)

        receive(updated, track)
        assertThat(File(dir, "$id.opus").readBytes()).isEqualTo(updated)
        assertThat(store.contents()).containsExactly(track)
    }

    @Test
    fun `removing a song deletes its files and partials and nothing else`() {
        val a = song(7)
        receive(a, manifest(a, "aaaaaaaaaaaaaaaa"))
        val b = song(8)
        receive(b, manifest(b, "bbbbbbbbbbbbbbbb"))
        val c = manifest(song(9), "aaaaaaaaaaaaaaaa").copy(sha256 = Sha256.hex(song(9)))
        store.beginIncoming(c, 0).use { it.write(song(9), 0, 100) }

        val removed = store.remove(listOf("aaaaaaaaaaaaaaaa"))

        assertThat(removed).containsExactly("aaaaaaaaaaaaaaaa")
        assertThat(dir.listFiles().orEmpty().map { it.name }).containsExactly("bbbbbbbbbbbbbbbb.opus", "bbbbbbbbbbbbbbbb.json")
    }

    @Test
    fun `removing is idempotent and ignores ids that are not ids`() {
        val a = song(7)
        receive(a, manifest(a, "aaaaaaaaaaaaaaaa"))
        File(folder.root, "outside.opus").writeBytes(ByteArray(3))

        val removed = store.remove(listOf("aaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaa", "../outside", "zz"))

        assertThat(removed).containsExactly("aaaaaaaaaaaaaaaa")
        assertThat(File(folder.root, "outside.opus").exists()).isTrue()
        assertThat(store.remove(listOf("aaaaaaaaaaaaaaaa"))).containsExactly("aaaaaaaaaaaaaaaa")
    }

    @Test
    fun `old partial files are discarded and recent ones kept`() {
        val week = 7L * 24 * 3600 * 1000
        val old = manifest(song(7), "aaaaaaaaaaaaaaaa")
        store.beginIncoming(old, 0).use { it.write(song(7), 0, 10) }
        val recent = manifest(song(8), "bbbbbbbbbbbbbbbb")
        store.beginIncoming(recent, 0).use { it.write(song(8), 0, 10) }
        dir.listFiles().orEmpty().single { it.name.startsWith("aaaa") }.setLastModified(now - week - 1000)

        store.discardStalePartials(maxAgeMs = week)

        assertThat(store.partialLength(old)).isEqualTo(0)
        assertThat(store.partialLength(recent)).isEqualTo(10)
    }
}
