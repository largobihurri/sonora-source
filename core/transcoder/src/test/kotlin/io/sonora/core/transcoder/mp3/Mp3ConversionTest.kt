package io.sonora.core.transcoder.mp3

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.Mp3Mode
import io.sonora.core.domain.conversion.OutputContainer
import io.sonora.core.domain.conversion.OutputSampleFormat
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.OutputTargets
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.domain.port.SourceMetadataReader
import io.sonora.core.domain.port.UnsupportedOutputException
import io.sonora.core.transcoder.ConversionEngine
import io.sonora.core.transcoder.FakePcmDecoder
import io.sonora.core.transcoder.FakePcmStream
import io.sonora.core.transcoder.MemoryChannel
import io.sonora.core.transcoder.MetadataChunkSource
import io.sonora.core.transcoder.encoder.EncoderRegistry
import io.sonora.core.transcoder.encoder.Mp3EncoderFactory
import io.sonora.core.transcoder.encoder.Mp3Stream
import io.sonora.core.transcoder.encoder.Mp3StreamFactory
import io.sonora.core.transcoder.encoder.Mp3StreamUnavailableException
import io.sonora.core.transcoder.metadata.Id3MetadataChunkSource
import io.sonora.core.transcoder.metadata.Id3v23Writer
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The engine with the MP3 encoder over a [FakeMp3Stream]: LAME itself only runs on a device. */
class Mp3ConversionTest {

    private val source = "content://music/track.flac"
    private val cbr = OutputTargets.profileFor(OutputTarget.Mp3(Mp3Mode.Cbr(320)))
    private val vbr = OutputTargets.profileFor(OutputTarget.Mp3(Mp3Mode.Vbr(2)))
    private val metadata = TrackMetadata(text = mapOf(MetadataTextField.TITLE to "Aria"))

    /** Turns every call into bytes of its own value; the info frame is [FRAME] bytes of 0x7E. */
    class FakeMp3Stream(val rate: Int, val channels: Int, val mode: io.sonora.core.mp3.Mp3Mode) : Mp3Stream {
        var frames = 0
        var closed = false
        override fun encode(pcm: FloatArray, frames: Int): ByteArray {
            this.frames += frames
            return ByteArray(frames / 10) { 0x11 }
        }
        override fun flush(): ByteArray = ByteArray(FRAME * 2) { 0x22 }
        override fun lametagFrame(): ByteArray = ByteArray(FRAME) { 0x7E }
        override fun close() {
            closed = true
        }

        companion object {
            const val FRAME = 417
        }
    }

    private var stream: FakeMp3Stream? = null

    private fun registry(factory: Mp3StreamFactory = Mp3StreamFactory { rate, channels, mode -> FakeMp3Stream(rate, channels, mode).also { stream = it } }) =
        EncoderRegistry(mapOf((OutputContainer.MP3 to OutputSampleFormat.MP3) to Mp3EncoderFactory(factory)))

    private suspend fun convert(
        profile: ConversionProfile,
        rate: Int = 44_100,
        metadata: MetadataChunkSource = MetadataChunkSource.None,
        factory: Mp3StreamFactory? = null,
    ): ByteArray {
        val output = MemoryChannel()
        val pcm = FakePcmStream(FakePcmStream.format(rate, 2, PcmSampleFormat.INT16, 4_410), FloatArray(8_820))
        val registry = if (factory == null) registry() else registry(factory)
        ConversionEngine(FakePcmDecoder(mapOf(source to { pcm })), registry, metadata).convert(source, profile, output)
        return output.bytes()
    }

    @Test
    fun `the ID3 tag comes first and the info frame replaces the stream's first frame`() = runTest {
        val bytes = convert(cbr, metadata = Id3MetadataChunkSource(SourceMetadataReader { metadata }))

        val tag = Id3v23Writer.write(metadata, "Sonora (MP3)")
        assertThat(bytes.copyOfRange(0, tag.size)).isEqualTo(tag)
        val audio = bytes.copyOfRange(tag.size, bytes.size)
        // 4,410 frames in blocks of up to 1,000: 100 + 100 + 100 + 100 + 41 bytes, then the flush.
        assertThat(audio.size).isEqualTo(441 + FakeMp3Stream.FRAME * 2)
        assertThat(audio.copyOfRange(0, FakeMp3Stream.FRAME).all { it == 0x7E.toByte() }).isTrue()
        assertThat(audio.copyOfRange(FakeMp3Stream.FRAME, audio.size).all { it == 0x11.toByte() || it == 0x22.toByte() }).isTrue()
        assertThat(stream!!.frames).isEqualTo(4_410)
        assertThat(stream!!.closed).isTrue()
    }

    @Test
    fun `CBR and VBR profiles reach LAME with their bitrate or quality`() = runTest {
        convert(cbr)
        assertThat(stream!!.mode).isEqualTo(io.sonora.core.mp3.Mp3Mode.Cbr(320))
        assertThat(stream!!.channels).isEqualTo(2)
        convert(vbr)
        assertThat(stream!!.mode).isEqualTo(io.sonora.core.mp3.Mp3Mode.Vbr(2))
        // Without tags the stream starts at once, behind no ID3 tag, and still gets its info frame.
        val bytes = convert(vbr)
        assertThat(bytes.copyOfRange(0, FakeMp3Stream.FRAME).all { it == 0x7E.toByte() }).isTrue()
    }

    @Test
    fun `a rate or bitrate MP3 cannot carry, or a missing LAME, fails the file as unsupported`() = runTest {
        val tooFast = runCatching { convert(cbr, rate = 37_800) }.exceptionOrNull()
        assertThat(tooFast).isInstanceOf(UnsupportedOutputException::class.java)
        assertThat(tooFast!!.message).contains("37800 Hz")

        val missing = runCatching {
            convert(cbr, factory = Mp3StreamFactory { _, _, _ -> throw Mp3StreamUnavailableException("MP3 encoder not available on this device", null) })
        }.exceptionOrNull()
        assertThat(missing).isInstanceOf(UnsupportedOutputException::class.java)
        assertThat(missing!!.message).contains("not available")
    }
}
