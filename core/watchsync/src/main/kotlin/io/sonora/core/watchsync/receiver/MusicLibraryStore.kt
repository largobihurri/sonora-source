package io.sonora.core.watchsync.receiver

import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.Sha256
import io.sonora.core.watchsync.SyncLimits
import io.sonora.core.watchsync.TrackIds
import io.sonora.core.watchsync.protocol.SyncMessage
import io.sonora.core.watchsync.protocol.SyncMessageCodec
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.SerializationException

/** How a received song ended. */
sealed interface CommitResult {
    /** Verified and in the library. */
    data object Committed : CommitResult

    /** The bytes do not hash to the offered checksum; the partial file was deleted. */
    data object ChecksumMismatch : CommitResult
}

/**
 * The watch's music folder as the transfer sees it: what is held, what is half received, and the atomic steps between
 * (a song is written to a `.part` file, verified, and only then renamed into place, so the player never sees half a
 * song). Plain `java.io`, so it runs on the JVM and is tested there; the watch app passes `filesDir/music`.
 *
 * Not thread safe: one transfer session uses it at a time.
 */
class MusicLibraryStore(
    private val directory: File,
    private val freeSpace: () -> Long = { directory.usableSpace },
    private val totalSpace: () -> Long = { directory.totalSpace },
    private val clock: () -> Long = System::currentTimeMillis,
    private val reserveBytes: Long = SyncLimits.MIN_FREE_RESERVE_BYTES,
) {
    /** The storage's free and total bytes, and the reserve songs never use. */
    fun space(): SyncMessage.FreeSpace {
        directory.mkdirs()
        return SyncMessage.FreeSpace(freeBytes = freeSpace(), totalBytes = totalSpace(), reserveBytes = reserveBytes)
    }

    /** The songs held in full, in id order: a manifest entry whose audio file is there and has the entry's size. */
    fun contents(): List<ManifestTrack> = directory.listFiles { file -> file.isFile && file.name.endsWith(".${LibraryLayout.SIDECAR_EXTENSION}") }
        .orEmpty()
        .mapNotNull { readEntry(it) }
        .filter { isComplete(it) }
        .sortedBy { it.id }

    /** Whether exactly this song (id, size and checksum) is held in full. */
    fun has(track: ManifestTrack): Boolean {
        val held = readEntry(File(directory, LibraryLayout.sidecarName(track.id))) ?: return false
        return held.sizeBytes == track.sizeBytes && held.sha256.equals(track.sha256, ignoreCase = true) && isComplete(held)
    }

    /**
     * How many bytes of [track] a transfer cut short left on disk. A partial file longer than the song cannot be right
     * and is deleted.
     */
    fun partialLength(track: ManifestTrack): Long {
        val partial = File(directory, LibraryLayout.partialName(track))
        if (!partial.isFile) return 0
        val length = partial.length()
        if (length > track.sizeBytes) {
            partial.delete()
            return 0
        }
        return length
    }

    /**
     * Starts or continues receiving [track], from [offset], which must be [partialLength].
     *
     * @throws IllegalArgumentException when the track is malformed or [offset] is not the partial file's length.
     * @throws IOException when the partial file cannot be opened.
     */
    fun beginIncoming(track: ManifestTrack, offset: Long): IncomingTrack {
        require(track.problem() == null) { "Malformed track: ${track.problem()}" }
        directory.mkdirs()
        val partial = File(directory, LibraryLayout.partialName(track))
        val existing = if (partial.isFile) partial.length() else 0L
        require(offset == existing) { "Offset $offset is not the $existing bytes already received" }
        val digest = Sha256.newDigest()
        if (existing > 0) {
            partial.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var left = existing
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) throw IOException("Partial file ended early")
                    digest.update(buffer, 0, read)
                    left -= read
                }
            }
        }
        return IncomingTrack(track, partial, digest, FileOutputStream(partial, true), existing)
    }

    /**
     * Deletes these songs: audio, manifest entry and any partial file. Ids that are not ids (nothing a path could
     * escape with) are ignored. Returns the valid ids, as asking for a song that is already gone also succeeds.
     */
    fun remove(ids: List<String>): List<String> {
        val valid = ids.filter(TrackIds::isValid).distinct()
        val files = directory.listFiles().orEmpty()
        for (id in valid) {
            File(directory, LibraryLayout.audioName(id)).delete()
            File(directory, LibraryLayout.sidecarName(id)).delete()
            files.filter { it.name.startsWith(LibraryLayout.partialPrefix(id)) && it.name.endsWith(LibraryLayout.PARTIAL_SUFFIX) }
                .forEach { it.delete() }
        }
        return valid
    }

    /** Deletes partial and temporary files not touched for [maxAgeMs]: transfers that will not be resumed. */
    fun discardStalePartials(maxAgeMs: Long) {
        val limit = clock() - maxAgeMs
        directory.listFiles().orEmpty()
            .filter { it.name.endsWith(LibraryLayout.PARTIAL_SUFFIX) || it.name.endsWith(LibraryLayout.TEMP_SUFFIX) }
            .filter { it.lastModified() < limit }
            .forEach { it.delete() }
    }

    private fun readEntry(sidecar: File): ManifestTrack? {
        if (!sidecar.isFile) return null
        val track = try {
            SyncMessageCodec.json.decodeFromString(ManifestTrack.serializer(), sidecar.readText())
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: IOException) {
            return null
        }
        return track.takeIf { it.problem() == null && sidecar.name == LibraryLayout.sidecarName(it.id) }
    }

    private fun isComplete(track: ManifestTrack): Boolean {
        val audio = File(directory, LibraryLayout.audioName(track.id))
        return audio.isFile && audio.length() == track.sizeBytes
    }

    /** A song being received. [close] keeps the partial file for a later resume; [discard] deletes it. */
    inner class IncomingTrack internal constructor(
        val track: ManifestTrack,
        private val partial: File,
        private val digest: MessageDigest,
        private val output: FileOutputStream,
        received: Long,
    ) : Closeable {
        /** Bytes of the song on disk (and hashed) so far. */
        var received: Long = received
            private set

        private var open = true

        /** @throws IllegalArgumentException when the bytes would pass the song's size. */
        fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(received + length <= track.sizeBytes) { "More bytes than the song's ${track.sizeBytes}" }
            output.write(buffer, offset, length)
            digest.update(buffer, offset, length)
            received += length
        }

        /**
         * Verifies the whole song and moves it into the library: first its manifest entry, then the audio, each by an
         * atomic rename.
         *
         * @throws IllegalStateException before every byte is in.
         * @throws IOException when the storage cannot be written.
         */
        fun commit(): CommitResult {
            check(received == track.sizeBytes) { "Only $received of ${track.sizeBytes} bytes received" }
            finish()
            val actual = Sha256.toHex(digest.digest())
            if (!actual.equals(track.sha256, ignoreCase = true)) {
                partial.delete()
                return CommitResult.ChecksumMismatch
            }
            val sidecar = File(directory, LibraryLayout.sidecarName(track.id))
            val temp = File(directory, LibraryLayout.sidecarName(track.id) + LibraryLayout.TEMP_SUFFIX)
            temp.writeText(SyncMessageCodec.json.encodeToString(ManifestTrack.serializer(), track))
            moveReplacing(temp, sidecar)
            moveReplacing(partial, File(directory, LibraryLayout.audioName(track.id)))
            return CommitResult.Committed
        }

        /** Stops writing and keeps the partial file. */
        override fun close() {
            if (open) finish()
        }

        /** Stops writing and deletes the partial file. */
        fun discard() {
            close()
            partial.delete()
        }

        private fun finish() {
            open = false
            output.flush()
            runCatching { output.fd.sync() }
            output.close()
        }
    }

    private fun moveReplacing(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
