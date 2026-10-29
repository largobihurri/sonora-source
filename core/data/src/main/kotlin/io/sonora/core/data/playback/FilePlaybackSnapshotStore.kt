package io.sonora.core.data.playback

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PlaybackSnapshotStore
import io.sonora.core.domain.queue.PlaybackSnapshot
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [PlaybackSnapshotStore] in two small binary files under the app's private files: `queue.bin` (the whole snapshot)
 * and `position.bin` (the current entry and position, rewritten often). Each is replaced atomically, so a crash
 * mid-write leaves the previous version; a file that cannot be parsed (damaged, or from a future format) loads as
 * nothing rather than failing.
 *
 * The replacement follows `androidx.core.util.AtomicFile` (a synced sibling `.new` file moved over the old one) but
 * moves with [Files.move], which replaces the target on every platform; `AtomicFile` uses `File.renameTo`, which on a
 * Windows JVM leaves the old file in place and only logs.
 */
@Singleton
class FilePlaybackSnapshotStore internal constructor(
    private val directory: File,
    private val io: CoroutineDispatcher,
) : PlaybackSnapshotStore {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "playback"), Dispatchers.IO)

    private val mutex = Mutex()
    private val queueFile by lazy { File(directory, "queue.bin") }
    private val positionFile by lazy { File(directory, "position.bin") }

    override suspend fun load(): PlaybackSnapshot? = access {
        val snapshot = read(queueFile, QUEUE_MAGIC) { readSnapshot() } ?: return@access null
        val position = read(positionFile, POSITION_MAGIC) { readLong() to readLong() } ?: return@access snapshot
        val index = snapshot.items.indexOfFirst { it.id == position.first }
        if (index < 0) snapshot else snapshot.copy(currentIndex = index, positionMs = position.second)
    }

    override suspend fun save(snapshot: PlaybackSnapshot) = access {
        write(queueFile, QUEUE_MAGIC) { writeSnapshot(snapshot) }
        val current = snapshot.items.getOrNull(snapshot.currentIndex) ?: return@access
        write(positionFile, POSITION_MAGIC) { writeLong(current.id); writeLong(snapshot.positionMs) }
    }

    override suspend fun savePosition(itemId: Long, positionMs: Long) = access {
        write(positionFile, POSITION_MAGIC) { writeLong(itemId); writeLong(positionMs) }
    }

    private suspend fun <T> access(block: () -> T): T = withContext(io) { mutex.withLock { block() } }

    /** The file a write goes to before it replaces [file]. */
    private fun pendingOf(file: File) = File(file.path + ".new")

    private fun write(file: File, magic: Int, body: DataOutputStream.() -> Unit) {
        directory.mkdirs()
        val pending = pendingOf(file)
        try {
            FileOutputStream(pending).use { stream ->
                DataOutputStream(BufferedOutputStream(stream)).apply {
                    writeInt(magic)
                    body()
                    flush()
                }
                stream.fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (e: Throwable) {
            // Any failure, not only I/O (a runtime exception from the body too): the previous version stands.
            pending.delete()
            throw e
        }
    }

    private fun <T> read(file: File, magic: Int, body: DataInputStream.() -> T): T? {
        // A write cut short by a crash: the previous version stands, as with AtomicFile.openRead.
        pendingOf(file).delete()
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != magic) throw IOException("Unknown format")
                input.body()
            }
        } catch (e: Exception) {
            // IOException (truncated), or garbage read as a huge count, an unknown repeat mode, a bad index.
            Log.w(TAG, "Ignoring unreadable ${file.name}", e)
            null
        }
    }

    private fun DataOutputStream.writeSnapshot(snapshot: PlaybackSnapshot) {
        writeInt(snapshot.currentIndex)
        writeLong(snapshot.positionMs)
        writeBoolean(snapshot.shuffled)
        writeText(snapshot.repeatMode.name)
        writeInt(snapshot.items.size)
        snapshot.items.forEach { item ->
            writeLong(item.id)
            writeTrack(item.track)
        }
        writeInt(snapshot.unshuffledIds.size)
        snapshot.unshuffledIds.forEach(::writeLong)
    }

    private fun DataInputStream.readSnapshot(): PlaybackSnapshot {
        val currentIndex = readInt()
        val positionMs = readLong()
        val shuffled = readBoolean()
        val repeatMode = RepeatMode.valueOf(readText())
        val items = List(readCount()) { QueueItem(readLong(), readTrack()) }
        val unshuffledIds = List(readCount()) { readLong() }
        require(currentIndex in items.indices) { "Current index $currentIndex outside ${items.size} items" }
        return PlaybackSnapshot(items, currentIndex, positionMs, shuffled, unshuffledIds, repeatMode)
    }

    private fun DataOutputStream.writeTrack(t: Track) {
        writeLong(t.id)
        writeText(t.uri)
        writeText(t.title)
        writeText(t.artist)
        writeText(t.album)
        writeOptionalText(t.albumArtist)
        writeLong(t.durationMs)
        writeOptionalInt(t.trackNumber)
        writeOptionalInt(t.discNumber)
        writeOptionalInt(t.year)
        writeLong(t.albumId)
        writeLong(t.artistId)
        writeOptionalText(t.artworkUri)
        writeText(t.mimeType)
        writeLong(t.sizeBytes)
        writeLong(t.dateAddedSec)
        writeOptionalText(t.genre)
        writeOptionalText(t.folderPath)
        writeOptionalInt(t.sampleRateHz)
        writeOptionalInt(t.bitsPerSample)
        writeOptionalInt(t.channelCount)
    }

    private fun DataInputStream.readTrack() = Track(
        id = readLong(),
        uri = readText(),
        title = readText(),
        artist = readText(),
        album = readText(),
        albumArtist = readOptionalText(),
        durationMs = readLong(),
        trackNumber = readOptionalInt(),
        discNumber = readOptionalInt(),
        year = readOptionalInt(),
        albumId = readLong(),
        artistId = readLong(),
        artworkUri = readOptionalText(),
        mimeType = readText(),
        sizeBytes = readLong(),
        dateAddedSec = readLong(),
        genre = readOptionalText(),
        folderPath = readOptionalText(),
        sampleRateHz = readOptionalInt(),
        bitsPerSample = readOptionalInt(),
        channelCount = readOptionalInt(),
    )

    /** Length-prefixed UTF-8 (no 64 KB limit, unlike writeUTF). */
    private fun DataOutputStream.writeText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        if (size !in 0..MAX_TEXT_BYTES) throw IOException("Bad text length $size")
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeOptionalText(text: String?) {
        writeBoolean(text != null)
        if (text != null) writeText(text)
    }

    private fun DataInputStream.readOptionalText(): String? = if (readBoolean()) readText() else null

    private fun DataOutputStream.writeOptionalInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataInputStream.readOptionalInt(): Int? = if (readBoolean()) readInt() else null

    private fun DataInputStream.readCount(): Int = readInt().also { if (it !in 0..MAX_ITEMS) throw IOException("Bad count $it") }

    private companion object {
        const val TAG = "SonoraSnapshot"
        const val QUEUE_MAGIC = 0x534E5101 // "SNQ" + format version 1
        const val POSITION_MAGIC = 0x534E5001 // "SNP" + format version 1
        const val MAX_ITEMS = 500_000
        const val MAX_TEXT_BYTES = 1 shl 20
    }
}
