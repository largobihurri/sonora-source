package io.sonora.core.transcoder.flac

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.OutputContainer
import io.sonora.core.domain.conversion.OutputSampleFormat
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.OutputTargets
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.model.AudioFormat
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.domain.port.UnsupportedOutputException
import io.sonora.core.transcoder.ConversionEngine
import io.sonora.core.transcoder.FakePcmDecoder
import io.sonora.core.transcoder.FakePcmStream
import io.sonora.core.transcoder.MemoryChannel
import io.sonora.core.transcoder.MetadataChunkSource
import io.sonora.core.transcoder.encoder.EncoderRegistry
import io.sonora.core.transcoder.encoder.FlacEncoderFactory
import io.sonora.core.transcoder.encoder.FlacFrameCodecFactory
import io.sonora.core.transcoder.metadata.FlacMetadataChunkSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The engine with the FLAC encoder over a [FakeFlacCodec]: the real codec only runs on a device. */
class FlacConversionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val source = "content://music/track.wav"
    private val flac16 = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.FLAC, bitDepth = 16))
    private val flac24 = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.FLAC, bitDepth = 24))
    private var codec: FakeFlacCodec? = null

    /** Opens fake codecs that state [statedBits] whatever is asked (null: what is asked). */
    private fun registry(statedBits: Int? = null) = EncoderRegistry(
        listOf(OutputSampleFormat.FLAC_16, OutputSampleFormat.FLAC_24).associate { format ->
            (OutputContainer.FLAC to format) to FlacEncoderFactory(
                FlacFrameCodecFactory { rate, channels, bits ->
                    FakeFlacCodec(rate, channels, statedBits ?: bits, floatInput = bits == 24).also { codec = it }
                },
            )
        },
    )

    private suspend fun convert(
        stream: FakePcmStream,
        profile: ConversionProfile,
        metadata: MetadataChunkSource = MetadataChunkSource.None,
        statedBits: Int? = null,
    ): Pair<ByteArray, io.sonora.core.domain.model.ConversionResult> {
        val output = MemoryChannel()
        val result = ConversionEngine(FakePcmDecoder(mapOf(source to { stream })), registry(statedBits), metadata)
            .convert(source, profile, output)
        return output.bytes() to result
    }

    @Test
    fun `a 16-bit source reaches the codec bit-exact and the stream states its length`() = runTest {
        val values = ShortArray(20_000) { ((it * 37) % 65_536 - 32_768).toShort() }
        val input = FloatArray(values.size) { values[it] / 32_768f }
        val (bytes, result) = convert(FakePcmStream(FakePcmStream.format(44_100, 2, PcmSampleFormat.INT16, 10_000), input), flac16)

        assertThat(result.plan.dither).isFalse()
        assertThat(result.outputFrames).isEqualTo(10_000)
        val fed = codec!!.blocks.reduce(ByteArray::plus)
        val shorts = ByteBuffer.wrap(fed).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertThat(ShortArray(shorts.remaining()) { shorts.get() }).isEqualTo(values)
        assertThat(codec!!.closed).isTrue()
        val (blocks, frames) = FlacBlocks.parse(bytes)
        assertThat(blocks.map { it.type }).containsExactly(0, 4).inOrder()
        assertThat(FlacBlocks.totalSamples(blocks[0].body)).isEqualTo(10_000)
        assertThat(FlacBlocks.bitsPerSample(blocks[0].body)).isEqualTo(16)
        assertThat(bytes.size - frames).isEqualTo(codec!!.blocks.sumOf { it.size + 2 })
    }

    @Test
    fun `a 24-bit source reaches the codec as floats on the 24-bit grid`() = runTest {
        val values = IntArray(2_000) { (it * 4_099) % 16_777_216 - 8_388_608 }
        val input = FloatArray(values.size) { (values[it] / 8_388_608.0).toFloat() }
        val (bytes, result) = convert(FakePcmStream(FakePcmStream.format(96_000, 1, PcmSampleFormat.INT24, 2_000), input), flac24)

        assertThat(result.plan.dither).isFalse()
        val fed = ByteBuffer.wrap(codec!!.blocks.reduce(ByteArray::plus)).order(ByteOrder.nativeOrder()).asFloatBuffer()
        assertThat(IntArray(fed.remaining()) { Math.round(fed.get() * 8_388_608.0).toInt() }).isEqualTo(values)
        val (blocks, _) = FlacBlocks.parse(bytes)
        assertThat(FlacBlocks.bitsPerSample(blocks[0].body)).isEqualTo(24)
        assertThat(FlacBlocks.totalSamples(blocks[0].body)).isEqualTo(2_000)
    }

    @Test
    fun `a codec that would write 16 bits for a 24-bit profile fails the file with UnsupportedOutputException`() = runTest {
        val stream = FakePcmStream(FakePcmStream.format(96_000, 2, PcmSampleFormat.INT24, 1_000), FloatArray(2_000))

        val error = runCatching { convert(stream, flac24, statedBits = 16) }.exceptionOrNull()

        assertThat(error).isInstanceOf(UnsupportedOutputException::class.java)
        assertThat((error as UnsupportedOutputException).profile).isEqualTo(flac24)
        assertThat(error.message).contains("16-bit")
        assertThat(codec!!.closed).isTrue()
    }

    @Test
    fun `tags and the full-size cover go into VORBIS_COMMENT and PICTURE blocks`() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(3_000) { it.toByte() }
        val metadata = TrackMetadata(
            text = mapOf(
                MetadataTextField.TITLE to "Señorita",
                MetadataTextField.ARTIST to "Nadia",
                MetadataTextField.ALBUM_ARTIST to "Various Artists",
                MetadataTextField.ALBUM to "Ibiza 2026",
                MetadataTextField.GENRE to "Deep House",
                MetadataTextField.BPM to "124",
            ),
            date = "2026-05-07",
            trackNumber = 3, trackTotal = 12, discNumber = 1, discTotal = 2,
            cover = CoverArt(jpeg, "image/jpeg", 1_400, 1_400),
        )
        val stream = FakePcmStream(FakePcmStream.format(44_100, 2, PcmSampleFormat.INT16, 4_410), FloatArray(8_820))

        val (bytes, result) = convert(stream, flac16, FlacMetadataChunkSource(SourceMetadataReader { metadata }))

        assertThat(result.tagsWritten).isTrue()
        assertThat(FlacBlocks.parse(bytes).first.map { it.type }).containsExactly(0, 4, 6).inOrder()
        TagOptionSingleton.getInstance().isAndroid = true
        val file = folder.newFile("out.flac").apply { writeBytes(bytes) }
        val audio = AudioFileIO.read(file)
        assertThat(audio.audioHeader.sampleRateAsNumber).isEqualTo(44_100)
        val tag = audio.tag
        assertThat(tag.getFirst(FieldKey.TITLE)).isEqualTo("Señorita")
        assertThat(tag.getFirst(FieldKey.ARTIST)).isEqualTo("Nadia")
        assertThat(tag.getFirst(FieldKey.ALBUM_ARTIST)).isEqualTo("Various Artists")
        assertThat(tag.getFirst(FieldKey.ALBUM)).isEqualTo("Ibiza 2026")
        assertThat(tag.getFirst(FieldKey.GENRE)).isEqualTo("Deep House")
        assertThat(tag.getFirst(FieldKey.BPM)).isEqualTo("124")
        assertThat(tag.getFirst(FieldKey.YEAR)).isEqualTo("2026-05-07")
        assertThat(tag.getFirst(FieldKey.TRACK)).isEqualTo("3")
        assertThat(tag.getFirst(FieldKey.TRACK_TOTAL)).isEqualTo("12")
        assertThat(tag.getFirst(FieldKey.DISC_NO)).isEqualTo("1")
        assertThat(tag.getFirst(FieldKey.ENCODER)).isEqualTo("Sonora (FLAC)")
        assertThat(tag.firstArtwork.binaryData).isEqualTo(jpeg)
        assertThat(tag.firstArtwork.width).isEqualTo(1_400)
    }
}
