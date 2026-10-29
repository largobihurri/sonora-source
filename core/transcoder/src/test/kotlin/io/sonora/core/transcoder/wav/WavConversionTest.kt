package io.sonora.core.transcoder.wav

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.conversion.CoverArt
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.OutputTargets
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.model.AudioFormat
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.transcoder.ConversionEngine
import io.sonora.core.transcoder.FakePcmDecoder
import io.sonora.core.transcoder.FakePcmStream
import io.sonora.core.transcoder.MemoryChannel
import io.sonora.core.transcoder.MetadataChunkSource
import io.sonora.core.transcoder.encoder.EncoderRegistry
import io.sonora.core.transcoder.metadata.Id3MetadataChunkSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.wav.WavTag
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** WAV through the engine: samples, header and tags as JAudioTagger (and taggers) read them. */
class WavConversionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val source = "content://music/track.flac"
    private val wav16 = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.WAV, bitDepth = 16))
    private val wav24 = OutputTargets.profileFor(OutputTarget.Lossless(AudioFormat.WAV, bitDepth = 24))
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2_000) { it.toByte() }
    private val metadata = TrackMetadata(
        text = mapOf(
            MetadataTextField.TITLE to "Señorita",
            MetadataTextField.ARTIST to "Nadia",
            MetadataTextField.ALBUM to "Ibiza 2026",
            MetadataTextField.GENRE to "Deep House",
        ),
        date = "2026",
        trackNumber = 3,
        trackTotal = 12,
        cover = CoverArt(jpeg, "image/jpeg", 600, 600),
    )

    private suspend fun convert(
        stream: FakePcmStream,
        profile: io.sonora.core.domain.conversion.ConversionProfile,
        metadata: MetadataChunkSource = MetadataChunkSource.None,
    ): Pair<ByteArray, io.sonora.core.domain.model.ConversionResult> {
        val output = MemoryChannel()
        val result = ConversionEngine(FakePcmDecoder(mapOf(source to { stream })), EncoderRegistry.default(), metadata)
            .convert(source, profile, output)
        return output.bytes() to result
    }

    @Test
    fun `a 16-bit source passes through to 16-bit WAV bit-exact`() = runTest {
        val values = ShortArray(2_000) { ((it * 37) % 65_536 - 32_768).toShort() }
        val input = FloatArray(values.size) { values[it] / 32_768f }
        val (bytes, result) = convert(FakePcmStream(FakePcmStream.format(44_100, 2, PcmSampleFormat.INT16, 1_000), input), wav16)

        assertThat(result.plan.dither).isFalse()
        assertThat(result.outputFrames).isEqualTo(1_000)
        val data = ByteBuffer.wrap(bytes, 44, values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(ShortArray(values.size) { data.getShort() }).isEqualTo(values)
    }

    @Test
    fun `a 24-bit source passes through to 24-bit WAV bit-exact`() = runTest {
        val values = IntArray(1_000) { (it * 4_099) % 16_777_216 - 8_388_608 }
        val input = FloatArray(values.size) { (values[it] / 8_388_608.0).toFloat() }
        val (bytes, result) = convert(FakePcmStream(FakePcmStream.format(96_000, 1, PcmSampleFormat.INT24, 1_000), input), wav24)

        assertThat(result.plan.dither).isFalse()
        assertThat(result.plan.targetSampleRateHz).isEqualTo(96_000)
        val decoded = IntArray(values.size) {
            val at = 68 + it * 3
            (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at + 2].toInt() shl 16)
        }
        assertThat(decoded).isEqualTo(values)
    }

    @Test
    fun `tags and cover go into an id3 chunk and the basic ones into LIST INFO`() = runTest {
        val stream = FakePcmStream(FakePcmStream.format(48_000, 2, PcmSampleFormat.INT24, 480), FloatArray(960))
        val (bytes, result) = convert(stream, wav24, Id3MetadataChunkSource(SourceMetadataReader { metadata }))

        assertThat(result.tagsWritten).isTrue()
        TagOptionSingleton.getInstance().isAndroid = true
        val file = folder.newFile("out.wav").apply { writeBytes(bytes) }
        val audio = AudioFileIO.readAs(file, "wav")
        assertThat(audio.audioHeader.bitsPerSample).isEqualTo(24)
        assertThat(audio.audioHeader.sampleRateAsNumber).isEqualTo(48_000)
        val tag = audio.tag as WavTag
        assertThat(tag.isExistingId3Tag).isTrue()
        assertThat(tag.isExistingInfoTag).isTrue()
        val id3 = tag.iD3Tag
        assertThat(id3.getFirst(FieldKey.TITLE)).isEqualTo("Señorita")
        assertThat(id3.getFirst(FieldKey.ARTIST)).isEqualTo("Nadia")
        assertThat(id3.getFirst(FieldKey.TRACK)).isEqualTo("3")
        assertThat(id3.firstArtwork.binaryData).isEqualTo(jpeg)
        // JAudioTagger keeps the terminating NUL that RIFF ZSTR values carry.
        val info = tag.infoTag
        fun infoText(key: FieldKey) = info.getFirst(key).trimEnd('\u0000')
        assertThat(infoText(FieldKey.TITLE)).isEqualTo("Señorita")
        assertThat(infoText(FieldKey.ALBUM)).isEqualTo("Ibiza 2026")
        assertThat(infoText(FieldKey.GENRE)).isEqualTo("Deep House")
    }
}
