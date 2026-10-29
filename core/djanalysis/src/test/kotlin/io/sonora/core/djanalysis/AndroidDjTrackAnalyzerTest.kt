package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.analysis.EngineResult
import io.sonora.core.djengine.fixtures.SyntheticAudio
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.djmodel.DjAnalysisLimits
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.BeatActivationModelFactory
import io.sonora.core.djmodel.BeatModelDescriptor
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.ChunkLogits
import io.sonora.core.djmodel.MelSpectrogram
import io.sonora.core.domain.analysis.AnalysisException
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.ThermalLevel
import io.sonora.core.domain.audio.PcmDecodeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidDjTrackAnalyzerTest {

    private val uri = "content://test/track.wav"
    private val signal = SyntheticAudio.clickTrack(120.0, 6_000.0, sampleRateHz = 22_050)
    private val decoder = FakePcmDecoder(signal, 22_050, 1)
    private val engines = FakeEngines(EngineResult(120.0, 11_025.0, 2, 0, signal.size.toLong(), 12))
    private val log = RecordingLog()

    private fun analyzer(models: BeatActivationModelFactory = NoModel) =
        AndroidDjTrackAnalyzer(decoder, models, engines, log, Dispatchers.Unconfined, FakePipelineClock())

    @Test
    fun `without a model the grid is qm-dsp's, fed in the same decode`() = runBlocking<Unit> {
        val result = analyzer().full(uri, audioId(1)) { _, _ -> }

        val analysis = result.value
        assertThat(analysis.provenance.beatSource).isEqualTo(BeatSource.QM_DSP)
        // qm-dsp's grid, then the phase refinement against the onsets (the clicks are at 500 ms steps).
        val segment = analysis.grid!!.segments.single()
        assertThat(segment.bpm).isEqualTo(120.0)
        assertThat(segment.beatInBarAtStart).isEqualTo(2)
        assertThat(segment.startMs).isWithin(1.0).of(500.0)
        assertThat(analysis.durationMs).isEqualTo(6_000L)
        assertThat(analysis.waveform.columnCount).isEqualTo(900)
        assertThat(result.audioMs).isEqualTo(6_000L)
        assertThat(result.stageMs.keys).containsAtLeast(AnalysisStage.DECODE, AnalysisStage.WAVEFORM, AnalysisStage.KEY, AnalysisStage.GRID)
        assertThat(engines.created).isEqualTo(1)
        assertThat(engines.closed).isEqualTo(1)
        assertThat(decoder.streams.single().closed).isTrue()
    }

    @Test
    fun `analyze is the full pass without the timings`() = runBlocking<Unit> {
        val analysis = analyzer().analyze(uri, audioId(1))

        assertThat(analysis.grid!!.segments.single().bpm).isEqualTo(120.0)
    }

    @Test
    fun `a model that throws during inference falls back to qm-dsp and is logged`() = runBlocking<Unit> {
        val model = ThrowingModel()

        val analysis = analyzer(SingleModel(model)).full(uri, audioId(1)) { _, _ -> }.value

        assertThat(analysis.provenance.beatSource).isEqualTo(BeatSource.QM_DSP)
        assertThat(analysis.grid!!.segments.single().bpm).isEqualTo(120.0)
        assertThat(model.closed).isTrue()
        assertThat(log.warnings.map { it.second }).contains(model.failure)
    }

    @Test
    fun `progress is labelled by stage and ends at 1`() = runBlocking<Unit> {
        val steps = ArrayList<Pair<AnalysisStage, Float>>()

        analyzer().full(uri, audioId(1)) { stage, fraction -> steps += stage to fraction }

        assertThat(steps.first()).isEqualTo(AnalysisStage.DECODE to 0f)
        assertThat(steps.last()).isEqualTo(AnalysisStage.GRID to 1f)
        assertThat(steps.map { it.second }).isInStrictOrder()
    }

    @Test
    fun `the waveform pass gives the full pass's waveform without the model or qm-dsp`() = runBlocking<Unit> {
        val full = analyzer().full(uri, audioId(1)) { _, _ -> }.value.waveform
        val models = CountingNoModel()
        val createdBefore = engines.created

        val alone = analyzer(models).waveform(uri) { _, _ -> }

        assertThat(alone.value).isEqualTo(full)
        assertThat(alone.audioMs).isEqualTo(6_000L)
        assertThat(models.asked).isEqualTo(0)
        assertThat(engines.created).isEqualTo(createdBefore)
        assertThat(decoder.streams.last().closed).isTrue()
    }

    @Test
    fun `the waveform pass refuses audio with no columns`() {
        val empty = AndroidDjTrackAnalyzer(FakePcmDecoder(FloatArray(0), 44_100, 2), NoModel, engines, log, Dispatchers.Unconfined)

        assertThrows(AnalysisException.TooShort::class.java) { runBlocking { empty.waveform(uri) { _, _ -> } } }
    }

    @Test
    fun `a file that cannot be decoded fails`() {
        assertThrows(PcmDecodeException.SourceUnavailable::class.java) {
            runBlocking { analyzer().full("missing://track", audioId(1)) { _, _ -> } }
        }
    }

    @Test
    fun `a track starts at the priority of the performance in force and checks the limits between chunks`() = runBlocking<Unit> {
        val priorities = ArrayList<Int>()
        val throttle = AnalysisThrottle(
            limits = { DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, DeviceLoad()) },
            setPriority = { priorities += it },
        )

        throttledAnalyzer(throttle).full(uri, audioId(1)) { _, _ -> }
        throttledAnalyzer(throttle).waveform(uri) { _, _ -> }

        assertThat(priorities).containsExactly(15, 15)
    }

    @Test
    fun `while the budget pauses on heat the analysis waits between chunks and goes on when the device cools`() = runBlocking<Unit> {
        var limits = DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, DeviceLoad(thermalThrottled = true, thermalLevel = ThermalLevel.SEVERE))
        var sleeps = 0
        val throttle = AnalysisThrottle(
            limits = { limits },
            setPriority = {},
            sleep = {
                if (++sleeps == 2) limits = DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, DeviceLoad())
            },
        )

        val result = throttledAnalyzer(throttle).full(uri, audioId(1)) { _, _ -> }

        assertThat(sleeps).isEqualTo(2)
        assertThat(result.value.grid).isNotNull()
    }

    @Test
    fun `a waiting analysis can still be cancelled`() = runBlocking<Unit> {
        val hot = DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, DeviceLoad(thermalThrottled = true, thermalLevel = ThermalLevel.SEVERE))
        var scopeJob: Job? = null
        // The device never cools: only the cancellation, seen at the poll after the sleep, can end the wait.
        val throttle = AnalysisThrottle(limits = { hot }, setPriority = {}, sleep = { scopeJob!!.cancel() })

        val outcome = runCatching {
            coroutineScope {
                scopeJob = coroutineContext.job
                throttledAnalyzer(throttle).full(uri, audioId(1)) { _, _ -> }
            }
        }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    private fun throttledAnalyzer(throttle: AnalysisThrottle) =
        AndroidDjTrackAnalyzer(decoder, NoModel, engines, log, Dispatchers.Unconfined, FakePipelineClock(), throttle)

    private object NoModel : BeatActivationModelFactory {
        override suspend fun create(preferred: Accelerator): BeatActivationModel? = null
    }

    private class CountingNoModel : BeatActivationModelFactory {
        var asked = 0

        override suspend fun create(preferred: Accelerator): BeatActivationModel? {
            asked++
            return null
        }
    }

    private class SingleModel(private val model: BeatActivationModel) : BeatActivationModelFactory {
        override suspend fun create(preferred: Accelerator): BeatActivationModel = model
    }

    /** Behaves like the ONNX Runtime model when the runtime fails: [IllegalStateException] from [infer]. */
    private class ThrowingModel : BeatActivationModel {
        val failure = IllegalStateException("ONNX Runtime failed")
        var closed = false

        override val descriptor = BeatModelDescriptor("fake", "1", 22_050, 128, 50.0, 1_500, 6, Accelerator.CPU)

        override fun infer(chunk: MelSpectrogram): ChunkLogits = throw failure

        override fun close() {
            closed = true
        }
    }
}
