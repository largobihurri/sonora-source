package io.sonora.core.transcoder.aiff

import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.transcoder.MetadataChunk
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel

/**
 * Writes a plain AIFF (Audio IFF 1.3) file of 16- or 24-bit big-endian PCM to [channel], starting at its current
 * position: FORM/AIFF, COMM, SSND (offset 0, block size 0; padded to an even size when 24-bit mono leaves it odd), then
 * any metadata chunks, each padded to an even size. The header
 * goes out first with zero sizes and is patched with the real frame count in [finish], so the total length need not
 * be known in advance; the channel is truncated after the last chunk. The caller owns and closes [channel].
 */
class AiffWriter(
    private val channel: SeekableByteChannel,
    val channelCount: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int = 16,
) {
    private val start: Long
    private val bytesPerFrame = channelCount * (bitsPerSample / 8)
    private var dataBytes = 0L
    private var finished = false

    init {
        require(channelCount in 1..AiffParser.MAX_CHANNELS) { "Unsupported channel count $channelCount" }
        require(sampleRateHz in 1..AiffParser.MAX_SAMPLE_RATE_HZ) { "Unsupported sample rate $sampleRateHz" }
        require(bitsPerSample == 16 || bitsPerSample == 24) { "Unsupported sample size $bitsPerSample" }
        start = channel.position()
        writeFully(header(frames = 0, trailingBytes = 0))
    }

    /** Appends [length] bytes of interleaved big-endian frames from [bytes] at [offset]: whole frames only. */
    fun writeSamples(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "AIFF already finished" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "Range $offset+$length outside ${bytes.size} bytes" }
        require(length % bytesPerFrame == 0) { "$length bytes are not whole frames of $bytesPerFrame bytes" }
        if (FIXED_BYTES + dataBytes + length > MAX_FORM_BYTES) throw IOException("Output exceeds the 4 GiB AIFF limit")
        writeFully(ByteBuffer.wrap(bytes, offset, length))
        dataBytes += length
    }

    /** Writes [trailingChunks] after the sound data, patches the header and returns the frames written. */
    fun finish(trailingChunks: List<MetadataChunk> = emptyList()): Long {
        check(!finished) { "AIFF already finished" }
        finished = true
        // An odd SSND chunk (24-bit mono, odd frame count) takes a pad byte that its size does not count.
        val soundPad = dataBytes and 1
        if (soundPad == 1L) writeFully(ByteBuffer.wrap(ByteArray(1)))
        var trailing = soundPad
        for (chunk in trailingChunks) {
            val size = chunk.data.size
            val pad = size and 1
            val head = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            head.put(chunk.id.toByteArray(Charsets.US_ASCII)).putInt(size)
            head.flip()
            writeFully(head)
            writeFully(ByteBuffer.wrap(chunk.data))
            if (pad == 1) writeFully(ByteBuffer.wrap(ByteArray(1)))
            trailing += 8L + size + pad
            if (FIXED_BYTES + dataBytes + trailing > MAX_FORM_BYTES) throw IOException("Output exceeds the 4 GiB AIFF limit")
        }
        val end = channel.position()
        channel.position(start)
        writeFully(header(frames = dataBytes / bytesPerFrame, trailingBytes = trailing))
        channel.position(end)
        channel.truncate(end)
        return dataBytes / bytesPerFrame
    }

    private fun header(frames: Long, trailingBytes: Long): ByteBuffer {
        val buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        // trailingBytes includes the sound data's pad byte, if any.
        val formSize = FIXED_BYTES - 8 + dataBytes + trailingBytes
        buffer.put(FORM).putInt(formSize.toInt()).put(AIFF)
        buffer.put(COMM).putInt(COMM_SIZE)
            .putShort(channelCount.toShort())
            .putInt(frames.toInt())
            .putShort(bitsPerSample.toShort())
            .put(extended(sampleRateHz))
        buffer.put(SSND).putInt((8 + dataBytes).toInt()).putInt(0).putInt(0)
        buffer.flip()
        return buffer
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    internal companion object {
        private const val COMM_SIZE = 18

        /** FORM header (12) + COMM chunk (8 + 18) + SSND header (8 + 8). */
        const val HEADER_BYTES = 54

        /** Bytes outside the sound data and trailing chunks, counting the FORM header itself. */
        private const val FIXED_BYTES = HEADER_BYTES.toLong()

        /** FORM sizes are unsigned 32-bit and exclude the first 8 bytes. */
        private const val MAX_FORM_BYTES = 0xFFFF_FFFFL + 8

        private val FORM = "FORM".toByteArray(Charsets.US_ASCII)
        private val AIFF = "AIFF".toByteArray(Charsets.US_ASCII)
        private val COMM = "COMM".toByteArray(Charsets.US_ASCII)
        private val SSND = "SSND".toByteArray(Charsets.US_ASCII)

        /** A positive integer as an 80-bit IEEE 754 extended float: the inverse of the parser's reader. */
        fun extended(value: Int): ByteArray {
            require(value > 0) { "Only positive rates: $value" }
            val exponent = 31 - Integer.numberOfLeadingZeros(value)
            val mantissa = value.toLong() shl (63 - exponent) // explicit integer bit at the top
            val bytes = ByteArray(10)
            val biased = 16_383 + exponent
            bytes[0] = (biased shr 8).toByte()
            bytes[1] = biased.toByte()
            for (i in 0 until 8) bytes[2 + i] = (mantissa ushr (56 - 8 * i)).toByte()
            return bytes
        }
    }
}
