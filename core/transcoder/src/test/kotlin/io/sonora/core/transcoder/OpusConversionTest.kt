package io.sonora.core.transcoder

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.conversion.ConversionPlanner
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.OutputContainer
import io.sonora.core.domain.conversion.OutputSampleFormat
import io.sonora.core.domain.conversion.SampleRateRule
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.transcoder.dsp.Signals
import io.sonora.core.transcoder.encoder.EncoderRegistry
import io.sonora.core.transcoder.encoder.OpusEncoderFactory
import io.sonora.core.transcoder.ogg.OggPages
import io.sonora.core.transcoder.ogg.OpusHeaders
import io.sonora.core.transcoder.ogg.VorbisComment
import kotlin.math.abs
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The engine with the Ogg Opus encoder over a [FakeOpusCodec]: the real codec only runs on a device. */
class OpusConversionTest {

    private val watch = ConversionProfiles.WATCH_OPUS
    private val source = "content://music/track.flac"

    private fun engine(
        stream: () -> FakePcmStream,
        codec: FakeOpusCodec = FakeOpusCodec(),
        metadata: MetadataChunkSource = MetadataChunkSource.None,
    ) = ConversionEngine(
        FakePcmDecoder(mapOf(source to stream)),
        EncoderRegistry(
            mapOf(
                (OutputContainer.OGG_OPUS to OutputSampleFormat.OPUS) to OpusEncoderFactory { channels, bitrate ->
                    codec.also { it.channels = channels; it.bitrateBps = bitrate }
                },
            ),
        ),
        metadata,
    )

    private fun sine(rate: Int, frames: Int) = Signals.sines(rate, frames, 0.5, 1_000.0, 1_000.0)

    private fun pcmStream(rate: Int, frames: Int, onRead: suspend (Int) -> Unit = {}) =
        FakePcmStream(FakePcmStream.format(rate, 2, PcmSampleFormat.INT16, frames.toLong()), sine(rate, frames), beforeRead = onRead)

    @Test
    fun `a 44_1 kHz source becomes an Ogg Opus stream of 48 kHz frames with an exact end`() = runTest {
        val frames = 44_100
        val output = MemoryChannel()
        val codec = FakeOpusCodec()

        val result = engine({ pcmStream(44_100, frames) }, codec).convert(source, watch, output)

        assertThat(result.plan.resample).isTrue()
        assertThat(result.plan.targetSampleRateHz).isEqualTo(48_000)
        assertThat(result.outputFrames).isEqualTo(48_000)
        assertThat(result.sourceFrames).isEqualTo(frames)
        assertThat(codec.closed).isTrue()
        val pages = OggPages.parse(output.bytes())
        assertThat(pages.all { it.crcOk }).isTrue()
        assertThat(pages.first().beginOfStream).isTrue()
        assertThat(pages.last().endOfStream).isTrue()
        val packets = OggPages.packets(pages)
        assertThat(packets[0]).isEqualTo(OpusHeaders.head(2, 312, 44_100))
        // 50 frames of 960 samples plus the flush packet of the codec.
        assertThat(packets.drop(2)).hasSize(51)
        assertThat(pages.last().granulePosition).isEqualTo(312L + 48_000)
        assertThat(result.outputDurationUs).isEqualTo(1_000_000)
    }

    @Test
    fun `the codec gets whole 20 ms frames of little endian PCM at the profile bitrate`() = runTest {
        val codec = FakeOpusCodec()

        engine({ pcmStream(48_000, 5_000) }, codec).convert(source, watch, MemoryChannel(), chunkFrames = 1_000)

        assertThat(codec.channels).isEqualTo(2)
        assertThat(codec.bitrateBps).isEqualTo(192_000)
        // 5000 frames: five whole frames of 960 and one padded frame of 200.
        assertThat(codec.frames.map { it.size }.distinct()).containsExactly(960 * 2 * 2)
        assertThat(codec.frames).hasSize(6)
        assertThat(codec.endOfStreamAfterFrames).isEqualTo(6)
        // Frame 1 of the left channel is 0.5 * sin(2 pi 1000 / 48000) = 0.0653, 2140 in 16 bits; low byte first.
        val first = codec.frames[0]
        val left = (first[4].toInt() and 0xFF) or (first[5].toInt() shl 8)
        assertThat(abs(left - 2_140)).isAtMost(2)
    }

    @Test
    fun `the end of a padded last frame is trimmed through the granule`() = runTest {
        val output = MemoryChannel()

        val result = engine({ pcmStream(48_000, 5_000) }).convert(source, watch, output)

        assertThat(result.outputFrames).isEqualTo(5_000)
        assertThat(OggPages.parse(output.bytes()).last().granulePosition).isEqualTo(312L + 5_000)
    }

    @Test
    fun `the tags are asked for and written before the first audio is read`() = runTest {
        val events = ArrayList<String>()
        val tags = OpusHeaders.tags("Sonora", listOf(VorbisComment("TITLE", "Aria")))
        val metadata = MetadataChunkSource { _, _ ->
            events += "metadata"
            listOf(MetadataChunk("OTAG", tags))
        }
        val output = MemoryChannel()

        val result = engine({ pcmStream(48_000, 2_000) { events += "read" } }, metadata = metadata).convert(source, watch, output)

        assertThat(events.first()).isEqualTo("metadata")
        assertThat(events.count { it == "metadata" }).isEqualTo(1)
        assertThat(result.tagsWritten).isTrue()
        assertThat(OggPages.packets(OggPages.parse(output.bytes()))[1]).isEqualTo(tags)
    }

    @Test
    fun `without tags the stream still carries a minimal OpusTags header`() = runTest {
        val output = MemoryChannel()

        val result = engine({ pcmStream(48_000, 2_000) }).convert(source, watch, output)

        assertThat(result.tagsWritten).isFalse()
        val tags = OggPages.packets(OggPages.parse(output.bytes()))[1]
        assertThat(String(tags, 0, 8)).isEqualTo("OpusTags")
        assertThat(String(tags, 12, 6)).isEqualTo("Sonora")
    }

    @Test
    fun `the pre-skip comes from the codec`() = runTest {
        val output = MemoryChannel()

        engine({ pcmStream(48_000, 2_000) }, FakeOpusCodec(preSkip = 410)).convert(source, watch, output)

        val pages = OggPages.parse(output.bytes())
        assertThat(OggPages.packets(pages)[0]).isEqualTo(OpusHeaders.head(2, 410, 48_000))
        assertThat(pages.last().granulePosition).isEqualTo(410L + 2_000)
    }

    @Test
    fun `a mono source stays mono`() = runTest {
        val frames = 4_800
        val stream = FakePcmStream(FakePcmStream.format(48_000, 1, PcmSampleFormat.INT16, frames.toLong()), FloatArray(frames))
        val codec = FakeOpusCodec()

        engine({ stream }, codec).convert(source, watch, MemoryChannel())

        assertThat(codec.channels).isEqualTo(1)
        assertThat(codec.frames.map { it.size }.distinct()).containsExactly(960 * 2)
    }

    @Test
    fun `the codec is released when the conversion fails`() = runTest {
        val codec = FakeOpusCodec()
        val failing = { pcmStream(48_000, 5_000) { if (it == 2) error("decoder broke") } }

        runCatching { engine(failing, codec).convert(source, watch, MemoryChannel()) }

        assertThat(codec.closed).isTrue()
    }

    @Test
    fun `a profile without a bitrate or off 48 kHz cannot be encoded`() = runTest {
        val noBitrate = watch.copy(bitrateBps = null)
        val offRate = watch.copy(sampleRate = SampleRateRule(maxSampleRateHz = 48_000, resampleTargetHz = 44_100))

        for (profile in listOf(noBitrate, offRate)) {
            val result = runCatching { engine({ pcmStream(44_100, 1_000) }).convert(source, profile, MemoryChannel()) }

            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private suspend fun verify(frames: Int, rate: Int, expected: ConversionResult): OutputVerification {
        val decoded = "content://music/out"
        val stream = { FakePcmStream(FakePcmStream.format(rate, 2, null, frames.toLong()), FloatArray(frames * 2)) }
        return ConversionEngine(FakePcmDecoder(mapOf(decoded to stream)), EncoderRegistry.default(), MetadataChunkSource.None)
            .verifyDecodes(decoded, expected)
    }

    @Test
    fun `an Opus output may decode up to one 20 ms frame off the expected length`() = runTest {
        val plan = ConversionPlanner.plan(FakePcmStream.format(48_000, 2, PcmSampleFormat.INT16, null), watch)
        val expected = ConversionResult(plan, outputFrames = 48_000, sourceFrames = 48_000)

        assertThat(verify(48_000, 48_000, expected)).isEqualTo(OutputVerification.Valid)
        assertThat(verify(48_000 + 960, 48_000, expected)).isEqualTo(OutputVerification.Valid)
        assertThat(verify(48_000 - 960, 48_000, expected)).isEqualTo(OutputVerification.Valid)
        assertThat(verify(48_000 + 961, 48_000, expected)).isInstanceOf(OutputVerification.Invalid::class.java)
        assertThat(verify(48_000 - 961, 48_000, expected)).isInstanceOf(OutputVerification.Invalid::class.java)
    }

    @Test
    fun `an AIFF output must still decode to exactly the expected length`() = runTest {
        val plan = ConversionPlanner.plan(FakePcmStream.format(44_100, 2, PcmSampleFormat.INT16, null), ConversionProfiles.XDJ_700)
        val expected = ConversionResult(plan, outputFrames = 44_100, sourceFrames = 44_100)

        assertThat(verify(44_100, 44_100, expected)).isEqualTo(OutputVerification.Valid)
        assertThat(verify(44_101, 44_100, expected)).isInstanceOf(OutputVerification.Invalid::class.java)
        assertThat(verify(44_099, 44_100, expected)).isInstanceOf(OutputVerification.Invalid::class.java)
    }

    @Test
    fun `a chosen Opus bitrate reaches the codec and the watch keeps 192 kbps`() = runTest {
        val codec = FakeOpusCodec()
        val chosen = io.sonora.core.domain.conversion.OutputTargets.profileFor(io.sonora.core.domain.conversion.OutputTarget.Opus(96))

        engine({ pcmStream(48_000, 960) }, codec).convert(source, chosen, MemoryChannel())
        assertThat(codec.bitrateBps).isEqualTo(96_000)

        engine({ pcmStream(48_000, 960) }, codec).convert(source, watch, MemoryChannel())
        assertThat(codec.bitrateBps).isEqualTo(192_000)
    }

    @Test
    fun `a device without an Opus encoder fails the file as unsupported`() = runTest {
        val engine = ConversionEngine(
            FakePcmDecoder(mapOf(source to { pcmStream(48_000, 960) })),
            EncoderRegistry(
                mapOf(
                    (OutputContainer.OGG_OPUS to OutputSampleFormat.OPUS) to OpusEncoderFactory { _, _ ->
                        throw io.sonora.core.transcoder.encoder.OpusEncoderUnavailableException("This device has no Opus encoder")
                    },
                ),
            ),
            MetadataChunkSource.None,
        )

        val error = runCatching { engine.convert(source, watch, MemoryChannel()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(io.sonora.core.domain.port.UnsupportedOutputException::class.java)
        assertThat(error!!.message).contains("no Opus encoder")
    }
}
