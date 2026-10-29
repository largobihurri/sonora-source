package io.sonora.core.transcoder.aiff

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.domain.audio.AiffSource
import io.sonora.core.domain.audio.PcmLayout
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.OutputTargets
import io.sonora.core.domain.model.AudioFormat
import io.sonora.core.transcoder.ConversionEngine
import io.sonora.core.transcoder.FakePcmDecoder
import io.sonora.core.transcoder.FakePcmStream
import io.sonora.core.transcoder.MemoryChannel
import io.sonora.core.transcoder.MetadataChunk
import io.sonora.core.transcoder.MetadataChunkSource
import io.sonora.core.transcoder.encoder.EncoderRegistry
import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 24-bit AIFF: the writer's layout and the engine's AIFF 24-bit profiles. */
class Aiff24Test {

    private val source = "content://music/track.flac"
    private val aiff24 = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.AIFF, bitDepth = 24))
    private fun parse(bytes: ByteArray) = AiffParser.parse(AiffSource.of(ByteArrayInputStream(bytes)))

    @Test
    fun `a mono 24-bit file with an odd frame count pads its sound data and parses back`() {
        val channel = MemoryChannel()
        val writer = AiffWriter(channel, channelCount = 1, sampleRateHz = 96_000, bitsPerSample = 24)
        val samples = byteArrayOf(0x12, 0x34, 0x56, -1, -1, -2, 0x7F, -1, -1)
        writer.writeSamples(samples, 0, samples.size)

        assertThat(writer.finish(listOf(MetadataChunk("ID3 ", byteArrayOf(1, 2))))).isEqualTo(3)

        val bytes = channel.bytes()
        val header = parse(bytes)
        assertThat(header.declaredBits).isEqualTo(24)
        assertThat(header.layout).isEqualTo(PcmLayout(PcmLayout.SampleKind.SIGNED_INT, 24, ByteOrder.BIG_ENDIAN))
        assertThat(header.frameCount).isEqualTo(3)
        assertThat(header.sampleRate).isEqualTo(96_000.0)
        assertThat(bytes.copyOfRange(header.dataStart.toInt(), (header.dataStart + header.dataLength).toInt())).isEqualTo(samples)
        // SSND (8 + 8 + 9 bytes) is followed by one pad byte, then the ID3 chunk.
        val pad = AiffWriter.HEADER_BYTES + samples.size
        assertThat(bytes[pad]).isEqualTo(0)
        assertThat(String(bytes, pad + 1, 4, Charsets.US_ASCII)).isEqualTo("ID3 ")
        assertThat(bytes.size).isEqualTo(pad + 1 + 8 + 2)
        // FORM size counts everything after its first 8 bytes.
        assertThat(java.nio.ByteBuffer.wrap(bytes, 4, 4).int).isEqualTo(bytes.size - 8)
    }

    @Test
    fun `a 24-bit source converts to 24-bit AIFF bit-exact and without dither`() = runTest {
        val values = IntArray(2_000) { (it * 4_099) % 16_777_216 - 8_388_608 }
        val input = FloatArray(values.size) { (values[it] / 8_388_608.0).toFloat() }
        val stream = FakePcmStream(FakePcmStream.format(48_000, 2, PcmSampleFormat.INT24, 1_000), input)
        val output = MemoryChannel()

        val result = engine(stream).convert(source, aiff24, output)

        assertThat(result.plan.dither).isFalse()
        assertThat(result.plan.targetBitsPerSample).isEqualTo(24)
        assertThat(result.outputFrames).isEqualTo(1_000)
        val bytes = output.bytes()
        val header = parse(bytes)
        assertThat(header.declaredBits).isEqualTo(24)
        val data = bytes.copyOfRange(header.dataStart.toInt(), (header.dataStart + header.dataLength).toInt())
        val decoded = IntArray(values.size) {
            (data[it * 3].toInt() shl 16) or ((data[it * 3 + 1].toInt() and 0xFF) shl 8) or (data[it * 3 + 2].toInt() and 0xFF)
        }
        assertThat(decoded).isEqualTo(values)
    }

    @Test
    fun `a 16-bit source widens to 24 bits without dither, a float source is dithered`() = runTest {
        val sixteen = FakePcmStream(FakePcmStream.format(44_100, 1, PcmSampleFormat.INT16, 10), FloatArray(10) { (it - 5) / 32_768f })
        val float = FakePcmStream(FakePcmStream.format(44_100, 1, PcmSampleFormat.FLOAT32, 10), FloatArray(10) { it / 10f })

        val widened = MemoryChannel()
        assertThat(engine(sixteen).convert(source, aiff24, widened).plan.dither).isFalse()
        assertThat(engine(float).convert(source, aiff24, MemoryChannel()).plan.dither).isTrue()

        val bytes = widened.bytes()
        val start = parse(bytes).dataStart.toInt()
        // -5 / 32768 is -1280 in 24 bits: 0xFFFB00.
        assertThat(bytes.copyOfRange(start, start + 3)).isEqualTo(byteArrayOf(-1, -5, 0))
    }

    private fun engine(stream: FakePcmStream) =
        ConversionEngine(FakePcmDecoder(mapOf(source to { stream })), EncoderRegistry.default(), MetadataChunkSource.None)
}
