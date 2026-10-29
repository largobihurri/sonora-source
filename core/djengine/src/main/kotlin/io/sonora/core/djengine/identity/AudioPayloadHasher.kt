package io.sonora.core.djengine.identity

import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.ByteSource
import java.security.MessageDigest

/**
 * Computes the [AudioId] of an audio file so that retagging, renaming or moving it does not change the id.
 *
 * Under the [AudioId.PAYLOAD_V1] scheme (`ap1`) the id is the SHA-256 of the ASCII container tag of the first
 * [PayloadWalker] that matches the file and delimits its payload, followed by the payload byte ranges in file order.
 * The tag keeps the same bytes in two containers apart. Under [AudioId.WHOLE_FILE_V1] (`wf1`) the id is the plain
 * SHA-256 of every byte of the file; it is used whenever no walker applies: an unknown format, an input of unknown
 * length, a malformed or truncated container, or a source that ends before the length it reported. Format problems
 * never throw; I/O errors from the [ByteSource] propagate.
 *
 * Bytes are read by position in chunks of [CHUNK_SIZE], and the identification never depends on how the source splits
 * its reads. The `walkers` list is ordered and open ([defaultWalkers] holds FLAC, MP3, WAV, AIFF, MP4 and Ogg). When
 * several walkers match the first bytes (an ID3 tag can start both an MP3 and a FLAC file) they are tried in order
 * until one returns ranges. The walkers read through a view of the source that checks cancellation before every read,
 * so a long walk (an Ogg stream has one page header every few KiB) stops as promptly as the hashing does.
 */
class AudioPayloadHasher(private val walkers: List<PayloadWalker> = defaultWalkers()) {

    /**
     * @param checkCancelled called before every read chunk; it throws (typically a `CancellationException`) to stop.
     * @throws java.io.IOException when [source] cannot be read.
     */
    fun identify(source: ByteSource, checkCancelled: () -> Unit = {}): AudioId {
        checkCancelled()
        val length = source.length
        if (length >= 0) payloadId(source, length, checkCancelled)?.let { return it }
        return wholeFileId(source, length, checkCancelled)
    }

    private fun payloadId(source: ByteSource, length: Long, checkCancelled: () -> Unit): AudioId? {
        val prefix = ByteArray(minOf(length, PREFIX_SIZE.toLong()).toInt())
        if (!source.readFully(0, prefix)) return null
        for (walker in walkers) {
            if (!walker.matches(prefix)) continue
            checkCancelled()
            val ranges = walker.payloadRanges(CancellableSource(source, checkCancelled))?.takeIf { isValid(it, length) } ?: continue
            // A source that ends early cannot be walked: hash every byte instead.
            return hashPayload(source, walker.containerTag, ranges, checkCancelled)
        }
        return null
    }

    /** In file order, inside the file, not overlapping, and not empty overall. */
    private fun isValid(ranges: List<LongRange>, length: Long): Boolean {
        var previousEnd = 0L
        var total = 0L
        for (range in ranges) {
            if (range.isEmpty()) continue
            if (range.first < previousEnd || range.last >= length) return false
            previousEnd = range.last + 1
            total += range.last - range.first + 1
        }
        return total > 0
    }

    private fun hashPayload(
        source: ByteSource,
        containerTag: String,
        ranges: List<LongRange>,
        checkCancelled: () -> Unit,
    ): AudioId? {
        val digest = sha256()
        digest.update(containerTag.toByteArray(Charsets.US_ASCII))
        val buffer = ByteArray(CHUNK_SIZE)
        for (range in ranges) {
            var position = range.first
            val end = range.last + 1
            while (position < end) {
                checkCancelled()
                val read = source.read(position, buffer, 0, minOf(CHUNK_SIZE.toLong(), end - position).toInt())
                if (read <= 0) return null
                digest.update(buffer, 0, read)
                position += read
            }
        }
        return AudioId.of(AudioId.PAYLOAD_V1, digest.digest().toHex())
    }

    private fun wholeFileId(source: ByteSource, length: Long, checkCancelled: () -> Unit): AudioId {
        val digest = sha256()
        val buffer = ByteArray(CHUNK_SIZE)
        var position = 0L
        while (length < 0 || position < length) {
            checkCancelled()
            val wanted = if (length < 0) CHUNK_SIZE else minOf(CHUNK_SIZE.toLong(), length - position).toInt()
            val read = source.read(position, buffer, 0, wanted)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            position += read
        }
        return AudioId.of(AudioId.WHOLE_FILE_V1, digest.digest().toHex())
    }

    private fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        for (i in indices) {
            val b = this[i].toInt() and 0xFF
            out[2 * i] = HEX[b ushr 4]
            out[2 * i + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }

    /** Forwards every read to [delegate] after calling [checkCancelled]. */
    private class CancellableSource(private val delegate: ByteSource, private val checkCancelled: () -> Unit) : ByteSource {
        override val length: Long get() = delegate.length

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            checkCancelled()
            return delegate.read(position, buffer, offset, count)
        }

        override fun close() = delegate.close()
    }

    companion object {
        /** Bytes read per positional read; cancellation is checked once per chunk. */
        const val CHUNK_SIZE: Int = 64 * 1024

        private const val PREFIX_SIZE = 16
        private val HEX = "0123456789abcdef".toCharArray()

        /** The walkers in the order they are tried. Add the next container here. */
        fun defaultWalkers(): List<PayloadWalker> =
            listOf(
                FlacPayloadWalker,
                Mp3PayloadWalker,
                WavPayloadWalker,
                AiffPayloadWalker,
                Mp4PayloadWalker,
                OggPayloadWalker,
            )
    }
}
