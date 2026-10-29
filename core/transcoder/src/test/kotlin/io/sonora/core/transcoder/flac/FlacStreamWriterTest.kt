package io.sonora.core.transcoder.flac

import com.google.common.truth.Truth.assertThat
import io.sonora.core.transcoder.MemoryChannel
import java.nio.ByteBuffer
import org.junit.Test

class FlacStreamWriterTest {

    private val info = FlacBlocks.streamInfo(44_100, 2, 16)

    @Test
    fun `the stream is the marker, STREAMINFO, the tags, the picture and the frames`() {
        val channel = MemoryChannel()
        val writer = FlacStreamWriter(channel, vorbisComment = byteArrayOf(1, 2, 3), picture = byteArrayOf(4, 5))
        writer.writeFrame(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 7))
        writer.writeFrame(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 8))
        writer.finish(FlacStreamInfo(info), totalSamples = 1_234)

        val bytes = channel.bytes()
        val (blocks, frames) = FlacBlocks.parse(bytes)
        assertThat(blocks.map { it.type }).containsExactly(0, 4, 6).inOrder()
        assertThat(blocks.map { it.last }).containsExactly(false, false, true).inOrder()
        assertThat(FlacBlocks.totalSamples(blocks[0].body)).isEqualTo(1_234)
        assertThat(blocks[1].body).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(blocks[2].body).isEqualTo(byteArrayOf(4, 5))
        assertThat(bytes.copyOfRange(frames, bytes.size)).isEqualTo(byteArrayOf(-1, -8, 7, -1, -8, 8))
    }

    @Test
    fun `without a picture the tags are the last block`() {
        val channel = MemoryChannel()
        FlacStreamWriter(channel, vorbisComment = byteArrayOf(1), picture = null).finish(FlacStreamInfo(info), totalSamples = 0)

        val (blocks, frames) = FlacBlocks.parse(channel.bytes())
        assertThat(blocks.map { it.type to it.last }).containsExactly(0 to false, 4 to true).inOrder()
        assertThat(frames).isEqualTo(channel.bytes().size)
    }

    @Test
    fun `an output that held a longer file is truncated`() {
        val channel = MemoryChannel()
        channel.write(ByteBuffer.wrap(ByteArray(500)))
        channel.position(0)
        FlacStreamWriter(channel, vorbisComment = byteArrayOf(1), picture = null).finish(FlacStreamInfo(info), totalSamples = 0)

        assertThat(channel.bytes().size).isEqualTo(4 + 4 + 34 + 4 + 1)
    }
}
