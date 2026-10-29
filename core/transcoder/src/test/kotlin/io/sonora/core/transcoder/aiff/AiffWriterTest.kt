package io.sonora.core.transcoder.aiff

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.domain.audio.AiffSource
import io.sonora.core.domain.audio.PcmLayout
import io.sonora.core.transcoder.MemoryChannel
import io.sonora.core.transcoder.MetadataChunk
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class AiffWriterTest {

    private fun parse(bytes: ByteArray) = AiffParser.parse(AiffSource.of(ByteArrayInputStream(bytes)))

    private fun shortsBigEndian(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()

    private fun write(channels: Int, rate: Int, samples: ByteArray, chunks: List<MetadataChunk> = emptyList()): ByteArray {
        val channel = MemoryChannel()
        val writer = AiffWriter(channel, channels, rate)
        writer.writeSamples(samples, 0, samples.size)
        assertThat(writer.finish(chunks)).isEqualTo(samples.size / (2L * channels))
        return channel.bytes()
    }

    @Test
    fun `a stereo file parses back with its format and samples`() {
        val samples = shortsBigEndian(1, -1, 32_767, -32_768, 1_000, -1_000)
        val bytes = write(channels = 2, rate = 44_100, samples = samples)

        val header = parse(bytes)
        assertThat(header.isAifc).isFalse()
        assertThat(header.channelCount).isEqualTo(2)
        assertThat(header.frameCount).isEqualTo(3)
        assertThat(header.declaredBits).isEqualTo(16)
        assertThat(header.sampleRate).isEqualTo(44_100.0)
        assertThat(header.layout).isEqualTo(PcmLayout(PcmLayout.SampleKind.SIGNED_INT, 16, ByteOrder.BIG_ENDIAN))
        assertThat(header.blockSize).isEqualTo(0)
        assertThat(bytes.copyOfRange(header.dataStart.toInt(), (header.dataStart + header.dataLength).toInt())).isEqualTo(samples)
    }

    @Test
    fun `the header holds the exact FORM, COMM and SSND fields`() {
        val bytes = write(channels = 1, rate = 44_100, samples = shortsBigEndian(5, 6))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        assertThat(String(bytes, 0, 4, Charsets.US_ASCII)).isEqualTo("FORM")
        assertThat(b.getInt(4)).isEqualTo(bytes.size - 8)
        assertThat(String(bytes, 8, 4, Charsets.US_ASCII)).isEqualTo("AIFF")
        assertThat(String(bytes, 12, 4, Charsets.US_ASCII)).isEqualTo("COMM")
        assertThat(b.getInt(16)).isEqualTo(18)
        assertThat(b.getShort(20).toInt()).isEqualTo(1) // channels
        assertThat(b.getInt(22)).isEqualTo(2) // frames
        assertThat(b.getShort(26).toInt()).isEqualTo(16) // bits
        // 44100 Hz as an 80-bit extended float.
        assertThat(bytes.copyOfRange(28, 38)).isEqualTo(byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0))
        assertThat(String(bytes, 38, 4, Charsets.US_ASCII)).isEqualTo("SSND")
        assertThat(b.getInt(42)).isEqualTo(8 + 4)
        assertThat(b.getInt(46)).isEqualTo(0) // offset
        assertThat(b.getInt(50)).isEqualTo(0) // block size
        assertThat(bytes.size).isEqualTo(54 + 4)
    }

    @Test
    fun `sample rates survive the 80-bit extended encoding`() {
        for (rate in listOf(8_000, 11_025, 22_050, 32_000, 44_100, 48_000, 96_000, 192_000, 768_000)) {
            assertThat(parse(write(1, rate, shortsBigEndian(0))).sampleRate).isEqualTo(rate.toDouble())
        }
    }

    @Test
    fun `metadata chunks follow the sound data, padded to even sizes`() {
        val samples = shortsBigEndian(1, 2, 3)
        val bytes = write(1, 48_000, samples, listOf(MetadataChunk("ID3 ", byteArrayOf(9, 8, 7)), MetadataChunk("COMT", byteArrayOf(1, 2))))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val id3At = 54 + samples.size
        assertThat(String(bytes, id3At, 4, Charsets.US_ASCII)).isEqualTo("ID3 ")
        assertThat(b.getInt(id3At + 4)).isEqualTo(3)
        assertThat(bytes.copyOfRange(id3At + 8, id3At + 11)).isEqualTo(byteArrayOf(9, 8, 7))
        assertThat(bytes[id3At + 11]).isEqualTo(0.toByte()) // pad
        val comtAt = id3At + 12
        assertThat(String(bytes, comtAt, 4, Charsets.US_ASCII)).isEqualTo("COMT")
        assertThat(bytes.size).isEqualTo(comtAt + 10)
        assertThat(b.getInt(4)).isEqualTo(bytes.size - 8)
        assertThat(parse(bytes).frameCount).isEqualTo(3)
    }

    @Test
    fun `an output that held a longer file is truncated`() {
        val channel = MemoryChannel()
        channel.write(ByteBuffer.wrap(ByteArray(500) { 1 }))
        channel.position(0)
        val writer = AiffWriter(channel, 1, 44_100)
        writer.writeSamples(shortsBigEndian(4), 0, 2)
        writer.finish()

        assertThat(channel.size()).isEqualTo(56)
    }

    @Test
    fun `misuse is rejected`() {
        val writer = AiffWriter(MemoryChannel(), 2, 44_100)
        assertThrows(IllegalArgumentException::class.java) { writer.writeSamples(ByteArray(6), 0, 6) }
        assertThrows(IllegalArgumentException::class.java) { MetadataChunk("ID3", ByteArray(0)) }
        writer.finish()
        assertThrows(IllegalStateException::class.java) { writer.finish() }
        assertThrows(IllegalStateException::class.java) { writer.writeSamples(ByteArray(4), 0, 4) }
        assertThrows(IllegalArgumentException::class.java) { AiffWriter(MemoryChannel(), 0, 44_100) }
    }
}
