package io.sonora.core.watchsync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One direction of an in-memory connection: bytes written to [output] come out of [input]. Closing either end ends the
 * pipe (the reader gets what was written first, then the end of the stream). With [cutAfterBytes], the write that crosses
 * that many bytes delivers only the part below the limit, closes the pipe and fails, like a connection that drops.
 */
class BytePipe(private val cutAfterBytes: Long = Long.MAX_VALUE) {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private val end = ByteArray(0)

    @Volatile
    private var closed = false
    private var current: ByteArray? = null
    private var position = 0
    private var written = 0L

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("pipe closed")
            val allowed = minOf(len.toLong(), cutAfterBytes - written).toInt()
            if (allowed > 0) {
                chunks.put(b.copyOfRange(off, off + allowed))
                written += allowed
            }
            if (allowed < len) {
                close()
                throw IOException("pipe cut after $cutAfterBytes bytes")
            }
        }

        override fun close() = this@BytePipe.close()
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var chunk = current
            if (chunk == null || position >= chunk.size) {
                chunk = nextChunk() ?: return -1
                current = chunk
                position = 0
            }
            val count = minOf(len, chunk.size - position)
            System.arraycopy(chunk, position, b, off, count)
            position += count
            return count
        }

        override fun close() = this@BytePipe.close()
    }

    fun close() {
        closed = true
        chunks.offer(end)
    }

    private fun nextChunk(): ByteArray? {
        while (true) {
            val chunk = chunks.poll(10, TimeUnit.MILLISECONDS)
            if (chunk != null) return if (chunk === end) null else chunk
            if (closed && chunks.isEmpty()) return null
        }
    }
}

/** A connection between a phone and a watch in memory. [cutPhoneToWatchAfterBytes] drops it once that many bytes went across. */
class Duplex(cutPhoneToWatchAfterBytes: Long = Long.MAX_VALUE) {
    private val toWatch = BytePipe(cutPhoneToWatchAfterBytes)
    private val toPhone = BytePipe()

    private fun closeBoth() {
        toWatch.close()
        toPhone.close()
    }

    val phone = SyncStreams(toPhone.input, toWatch.output, ::closeBoth)
    val watch = SyncStreams(toWatch.input, toPhone.output, ::closeBoth)
}
