package io.sonora.core.transcoder.wav

import io.sonora.core.transcoder.MetadataChunk
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel

/**
 * Writes a RIFF WAVE file of 16- or 24-bit little-endian PCM to [channel], starting at its current position: RIFF/WAVE,
 * `fmt `, `data`, then any metadata chunks (`LIST`, `id3 `), each padded to an even size. 16-bit files carry a plain
 * `WAVE_FORMAT_PCM` header; 24-bit ones `WAVE_FORMAT_EXTENSIBLE` with the valid bits and the speaker mask, which
 * Microsoft requires for samples wider than 16 bits. The sizes go out as zero first and are patched in [finish]; the
 * channel is truncated after the last chunk. The caller owns and closes [channel].
 */
class WavWriter(
    private val channel: SeekableByteChannel,
    val channelCount: Int,
    val sampleRateHz: Int,
    val bitsPerSample: Int,
) {
    private val start: Long
    private val bytesPerFrame = channelCount * (bitsPerSample / 8)
    private val extensible = bitsPerSample > 16
    private val headerBytes = if (extensible) EXTENSIBLE_HEADER_BYTES else PCM_HEADER_BYTES
    private var dataBytes = 0L
    private var finished = false

    init {
        require(channelCount in 1..2) { "Unsupported channel count $channelCount" }
        require(sampleRateHz > 0) { "Unsupported sample rate $sampleRateHz" }
        require(bitsPerSample == 16 || bitsPerSample == 24) { "Unsupported sample size $bitsPerSample" }
        start = channel.position()
        writeFully(header(trailingBytes = 0))
    }

    /** Appends [length] bytes of interleaved little-endian frames from [bytes] at [offset]: whole frames only. */
    fun writeSamples(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "WAV already finished" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "Range $offset+$length outside ${bytes.size} bytes" }
        require(length % bytesPerFrame == 0) { "$length bytes are not whole frames of $bytesPerFrame bytes" }
        if (headerBytes + dataBytes + length > MAX_RIFF_BYTES) throw IOException("Output exceeds the 4 GiB WAV limit")
        writeFully(ByteBuffer.wrap(bytes, offset, length))
        dataBytes += length
    }

    /** Writes [trailingChunks] after the sound data, patches the sizes and returns the frames written. */
    fun finish(trailingChunks: List<MetadataChunk> = emptyList()): Long {
        check(!finished) { "WAV already finished" }
        finished = true
        // The data chunk's size does not count its pad byte; the RIFF size does.
        val dataPad = dataBytes and 1
        if (dataPad == 1L) writeFully(ByteBuffer.wrap(ByteArray(1)))
        var trailing = dataPad
        for (chunk in trailingChunks) {
            val size = chunk.data.size
            val pad = size and 1
            val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            head.put(chunk.id.toByteArray(Charsets.US_ASCII)).putInt(size)
            head.flip()
            writeFully(head)
            writeFully(ByteBuffer.wrap(chunk.data))
            if (pad == 1) writeFully(ByteBuffer.wrap(ByteArray(1)))
            trailing += 8L + size + pad
            if (headerBytes + dataBytes + trailing > MAX_RIFF_BYTES) throw IOException("Output exceeds the 4 GiB WAV limit")
        }
        val end = channel.position()
        channel.position(start)
        writeFully(header(trailingBytes = trailing))
        channel.position(end)
        channel.truncate(end)
        return dataBytes / bytesPerFrame
    }

    private fun header(trailingBytes: Long): ByteBuffer {
        val buffer = ByteBuffer.allocate(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val riffSize = headerBytes - 8 + dataBytes + trailingBytes
        buffer.put(RIFF).putInt(riffSize.toInt()).put(WAVE)
        buffer.put(FMT).putInt(if (extensible) 40 else 16)
            .putShort((if (extensible) WAVE_FORMAT_EXTENSIBLE else WAVE_FORMAT_PCM).toShort())
            .putShort(channelCount.toShort())
            .putInt(sampleRateHz)
            .putInt(sampleRateHz * bytesPerFrame)
            .putShort(bytesPerFrame.toShort())
            .putShort(bitsPerSample.toShort())
        if (extensible) {
            buffer.putShort(EXTENSION_BYTES.toShort())
                .putShort(bitsPerSample.toShort())
                .putInt(if (channelCount == 1) SPEAKER_FRONT_CENTER else SPEAKER_FRONT_LEFT or SPEAKER_FRONT_RIGHT)
                .put(SUBTYPE_PCM)
        }
        buffer.put(DATA).putInt(dataBytes.toInt())
        buffer.flip()
        return buffer
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    internal companion object {
        /** RIFF header (12) + `fmt ` (8 + 16) + `data` header (8). */
        const val PCM_HEADER_BYTES = 44

        /** RIFF header (12) + `fmt ` (8 + 40) + `data` header (8). */
        const val EXTENSIBLE_HEADER_BYTES = 68

        private const val WAVE_FORMAT_PCM = 1
        private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
        private const val EXTENSION_BYTES = 22
        private const val SPEAKER_FRONT_LEFT = 0x1
        private const val SPEAKER_FRONT_RIGHT = 0x2
        private const val SPEAKER_FRONT_CENTER = 0x4

        /** RIFF sizes are unsigned 32-bit and exclude the first 8 bytes. */
        private const val MAX_RIFF_BYTES = 0xFFFF_FFFFL + 8

        private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
        private val FMT = "fmt ".toByteArray(Charsets.US_ASCII)
        private val DATA = "data".toByteArray(Charsets.US_ASCII)

        /** KSDATAFORMAT_SUBTYPE_PCM, 00000001-0000-0010-8000-00AA00389B71, in its GUID byte layout. */
        private val SUBTYPE_PCM = byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)
    }
}
