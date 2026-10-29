package io.sonora.core.analysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.AnalysisException
import io.sonora.core.domain.analysis.Beat
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.math.sin
import org.junit.Test

class AndroidTrackAnalyzerTest {

    private val defaultResult = EngineResult(
        bpm = 120.0,
        firstBeatFrame = 4_410.0, // 100 ms at 44.1 kHz
        firstBeatInBar = 3,
        keyIndex = 22,
        totalFrames = 441_000, // 10 s
        detectedBeats = 19,
    )

    /** Serves [blocks] of interleaved samples, then optionally fails or suspends forever. */
    private class FakeStream(
        override val format: PcmFormat,
        private val blocks: List<FloatArray>,
        private val failAtEnd: Exception? = null,
        private val hangAtEnd: Boolean = false,
    ) : PcmStream {
        var closed = false
        val reachedEnd = CompletableDeferred<Unit>()
        private var next = 0

        override suspend fun read(buffer: FloatArray): Int {
            check(!closed)
            if (next == blocks.size) {
                reachedEnd.complete(Unit)
                failAtEnd?.let { throw it }
                if (hangAtEnd) awaitCancellation()
                return -1
            }
            val block = blocks[next++]
            block.copyInto(buffer)
            return block.size / format.channelCount
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeDecoder(private val stream: FakeStream) : PcmDecoder {
        val opened = mutableListOf<String>()
        override suspend fun open(sourceUri: String): PcmStream {
            opened += sourceUri
            return stream
        }
    }

    private class FakeEngine(private val result: () -> EngineResult) : AnalysisEngine {
        val received = mutableListOf<Float>()
        var finished = false
        var closeCount = 0

        override fun process(samples: FloatArray, count: Int) {
            check(closeCount == 0)
            for (i in 0 until count) received += samples[i]
        }

        override fun finish(): EngineResult {
            finished = true
            return result()
        }

        override fun close() {
            closeCount++
        }
    }

    private fun format(
        channels: Int = 2,
        totalFrames: Long? = null,
        durationUs: Long? = null,
        sampleRateHz: Int = 44_100,
    ) = PcmFormat(
        sampleRateHz = sampleRateHz,
        channelCount = channels,
        sourceSampleFormat = null,
        codec = "test",
        durationUs = durationUs,
        totalFrames = totalFrames,
    )

    private fun stereoBlocks(vararg frames: Int): List<FloatArray> {
        var value = 0f
        return frames.map { count -> FloatArray(count * 2) { value++ } }
    }

    private val engineRates = mutableListOf<Int>()

    private fun factory(engine: FakeEngine) = AnalysisEngineFactory { rate ->
        engineRates += rate
        engine
    }

    @Test
    fun `downmixes every block to mono, feeds it in order and maps the result`() = runTest {
        val stream = FakeStream(format(), stereoBlocks(2, 3))
        val decoder = FakeDecoder(stream)
        val engine = FakeEngine { defaultResult }
        val analyzer = AndroidTrackAnalyzer(decoder, factory(engine), StandardTestDispatcher(testScheduler))

        val analysis = analyzer.analyze("content://track")

        assertThat(decoder.opened).containsExactly("content://track")
        assertThat(engineRates).containsExactly(44_100)
        // Frames (0,1) (2,3) | (4,5) (6,7) (8,9) -> averages 0.5, 2.5, 4.5, 6.5, 8.5
        assertThat(engine.received).containsExactly(0.5f, 2.5f, 4.5f, 6.5f, 8.5f).inOrder()
        assertThat(engine.finished).isTrue()
        assertThat(analysis.bpm).isEqualTo(120.0)
        assertThat(analysis.key).isEqualTo(MusicalKey.A_MINOR)
        assertThat(analysis.key!!.alphanumeric).isEqualTo("8A")
        assertThat(analysis.durationMs).isEqualTo(10_000)
        assertThat(analysis.analyzerVersion).isEqualTo(AndroidTrackAnalyzer.ANALYZER_VERSION)
        with(analysis.beatGrid) {
            assertThat(firstBeatMs).isWithin(1e-9).of(100.0)
            assertThat(firstBeatInBar).isEqualTo(3)
            assertThat(beatCount).isEqualTo(20) // 100, 600, ..., 9600 ms
            assertThat(beatAt(2)).isEqualTo(Beat(1_100.0, 1))
        }
        assertThat(engine.closeCount).isEqualTo(1)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `mono streams pass through unchanged`() = runTest {
        val stream = FakeStream(format(channels = 1), listOf(floatArrayOf(0.25f, -0.5f)))
        val engine = FakeEngine { defaultResult }

        AndroidTrackAnalyzer(FakeDecoder(stream), factory(engine), StandardTestDispatcher(testScheduler))
            .analyze("content://mono")

        assertThat(engine.received).containsExactly(0.25f, -0.5f).inOrder()
    }

    @Test
    fun `an undetected key is null`() = runTest {
        val stream = FakeStream(format(), stereoBlocks(1))
        val engine = FakeEngine { defaultResult.copy(keyIndex = 0) }

        val analysis = AndroidTrackAnalyzer(FakeDecoder(stream), factory(engine), StandardTestDispatcher(testScheduler))
            .analyze("content://track")

        assertThat(analysis.key).isNull()
    }

    @Test
    fun `progress follows the decoded frames when the length is known and ends at one`() = runTest {
        val stream = FakeStream(format(totalFrames = 1_000), stereoBlocks(250, 250, 500))
        val engine = FakeEngine { defaultResult }
        val progress = mutableListOf<Float>()

        AndroidTrackAnalyzer(FakeDecoder(stream), factory(engine), StandardTestDispatcher(testScheduler))
            .analyze("content://track") { progress += it }

        assertThat(progress.size).isAtLeast(3)
        assertThat(progress).isInOrder()
        assertThat(progress.first()).isGreaterThan(0f)
        assertThat(progress.dropLast(1).all { it < 1f }).isTrue()
        assertThat(progress.last()).isEqualTo(1f)
    }

    @Test
    fun `progress uses the declared duration when the frame count is unknown`() = runTest {
        val stream = FakeStream(format(durationUs = 100_000), stereoBlocks(2_205, 2_205))
        val progress = mutableListOf<Float>()

        AndroidTrackAnalyzer(FakeDecoder(stream), factory(FakeEngine { defaultResult }), StandardTestDispatcher(testScheduler))
            .analyze("content://track") { progress += it }

        assertThat(progress.first()).isWithin(0.05f).of(0.5f)
        assertThat(progress.last()).isEqualTo(1f)
    }

    @Test
    fun `without a known length only completion is reported`() = runTest {
        val stream = FakeStream(format(), stereoBlocks(10, 10))
        val progress = mutableListOf<Float>()

        AndroidTrackAnalyzer(FakeDecoder(stream), factory(FakeEngine { defaultResult }), StandardTestDispatcher(testScheduler))
            .analyze("content://track") { progress += it }

        assertThat(progress).containsExactly(1f)
    }

    @Test
    fun `analysis failures propagate and release the engine and the stream`() = runTest {
        val stream = FakeStream(format(), stereoBlocks(4))
        val engine = FakeEngine { throw AnalysisException.NoBeatsDetected() }
        val analyzer = AndroidTrackAnalyzer(FakeDecoder(stream), factory(engine), StandardTestDispatcher(testScheduler))

        val failure = runCatching { analyzer.analyze("content://track") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AnalysisException.NoBeatsDetected::class.java)
        assertThat(engine.closeCount).isEqualTo(1)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `decode failures propagate unchanged and release the engine and the stream`() = runTest {
        val corrupt = PcmDecodeException.CorruptData("bad frame")
        val stream = FakeStream(format(), stereoBlocks(4), failAtEnd = corrupt)
        val engine = FakeEngine { defaultResult }
        val analyzer = AndroidTrackAnalyzer(FakeDecoder(stream), factory(engine), StandardTestDispatcher(testScheduler))

        val failure = runCatching { analyzer.analyze("content://track") }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(corrupt)
        assertThat(engine.finished).isFalse()
        assertThat(engine.closeCount).isEqualTo(1)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `an unsupported sample rate closes the stream`() = runTest {
        val stream = FakeStream(format(), stereoBlocks(4))
        val factory = AnalysisEngineFactory { throw AnalysisException.UnsupportedSampleRate(it) }
        val analyzer = AndroidTrackAnalyzer(FakeDecoder(stream), factory, StandardTestDispatcher(testScheduler))

        val failure = runCatching { analyzer.analyze("content://track") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AnalysisException.UnsupportedSampleRate::class.java)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `a sample rate the waveform cannot take is an unsupported sample rate even if the engine accepts it`() =
        runTest {
            val rates = listOf(BlueWaveformBuilder.MIN_SAMPLE_RATE_HZ - 1, BlueWaveformBuilder.MAX_SAMPLE_RATE_HZ + 1)
            for (rate in rates) {
                val stream = FakeStream(format(sampleRateHz = rate), stereoBlocks(4))
                val engine = FakeEngine { defaultResult }
                val analyzer =
                    AndroidTrackAnalyzer(FakeDecoder(stream), factory(engine), StandardTestDispatcher(testScheduler))

                val failure = runCatching { analyzer.analyze("content://track") }.exceptionOrNull()

                assertThat(failure).isInstanceOf(AnalysisException.UnsupportedSampleRate::class.java)
                assertThat((failure as AnalysisException.UnsupportedSampleRate).sampleRateHz).isEqualTo(rate)
                assertThat(engine.closeCount).isEqualTo(engineRates.count { it == rate })
                assertThat(stream.closed).isTrue()
            }
        }

    @Test
    fun `cancellation stops reading, skips finishing and releases everything`() = runTest {
        val stream = FakeStream(format(), stereoBlocks(4, 4), hangAtEnd = true)
        val engine = FakeEngine { defaultResult }
        val analyzer = AndroidTrackAnalyzer(FakeDecoder(stream), factory(engine), UnconfinedTestDispatcher(testScheduler))

        val job = async { analyzer.analyze("content://track") }
        stream.reachedEnd.await()
        job.cancel()
        advanceUntilIdle()

        assertThat(job.isCancelled).isTrue()
        assertThat(engine.received).hasSize(8)
        assertThat(engine.finished).isFalse()
        assertThat(engine.closeCount).isEqualTo(1)
        assertThat(stream.closed).isTrue()
    }

    @Test
    fun `the waveform is built from the same mono samples the engine gets`() = runTest {
        val blocks = toneBlocks(frames = 44_100 * 6, chunk = 4096)
        val stream = FakeStream(format(), blocks)
        val engine = FakeEngine { defaultResult }

        val analysis = AndroidTrackAnalyzer(FakeDecoder(stream), factory(engine), StandardTestDispatcher(testScheduler))
            .analyze("content://track")

        val expected = BlueWaveformBuilder(44_100).apply {
            val mono = engine.received.toFloatArray()
            process(mono, mono.size)
        }.finish()
        assertThat(analysis.waveform).isEqualTo(expected)
        assertThat(analysis.waveform.detailSize).isEqualTo(900)
        assertThat(analysis.waveform.detail().any { it != 0.toByte() }).isTrue()
    }

    @Test
    fun `a known or unknown frame count gives the same waveform`() = runTest {
        val frames = 44_100 * 5 + 77
        suspend fun analyse(format: PcmFormat) = AndroidTrackAnalyzer(
            FakeDecoder(FakeStream(format, toneBlocks(frames, 1_000))),
            factory(FakeEngine { defaultResult }),
            StandardTestDispatcher(testScheduler),
        ).analyze("content://track").waveform

        val unknown = analyse(format())
        val known = analyse(format(totalFrames = frames.toLong()))
        val wrong = analyse(format(totalFrames = frames * 3L))

        assertThat(known).isEqualTo(unknown)
        assertThat(wrong).isEqualTo(unknown)
        assertThat(unknown.preview()).hasLength(BlueWaveform.PREVIEW_COLUMNS)
    }

    @Test
    fun `invalid engine values are an engine failure and still release everything`() = runTest {
        val invalid = listOf(
            defaultResult.copy(bpm = Double.NaN),
            defaultResult.copy(bpm = 0.0),
            defaultResult.copy(bpm = -120.0),
            defaultResult.copy(bpm = Double.POSITIVE_INFINITY),
            defaultResult.copy(firstBeatFrame = Double.NaN),
            defaultResult.copy(firstBeatFrame = Double.NEGATIVE_INFINITY),
            defaultResult.copy(firstBeatInBar = 0),
            defaultResult.copy(firstBeatInBar = 5),
            defaultResult.copy(totalFrames = -1),
        )
        for (result in invalid) {
            val stream = FakeStream(format(), stereoBlocks(4))
            val engine = FakeEngine { result }
            val analyzer = AndroidTrackAnalyzer(FakeDecoder(stream), factory(engine), StandardTestDispatcher(testScheduler))

            val failure = runCatching { analyzer.analyze("content://track") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(AnalysisException.EngineFailure::class.java)
            assertThat(engine.closeCount).isEqualTo(1)
            assertThat(stream.closed).isTrue()
        }
    }

    /** Stereo blocks of [chunk] frames (last one shorter) of a 440 Hz tone, the same on both channels. */
    private fun toneBlocks(frames: Int, chunk: Int): List<FloatArray> = (0 until frames step chunk).map { start ->
        val count = minOf(chunk, frames - start)
        FloatArray(count * 2) { i -> (0.6 * sin(2 * Math.PI * 440 * (start + i / 2) / 44_100)).toFloat() }
    }
}
