package io.sonora.core.transcoder.wav

import com.google.common.truth.Truth.assertThat
import io.sonora.core.transcoder.MemoryChannel
import io.sonora.core.transcoder.MetadataChunk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class WavWriterTest {

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun ascii(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)

    private fun write(channels: Int, rate: Int, bits: Int, samples: ByteArray, chunks: List<MetadataChunk> = emptyList()): ByteArray {
        val channel = MemoryChannel()
        val writer = WavWriter(channel, channels, rate, bits)
        writer.writeSamples(samples, 0, samples.size)
        assertThat(writer.finish(chunks)).isEqualTo(samples.size / (bits / 8L * channels))
        return channel.bytes()
    }

    @Test
    fun `16-bit stereo is a plain PCM fmt chunk followed by the data`() {
        val samples = byteArrayOf(1, 0, -1, -1, 0, 0x7F, 0, -128)
        val bytes = write(channels = 2, rate = 44_100, bits = 16, samples = samples)
        val b = le(bytes)

        assertThat(ascii(bytes, 0)).isEqualTo("RIFF")
        assertThat(b.getInt(4)).isEqualTo(bytes.size - 8)
        assertThat(ascii(bytes, 8)).isEqualTo("WAVE")
        assertThat(ascii(bytes, 12)).isEqualTo("fmt ")
        assertThat(b.getInt(16)).isEqualTo(16)
        assertThat(b.getShort(20).toInt()).isEqualTo(1) // WAVE_FORMAT_PCM
        assertThat(b.getShort(22).toInt()).isEqualTo(2)
        assertThat(b.getInt(24)).isEqualTo(44_100)
        assertThat(b.getInt(28)).isEqualTo(44_100 * 4)
        assertThat(b.getShort(32).toInt()).isEqualTo(4)
        assertThat(b.getShort(34).toInt()).isEqualTo(16)
        assertThat(ascii(bytes, 36)).isEqualTo("data")
        assertThat(b.getInt(40)).isEqualTo(samples.size)
        assertThat(bytes.copyOfRange(44, 44 + samples.size)).isEqualTo(samples)
        assertThat(bytes.size).isEqualTo(44 + samples.size)
    }

    @Test
    fun `24-bit mono uses WAVE_FORMAT_EXTENSIBLE and pads an odd data chunk`() {
        val samples = byteArrayOf(0x56, 0x34, 0x12, -2, -1, -1, 0, 0, 0x7F)
        val bytes = write(channels = 1, rate = 96_000, bits = 24, samples = samples, chunks = listOf(MetadataChunk("id3 ", byteArrayOf(9, 8, 7))))
        val b = le(bytes)

        assertThat(b.getInt(16)).isEqualTo(40)
        assertThat(b.getShort(20).toInt() and 0xFFFF).isEqualTo(0xFFFE)
        assertThat(b.getShort(22).toInt()).isEqualTo(1)
        assertThat(b.getInt(24)).isEqualTo(96_000)
        assertThat(b.getInt(28)).isEqualTo(96_000 * 3)
        assertThat(b.getShort(32).toInt()).isEqualTo(3)
        assertThat(b.getShort(34).toInt()).isEqualTo(24)
        assertThat(b.getShort(36).toInt()).isEqualTo(22) // cbSize
        assertThat(b.getShort(38).toInt()).isEqualTo(24) // valid bits
        assertThat(b.getInt(40)).isEqualTo(0x4) // front centre
        // KSDATAFORMAT_SUBTYPE_PCM: 00000001-0000-0010-8000-00AA00389B71.
        assertThat(bytes.copyOfRange(44, 60)).isEqualTo(
            byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, -128, 0, 0, -86, 0, 0x38, -101, 0x71),
        )
        assertThat(ascii(bytes, 60)).isEqualTo("data")
        assertThat(b.getInt(64)).isEqualTo(9)
        assertThat(bytes.copyOfRange(68, 77)).isEqualTo(samples)
        assertThat(bytes[77]).isEqualTo(0)
        assertThat(ascii(bytes, 78)).isEqualTo("id3 ")
        assertThat(b.getInt(82)).isEqualTo(3)
        assertThat(bytes.copyOfRange(86, 89)).isEqualTo(byteArrayOf(9, 8, 7))
        assertThat(bytes[89]).isEqualTo(0)
        assertThat(bytes.size).isEqualTo(90)
        assertThat(b.getInt(4)).isEqualTo(bytes.size - 8)
    }

    @Test
    fun `24-bit stereo declares the front left and right speakers`() {
        val bytes = write(channels = 2, rate = 48_000, bits = 24, samples = ByteArray(6))
        assertThat(le(bytes).getInt(40)).isEqualTo(0x3)
    }

    @Test
    fun `an output that held a longer file is truncated`() {
        val channel = MemoryChannel()
        channel.write(ByteBuffer.wrap(ByteArray(500)))
        channel.position(0)
        val writer = WavWriter(channel, 1, 8_000, 16)
        writer.writeSamples(byteArrayOf(1, 2), 0, 2)
        writer.finish()

        assertThat(channel.bytes().size).isEqualTo(46)
    }

    @Test
    fun `misuse is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WavWriter(MemoryChannel(), 0, 44_100, 16) }
        assertThrows(IllegalArgumentException::class.java) { WavWriter(MemoryChannel(), 2, 44_100, 20) }
        val writer = WavWriter(MemoryChannel(), 2, 44_100, 16)
        assertThrows(IllegalArgumentException::class.java) { writer.writeSamples(ByteArray(6), 0, 6) }
        writer.finish()
        assertThrows(IllegalStateException::class.java) { writer.finish() }
    }
}
