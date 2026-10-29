package io.sonora.core.watchsync.protocol

import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.SyncLimits
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * One unit on the wire: a 1-byte kind (1 control, 2 data), a 4-byte big-endian length, then that many bytes. A control
 * frame holds one JSON [SyncMessage]; a data frame holds a slice of the song being transferred.
 */
sealed interface Frame {
    data class Control(val message: SyncMessage) : Frame

    class Data(val bytes: ByteArray) : Frame
}

private const val KIND_CONTROL = 1
private const val KIND_DATA = 2

/** Writes frames. One writer per stream, used by one thread at a time. */
class FrameWriter(private val output: OutputStream) {
    private val header = ByteArray(5)

    /** Writes and flushes [message], so the other side sees it at once. */
    fun writeControl(message: SyncMessage) {
        val payload = SyncMessageCodec.encode(message)
        require(payload.size <= SyncLimits.MAX_CONTROL_FRAME_BYTES) { "Message too big: ${payload.size} bytes" }
        writeFrame(KIND_CONTROL, payload, 0, payload.size)
        output.flush()
    }

    /** Writes a slice of a song; not flushed (see [flush]). */
    fun writeData(buffer: ByteArray, offset: Int, length: Int) {
        require(length <= SyncLimits.MAX_DATA_FRAME_BYTES) { "Data frame too big: $length bytes" }
        writeFrame(KIND_DATA, buffer, offset, length)
    }

    fun flush() = output.flush()

    private fun writeFrame(kind: Int, buffer: ByteArray, offset: Int, length: Int) {
        header[0] = kind.toByte()
        header[1] = (length ushr 24).toByte()
        header[2] = (length ushr 16).toByte()
        header[3] = (length ushr 8).toByte()
        header[4] = length.toByte()
        output.write(header)
        output.write(buffer, offset, length)
    }
}

/** Reads frames. One reader per stream, used by one thread at a time. */
class FrameReader(private val input: InputStream) {
    private val header = ByteArray(5)

    /**
     * The next frame, or `null` when the stream ended cleanly between two frames.
     *
     * @throws EOFException when the stream ends inside a frame.
     * @throws ProtocolException when the bytes are not a frame.
     */
    fun read(): Frame? {
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        readFully(header, 1, 4)
        val length = ((header[1].toInt() and 0xFF) shl 24) or ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
        return when (first) {
            KIND_CONTROL -> {
                checkLength(length, SyncLimits.MAX_CONTROL_FRAME_BYTES)
                Frame.Control(SyncMessageCodec.decode(readPayload(length)))
            }
            KIND_DATA -> {
                checkLength(length, SyncLimits.MAX_DATA_FRAME_BYTES)
                Frame.Data(readPayload(length))
            }
            else -> throw ProtocolException("Unknown frame kind $first")
        }
    }

    private fun checkLength(length: Int, max: Int) {
        if (length < 0 || length > max) throw ProtocolException("Frame length $length is outside 0..$max")
    }

    private fun readPayload(length: Int): ByteArray = ByteArray(length).also { readFully(it, 0, length) }

    private fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val read = input.read(buffer, offset + done, length - done)
            if (read < 0) throw EOFException("Stream ended inside a frame")
            done += read
        }
    }
}
