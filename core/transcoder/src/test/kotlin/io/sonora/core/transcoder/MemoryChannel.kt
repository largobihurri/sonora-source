package io.sonora.core.transcoder

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel

/** An in-memory [SeekableByteChannel], standing in for an output file. */
class MemoryChannel : SeekableByteChannel {
    private var data = ByteArray(1_024)
    private var size = 0
    private var position = 0
    private var open = true

    fun bytes(): ByteArray = data.copyOf(size)

    override fun read(dst: ByteBuffer): Int {
        check()
        if (position >= size) return -1
        val n = minOf(dst.remaining(), size - position)
        dst.put(data, position, n)
        position += n
        return n
    }

    override fun write(src: ByteBuffer): Int {
        check()
        val n = src.remaining()
        if (position + n > data.size) data = data.copyOf(maxOf(data.size * 2, position + n))
        src.get(data, position, n)
        position += n
        size = maxOf(size, position)
        return n
    }

    override fun position(): Long = position.toLong()

    override fun position(newPosition: Long): SeekableByteChannel {
        check()
        position = newPosition.toInt()
        return this
    }

    override fun size(): Long = size.toLong()

    override fun truncate(newSize: Long): SeekableByteChannel {
        check()
        size = minOf(size, newSize.toInt())
        position = minOf(position, size)
        return this
    }

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }

    private fun check() {
        if (!open) throw ClosedChannelException()
    }
}
