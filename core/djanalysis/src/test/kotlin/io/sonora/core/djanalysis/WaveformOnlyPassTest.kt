package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.fixtures.SyntheticAudio
import io.sonora.core.djengine.pipeline.DjAnalysisPipeline
import io.sonora.core.djengine.pipeline.StageSinks
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisTelemetry
import io.sonora.core.djmodel.AnalysisTimings
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.BeatActivationModelFactory
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.MonoSink
import io.sonora.core.djmodel.MonoSinkFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class WaveformOnlyPassTest {

    @Test
    fun `gives the pipeline's waveform for stereo at a rate the sink takes directly`() {
        val samples = stereo(44_100, 3_000.0)

        val alone = WaveformOnlyPass().run(ArrayPcmSource(samples, 44_100, 2, framesPerRead = 1_000)).value
        val inPipeline = pipelineWaveform(ArrayPcmSource(samples, 44_100, 2))

        assertThat(alone.columnCount).isEqualTo(450)
        assertThat(alone).isEqualTo(inPipeline)
    }

    @Test
    fun `gives the pipeline's waveform when the sink needs a resampled stream`() {
        val samples = SyntheticAudio.clickTrack(120.0, 2_000.0, sampleRateHz = 8_000)

        val alone = WaveformOnlyPass().run(ArrayPcmSource(samples, 8_000, 1, framesPerRead = 777)).value
        val inPipeline = pipelineWaveform(ArrayPcmSource(samples, 8_000, 1))

        assertThat(alone.columnCount).isAtLeast(299)
        assertThat(alone).isEqualTo(inPipeline)
    }

    @Test
    fun `times the decode and the waveform and reports the audio length`() {
        val clock = FakePipelineClock()

        val result = WaveformOnlyPass(clock = clock).run(ArrayPcmSource(stereo(48_000, 1_500.0), 48_000, 2))

        assertThat(result.stageMs.keys).containsExactly(AnalysisStage.DECODE, AnalysisStage.WAVEFORM)
        assertThat(result.audioMs).isEqualTo(1_500L)
    }

    @Test
    fun `progress rises to 1`() {
        val progress = ArrayList<Float>()

        WaveformOnlyPass(blockFrames = 4_096).run(ArrayPcmSource(stereo(44_100, 2_000.0), 44_100, 2), onProgress = { progress += it })

        assertThat(progress).isInStrictOrder()
        assertThat(progress.last()).isEqualTo(1f)
        assertThat(progress.size).isAtLeast(3)
    }

    @Test
    fun `a cancelled run stops before the next block`() {
        val source = ArrayPcmSource(stereo(44_100, 5_000.0), 44_100, 2)
        var checks = 0

        assertThrows(CancellationException::class.java) {
            WaveformOnlyPass(blockFrames = 1_024).run(source, checkCancelled = { if (++checks == 3) throw CancellationException("stop") })
        }
        assertThat(source.reads).isEqualTo(2)
    }

    private fun stereo(rate: Int, durationMs: Double): FloatArray {
        val left = SyntheticAudio.clickTrack(128.0, durationMs, sampleRateHz = rate)
        val right = SyntheticAudio.sine(220.0, durationMs, sampleRateHz = rate, amplitude = 0.3)
        return FloatArray(left.size * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }
    }

    private fun pipelineWaveform(pcm: ArrayPcmSource): BandWaveform = runBlocking {
        val noModel = object : BeatActivationModelFactory {
            override suspend fun create(preferred: Accelerator): BeatActivationModel? = null
        }
        val noGrid = StageSinks { rate ->
            object : MonoSinkFactory<DjBeatGrid?> {
                override val sampleRateHz: Int = rate

                override fun create(): MonoSink<DjBeatGrid?> = object : MonoSink<DjBeatGrid?> {
                    override fun accept(samples: FloatArray, offset: Int, count: Int) = Unit

                    override fun finish(): DjBeatGrid? = null
                }
            }
        }
        val silent = object : AnalysisTelemetry {
            override fun analysed(audioId: AudioId, timings: AnalysisTimings, provenance: AnalysisProvenance) = Unit

            override fun failed(stage: AnalysisStage, cause: Throwable) = Unit
        }
        DjAnalysisPipeline(noModel, noGrid, silent).run(pcm, audioId(1)).waveform
    }
}

class FakePipelineClock : io.sonora.core.djengine.pipeline.PipelineClock {
    private var nanos = 0L

    override fun nowEpochMs(): Long = 1_700_000_000_000

    override fun monotonicNanos(): Long {
        nanos += 1_000_000L
        return nanos
    }
}
