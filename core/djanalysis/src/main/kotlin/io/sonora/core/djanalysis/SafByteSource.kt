package io.sonora.core.djanalysis

import android.content.ContentResolver
import android.net.Uri
import io.sonora.core.djmodel.ByteSource
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A [ByteSource] over a [FileChannel]: positional reads (`FileChannel.read(buffer, position)`), so the channel's own
 * position is never moved and reads at any offset cost the same. [length] is the size the file had when it was opened.
 * Closing it closes the channel and then [owner] (the descriptor the channel came from), once.
 */
class ChannelByteSource(
    private val channel: FileChannel,
    override val length: Long,
    private val owner: Closeable? = null,
) : ByteSource {

    private var closed = false

    override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
        require(position >= 0) { "position must not be negative: $position" }
        require(offset >= 0 && count >= 0 && offset + count <= buffer.size) {
            "offset $offset and count $count do not fit a buffer of ${buffer.size}"
        }
        if (count == 0) return 0
        val target = ByteBuffer.wrap(buffer, offset, count)
        while (true) {
            val read = channel.read(target, position)
            // A file channel blocks until it has a byte or the end; 0 would only mean no room, which count > 0 rules out.
            if (read != 0) return read
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            channel.close()
        } finally {
            owner?.close()
        }
    }
}

/** Opens SAF documents (and any `content://` or `file://` uri the resolver serves) as [ByteSource]s. */
object SafByteSource {

    /**
     * Opens [uri] read-only. The caller closes the source.
     *
     * @throws FileNotFoundException when the provider has no such document or refuses it.
     * @throws SecurityException when the permission to read it was revoked.
     */
    fun open(resolver: ContentResolver, uri: Uri): ByteSource {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        return try {
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            ChannelByteSource(channel, descriptor.statSize, descriptor)
        } catch (e: Exception) {
            descriptor.close()
            throw e
        }
    }
}
