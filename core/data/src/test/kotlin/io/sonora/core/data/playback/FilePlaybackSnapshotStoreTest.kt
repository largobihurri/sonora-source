package io.sonora.core.data.playback

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.queue.PlaybackSnapshot
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilePlaybackSnapshotStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val directory by lazy { File(folder.root, "playback") }
    private fun store() = FilePlaybackSnapshotStore(directory, Dispatchers.Unconfined)

    /** Every field set, including nulls, non-ASCII text and negative (foreign) item ids. */
    private val full = Track(
        id = 7, uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fa.aiff",
        title = "Été — 夜", artist = "Ünïcode", album = "B", albumArtist = "AA", durationMs = 312_000, trackNumber = 3,
        discNumber = 2, year = 1999, albumId = 11, artistId = 12, artworkUri = "file:///art/11.jpg", mimeType = "audio/aiff",
        sizeBytes = 55_000_000, dateAddedSec = 1_700_000_000, genre = "House", folderPath = "Music/Deep",
        sampleRateHz = 96_000, bitsPerSample = 24, channelCount = 2,
    )
    private val sparse = Track(
        id = 8, uri = "content://doc/8", title = "", artist = "", album = "", albumArtist = null, durationMs = 0,
        trackNumber = null, discNumber = null, year = null, albumId = 0, artworkUri = null, mimeType = "audio/*", sizeBytes = 0,
    )

    private val snapshot = PlaybackSnapshot(
        items = listOf(QueueItem(3, full), QueueItem(-1, sparse), QueueItem(9, full)),
        currentIndex = 1,
        positionMs = 61_500,
        shuffled = true,
        unshuffledIds = listOf(9, 3, -1),
        repeatMode = RepeatMode.ONE,
    )

    @Test
    fun `nothing saved loads nothing`() = runTest {
        assertThat(store().load()).isNull()
    }

    @Test
    fun `a saved snapshot loads back unchanged in a new store`() = runTest {
        store().save(snapshot)

        assertThat(store().load()).isEqualTo(snapshot)
    }

    @Test
    fun `a saved position moves the current entry without rewriting the queue`() = runTest {
        val store = store()
        store.save(snapshot)
        val queueBytes = File(directory, "queue.bin").readBytes()

        store.savePosition(itemId = 9, positionMs = 1_234)

        assertThat(File(directory, "queue.bin").readBytes()).isEqualTo(queueBytes)
        assertThat(store().load()).isEqualTo(snapshot.copy(currentIndex = 2, positionMs = 1_234))
    }

    @Test
    fun `a position for an entry no longer queued is ignored`() = runTest {
        val store = store()
        store.save(snapshot)
        store.savePosition(itemId = 42, positionMs = 9_999)

        assertThat(store.load()).isEqualTo(snapshot)
    }

    @Test
    fun `saving a snapshot replaces an older position`() = runTest {
        val store = store()
        store.save(snapshot)
        store.savePosition(itemId = 9, positionMs = 1_234)

        store.save(snapshot.copy(currentIndex = 0, positionMs = 5))

        assertThat(store.load()).isEqualTo(snapshot.copy(currentIndex = 0, positionMs = 5))
    }

    @Test
    fun `a damaged or foreign file loads nothing instead of failing`() = runTest {
        store().save(snapshot)
        val file = File(directory, "queue.bin")
        file.writeBytes(file.readBytes().copyOf(40))
        assertThat(store().load()).isNull()

        file.writeBytes(byteArrayOf(1, 2, 3))
        assertThat(store().load()).isNull()

        store().save(snapshot)
        File(directory, "position.bin").writeBytes(byteArrayOf(9))
        assertThat(store().load()).isEqualTo(snapshot)
    }

    // The hand-written atomic replace: the snapshot goes to a `.new` sibling, which is moved over the old file.

    /** A queue whose entries cannot be read: the write fails after its pending file is open. */
    private fun failingSnapshot(failure: Throwable) = snapshot.copy(
        items = object : AbstractList<QueueItem>() {
            override val size = 1
            override fun get(index: Int): QueueItem = throw failure
        },
    )

    @Test
    fun `a pending file left by a crash is dropped on read and the previous snapshot loads`() = runTest {
        store().save(snapshot)
        val pending = File(directory, "queue.bin.new")
        pending.writeBytes(byteArrayOf(1, 2, 3))

        assertThat(store().load()).isEqualTo(snapshot)
        assertThat(pending.exists()).isFalse()
    }

    @Test
    fun `a write that cannot open its pending file leaves the previous snapshot and no pending file`() = runTest {
        val store = store()
        store.save(snapshot)
        val pending = File(directory, "queue.bin.new").apply { mkdirs() } // a directory: the file cannot be opened

        val result = runCatching { store.save(snapshot.copy(positionMs = 1)) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(pending.exists()).isFalse()
        assertThat(store().load()).isEqualTo(snapshot)
    }

    @Test
    fun `a write that fails midway leaves the previous snapshot and no pending file`() = runTest {
        val store = store()
        store.save(snapshot)

        val result = runCatching { store.save(failingSnapshot(IOException("disk full"))) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(File(directory, "queue.bin.new").exists()).isFalse()
        assertThat(store().load()).isEqualTo(snapshot)
    }

    @Test
    fun `a write that fails midway with a runtime exception also drops its pending file`() = runTest {
        val store = store()
        store.save(snapshot)

        val result = runCatching { store.save(failingSnapshot(IllegalStateException("bad entry"))) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(File(directory, "queue.bin.new").exists()).isFalse()
        assertThat(store().load()).isEqualTo(snapshot)
    }
}
