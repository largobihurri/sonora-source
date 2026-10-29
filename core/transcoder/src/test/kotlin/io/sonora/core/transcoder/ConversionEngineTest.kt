package io.sonora.core.transcoder

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.AiffParser
import io.sonora.core.domain.audio.AiffSource
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.MetadataPolicy
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.transcoder.dsp.Signals
import io.sonora.core.transcoder.encoder.EncoderRegistry
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversionEngineTest {

    private val xdj = ConversionProfiles.XDJ_700
    private val source = "content://music/track.flac"

    private fun engine(stream: () -> FakePcmStream, metadata: MetadataChunkSource = MetadataChunkSource.None) =
        ConversionEngine(FakePcmDecoder(mapOf(source to stream)), EncoderRegistry.default(), metadata)

    private fun parse(bytes: ByteArray) = AiffParser.parse(AiffSource.of(ByteArrayInputStream(bytes)))

    private fun samples16(bytes: ByteArray): ShortArray {
        val header = parse(bytes)
        val buffer = ByteBuffer.wrap(bytes, header.dataStart.toInt(), header.dataLength.toInt())
        return ShortArray((header.dataLength / 2).toInt()) { buffer.getShort() }
    }

    @Test
    fun `a 96 kHz 24-bit stereo source becomes 44_1 kHz 16-bit AIFF with its tones intact`() = runTest {
        val frames = 19_200
        val input = Signals.sines(96_000, frames, 0.5, 1_000.0, 5_000.0)
        val stream = FakePcmStream(FakePcmStream.format(96_000, 2, PcmSampleFormat.INT24, frames.toLong()), input)
        val output = MemoryChannel()

        val result = engine({ stream }).convert(source, xdj, output)

        assertThat(result.plan.resample).isTrue()
        assertThat(result.plan.dither).isTrue()
        assertThat(result.sourceFrames).isEqualTo(frames)
        assertThat(result.outputFrames).isEqualTo(8_820)
        assertThat(result.outputDurationUs).isEqualTo(200_000)
        assertThat(stream.closed).isTrue()
        val header = parse(output.bytes())
        assertThat(header.sampleRateHz).isEqualTo(44_100)
        assertThat(header.channelCount).isEqualTo(2)
        assertThat(header.declaredBits).isEqualTo(16)
        assertThat(header.frameCount).isEqualTo(8_820)
        val pcm = samples16(output.bytes())
        val left = DoubleArray(8_820) { pcm[it * 2] / 32_768.0 }
        val right = DoubleArray(8_820) { pcm[it * 2 + 1] / 32_768.0 }
        val leftLevel = Signals.db(Signals.amplitudeAt(left, 500, 8_300, 1_000.0, 44_100) / 0.5)
        val rightLevel = Signals.db(Signals.amplitudeAt(right, 500, 8_300, 5_000.0, 44_100) / 0.5)
        val leftHz = Signals.zeroCrossingFrequency(left, 500, 8_300, 44_100)
        println("engine 96k/24 -> 44.1k/16: L 1 kHz ${"%.4f".format(leftLevel)} dB at ${"%.3f".format(leftHz)} Hz, R 5 kHz ${"%.4f".format(rightLevel)} dB")
        assertThat(abs(leftLevel)).isLessThan(0.1)
        assertThat(abs(rightLevel)).isLessThan(0.1)
        assertThat(abs(leftHz - 1_000.0)).isLessThan(0.5)
    }

    @Test
    fun `a 16-bit 44_1 kHz source passes through bit-exact`() = runTest {
        val values = ShortArray(2_000) { ((it * 37) % 65_536 - 32_768).toShort() }
        val input = FloatArray(values.size) { values[it] / 32_768f }
        val stream = FakePcmStream(FakePcmStream.format(44_100, 2, PcmSampleFormat.INT16, 1_000), input)
        val output = MemoryChannel()

        val result = engine({ stream }).convert(source, xdj, output)

        assertThat(result.plan.dither).isFalse()
        assertThat(samples16(output.bytes())).isEqualTo(values)
    }

    @Test
    fun `a 48 kHz 5_1 source becomes stereo at 48 kHz`() = runTest {
        val input = FloatArray(4_800 * 6) { if (it % 6 == 2) 0.25f else 0f } // centre only
        val stream = FakePcmStream(FakePcmStream.format(48_000, 6, PcmSampleFormat.INT24, null), input)
        val output = MemoryChannel()

        val result = engine({ stream }).convert(source, xdj, output)

        assertThat(result.plan.downmix).isTrue()
        val header = parse(output.bytes())
        assertThat(header.sampleRateHz).isEqualTo(48_000)
        assertThat(header.channelCount).isEqualTo(2)
        assertThat(header.frameCount).isEqualTo(4_800)
        val pcm = samples16(output.bytes())
        assertThat(abs(pcm[2_000] - pcm[2_001])).isAtMost(2)
        assertThat(pcm[2_000].toInt()).isGreaterThan(1_000)
    }

    @Test
    fun `mono stays mono`() = runTest {
        val stream = FakePcmStream(FakePcmStream.format(44_100, 1, null, null), FloatArray(3_000))
        val output = MemoryChannel()

        val result = engine({ stream }).convert(source, xdj, output)

        assertThat(result.plan.targetChannels).isEqualTo(1)
        assertThat(parse(output.bytes()).channelCount).isEqualTo(1)
        assertThat(result.outputFrames).isEqualTo(3_000)
    }

    @Test
    fun `the block size changes nothing in the output`() = runTest {
        val input = Signals.sines(96_000, 9_600, 0.5, 440.0, 3_000.0)
        fun stream() = FakePcmStream(FakePcmStream.format(96_000, 2, PcmSampleFormat.FLOAT32, 9_600), input, maxFrames = 100_000)
        val small = MemoryChannel()
        val large = MemoryChannel()

        engine(::stream).convert(source, xdj, small, chunkFrames = 2_048)
        engine(::stream).convert(source, xdj, large, chunkFrames = 8_192)

        assertThat(small.bytes()).isEqualTo(large.bytes())
    }

    @Test
    fun `progress rises to 1 when the source length is known`() = runTest {
        val stream = FakePcmStream(FakePcmStream.format(44_100, 2, PcmSampleFormat.INT24, 10_000), FloatArray(20_000))
        val progress = mutableListOf<Float>()

        engine({ stream }).convert(source, xdj, MemoryChannel(), chunkFrames = 1_000) { progress += it }

        assertThat(progress.size).isAtLeast(10)
        assertThat(progress).isInOrder()
        assertThat(progress.last()).isEqualTo(1f)
        assertThat(progress.all { it in 0f..1f }).isTrue()
    }

    @Test
    fun `progress is reported only at the end when the length is unknown`() = runTest {
        val stream = FakePcmStream(FakePcmStream.format(44_100, 2, null, null), FloatArray(20_000))
        val progress = mutableListOf<Float>()

        engine({ stream }).convert(source, xdj, MemoryChannel(), chunkFrames = 1_000) { progress += it }

        assertThat(progress).containsExactly(1f)
    }

    @Test
    fun `cancellation stops the conversion and closes the source`() = runTest {
        val started = CompletableDeferred<Unit>()
        lateinit var stream: FakePcmStream
        stream = FakePcmStream(FakePcmStream.format(44_100, 2, PcmSampleFormat.INT24, 100_000), FloatArray(200_000)) { read ->
            if (read == 3) {
                started.complete(Unit)
                currentCoroutineContext().cancel()
            }
        }

        val job = async { engine({ stream }).convert(source, xdj, MemoryChannel(), chunkFrames = 1_000) }
        val error = runCatching { job.await() }.exceptionOrNull()

        assertThat(started.isCompleted).isTrue()
        assertThat(error).isInstanceOf(CancellationException::class.java)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `decode failures propagate and close the source`() = runTest {
        lateinit var stream: FakePcmStream
        stream = FakePcmStream(FakePcmStream.format(44_100, 2, null, null), FloatArray(20_000)) { read ->
            if (read == 2) throw PcmDecodeException.CorruptData("bad frame")
        }

        val error = runCatching { engine({ stream }).convert(source, xdj, MemoryChannel(), chunkFrames = 1_000) }.exceptionOrNull()

        assertThat(error).isInstanceOf(PcmDecodeException.CorruptData::class.java)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `metadata chunks are requested for profiles that copy tags and written after the sound`() = runTest {
        val asked = mutableListOf<String>()
        val metadata = MetadataChunkSource { uri, _ ->
            asked += uri
            listOf(MetadataChunk("ID3 ", byteArrayOf(1, 2, 3, 4)))
        }
        val output = MemoryChannel()
        fun stream() = FakePcmStream(FakePcmStream.format(44_100, 1, PcmSampleFormat.INT16, 10), FloatArray(10))

        engine(::stream, metadata).convert(source, xdj, output)
        val untagged: ConversionProfile = xdj.copy(id = "untagged", metadata = MetadataPolicy.NONE)
        engine(::stream, metadata).convert(source, untagged, MemoryChannel())

        assertThat(asked).containsExactly(source)
        val bytes = output.bytes()
        assertThat(String(bytes, 54 + 20, 4, Charsets.US_ASCII)).isEqualTo("ID3 ")
    }

    @Test
    fun `the result tells whether tags were written`() = runTest {
        val tagged = MetadataChunkSource { _, _ -> listOf(MetadataChunk("ID3 ", byteArrayOf(1, 2, 3, 4))) }
        val nothingToCopy = MetadataChunkSource { _, _ -> emptyList() }
        fun stream() = FakePcmStream(FakePcmStream.format(44_100, 1, PcmSampleFormat.INT16, 10), FloatArray(10))
        val untagged: ConversionProfile = xdj.copy(id = "untagged", metadata = MetadataPolicy.NONE)

        assertThat(engine(::stream, tagged).convert(source, xdj, MemoryChannel()).tagsWritten).isTrue()
        assertThat(engine(::stream, nothingToCopy).convert(source, xdj, MemoryChannel()).tagsWritten).isFalse()
        assertThat(engine(::stream, tagged).convert(source, untagged, MemoryChannel()).tagsWritten).isFalse()
    }

    @Test
    fun `an output that decodes to the reported format and length verifies`() = runTest {
        val input = Signals.sines(88_200, 8_820, 0.5, 1_000.0, 1_000.0)
        val stream = FakePcmStream(FakePcmStream.format(88_200, 2, PcmSampleFormat.INT24, 8_820), input)
        val output = MemoryChannel()
        val outputUri = "content://out/track.aiff"
        val decoder = FakePcmDecoder(mapOf(source to { stream }, outputUri to { aiffStream(output.bytes()) }))
        val engine = ConversionEngine(decoder, EncoderRegistry.default(), MetadataChunkSource.None)

        val result = engine.convert(source, xdj, output)

        assertThat(engine.verifyDecodes(outputUri, result)).isEqualTo(OutputVerification.Valid)
        assertThat(engine.verifyDecodes(outputUri, result.copy(outputFrames = result.outputFrames + 1)))
            .isInstanceOf(OutputVerification.Invalid::class.java)
        val other = result.copy(plan = result.plan.copy(targetSampleRateHz = 48_000))
        assertThat(engine.verifyDecodes(outputUri, other)).isInstanceOf(OutputVerification.Invalid::class.java)
        val missing = engine.verifyDecodes("content://out/missing.aiff", result)
        assertThat(missing).isInstanceOf(OutputVerification.Invalid::class.java)
        assertThat((missing as OutputVerification.Invalid).cause).isInstanceOf(PcmDecodeException::class.java)
    }

    @Test
    fun `any failure to open or read the output comes back as Invalid, cancellation still propagates`() = runTest {
        val result = engine({ FakePcmStream(FakePcmStream.format(44_100, 1, PcmSampleFormat.INT16, 10), FloatArray(10)) })
            .convert(source, xdj, MemoryChannel())
        val failures = listOf(SecurityException("revoked"), IllegalStateException("bad state"), IllegalArgumentException("bad uri"))
        for (failure in failures) {
            val decoder = object : io.sonora.core.domain.audio.PcmDecoder {
                override suspend fun open(sourceUri: String) = throw failure
            }
            val verification = ConversionEngine(decoder, EncoderRegistry.default(), MetadataChunkSource.None)
                .verifyDecodes("content://out/x.aiff", result)

            assertThat(verification).isInstanceOf(OutputVerification.Invalid::class.java)
            assertThat((verification as OutputVerification.Invalid).cause).isSameInstanceAs(failure)
        }
        val cancelling = object : io.sonora.core.domain.audio.PcmDecoder {
            override suspend fun open(sourceUri: String) = throw CancellationException("stop")
        }
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                ConversionEngine(cancelling, EncoderRegistry.default(), MetadataChunkSource.None)
                    .verifyDecodes("content://out/x.aiff", result)
            }
        }
    }

    @Test
    fun `an unsupported block size is rejected`() {
        val engine = engine({ FakePcmStream(FakePcmStream.format(44_100, 1, null, null), FloatArray(1)) })

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { engine.convert(source, xdj, MemoryChannel(), chunkFrames = 0) }
        }
    }
}
