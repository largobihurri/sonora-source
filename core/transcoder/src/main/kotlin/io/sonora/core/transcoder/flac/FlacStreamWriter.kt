package io.sonora.core.transcoder.flac

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Writes a native FLAC stream (RFC 9639) to [channel] from its current position: the `fLaC` marker, a `STREAMINFO`
 * placeholder, `VORBIS_COMMENT` ([vorbisComment], the tags), `PICTURE` ([picture], the front cover, when there is
 * one), then the encoder's frames as they come. [finish] writes the real `STREAMINFO` over the placeholder and
 * truncates the channel after the last frame. The caller owns and closes [channel].
 */
class FlacStreamWriter(
    private val channel: SeekableByteChannel,
    vorbisComment: ByteArray,
    picture: ByteArray?,
) {
    private val start = channel.position()
    private var finished = false

    init {
        require(vorbisComment.size <= MAX_BLOCK_BYTES) { "Tags of ${vorbisComment.size} bytes exceed a FLAC metadata block" }
        require(picture == null || picture.size <= MAX_BLOCK_BYTES) { "Picture of ${picture?.size} bytes exceeds a FLAC metadata block" }
        writeFully(ByteBuffer.wrap(MARKER))
        writeFully(block(STREAMINFO, ByteArray(FlacStreamInfo.BODY_BYTES), last = false))
        writeFully(block(VORBIS_COMMENT, vorbisComment, last = picture == null))
        picture?.let { writeFully(block(PICTURE, it, last = true)) }
    }

    /** Appends encoded frames as the encoder gave them. */
    fun writeFrame(frame: ByteArray) {
        check(!finished) { "FLAC stream already finished" }
        writeFully(ByteBuffer.wrap(frame))
    }

    /** Writes [info] with [totalSamples] over the placeholder and ends the stream. */
    fun finish(info: FlacStreamInfo, totalSamples: Long) {
        check(!finished) { "FLAC stream already finished" }
        finished = true
        val end = channel.position()
        channel.position(start + MARKER.size)
        writeFully(block(STREAMINFO, info.withTotalSamples(totalSamples), last = false))
        channel.position(end)
        channel.truncate(end)
    }

    private fun block(type: Int, body: ByteArray, last: Boolean): ByteBuffer {
        val buffer = ByteBuffer.allocate(4 + body.size)
        buffer.put((type or if (last) LAST_BLOCK else 0).toByte())
        buffer.put((body.size ushr 16).toByte()).put((body.size ushr 8).toByte()).put(body.size.toByte())
        buffer.put(body)
        buffer.flip()
        return buffer
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    internal companion object {
        val MARKER = "fLaC".toByteArray(Charsets.US_ASCII)
        private const val STREAMINFO = 0
        private const val VORBIS_COMMENT = 4
        private const val PICTURE = 6
        private const val LAST_BLOCK = 0x80

        /** Metadata block lengths are 24-bit. */
        const val MAX_BLOCK_BYTES = (1 shl 24) - 1
    }
}
