package io.sonora.core.djanalysis

import io.sonora.core.analysis.AnalysisEngineFactory
import io.sonora.core.djengine.pipeline.DjAnalysisPipeline
import io.sonora.core.djengine.pipeline.PipelineClock
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisTelemetry
import io.sonora.core.djmodel.AnalysisTimings
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.BeatActivationModelFactory
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjTrackAnalyzer
import io.sonora.core.domain.analysis.AnalysisException
import io.sonora.core.domain.audio.PcmDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The app's [DjTrackAnalyzer]: the decoder ([DecoderPcmSource]) feeding a [DjAnalysisPipeline] with its default sinks
 * (3-band waveform, HPCP chroma and the edma key, the Beat This! log-mel front end, the onset envelope), the beat model
 * of [modelFactory] (ONNX Runtime on the CPU in the app), [GridFitter][io.sonora.core.djengine.beats.GridFitter] with
 * phase refinement, and the qm-dsp engine of [engines] as the fallback grid ([QmDspGridSink], fed during the same decode:
 * [io.sonora.core.djengine.pipeline.FallbackPolicy.ALWAYS]), on [dispatcher].
 *
 * [throttle] keeps it within the performance in force: the thread takes the budget's priority when a track starts and
 * waits between chunks while the budget pauses on heat.
 *
 * It never stores: [DjAnalysisRunner] does. Failures the pipeline recovers from (no model, a model failing during
 * inference) and failures it throws are logged to [log] as they happen.
 */
class AndroidDjTrackAnalyzer(
    private val decoder: PcmDecoder,
    private val modelFactory: BeatActivationModelFactory,
    private val engines: AnalysisEngineFactory,
    private val log: DjAnalysisLog,
    private val dispatcher: CoroutineDispatcher,
    private val clock: PipelineClock = PipelineClock.SYSTEM,
    private val throttle: AnalysisThrottle = AnalysisThrottle.UNLIMITED,
) : DjTrackAnalyzer, AnalysisPasses {

    override suspend fun analyze(
        sourceUri: String,
        audioId: AudioId,
        onProgress: (stage: AnalysisStage, fraction: Float) -> Unit,
    ): DjAnalysis = full(sourceUri, audioId, onProgress).value

    override suspend fun full(
        sourceUri: String,
        audioId: AudioId,
        onProgress: (AnalysisStage, Float) -> Unit,
    ): PassResult<DjAnalysis> = withContext(dispatcher) {
        val context = currentCoroutineContext()
        throttle.beginTrack()
        val openStart = clock.monotonicNanos()
        DecoderPcmSource.open(decoder, sourceUri).use { pcm ->
            val openNanos = clock.monotonicNanos() - openStart
            val telemetry = CapturingTelemetry(log)
            val models = TrackingFactory(modelFactory)
            val pipeline = DjAnalysisPipeline(models, QmDspGridSink(engines), telemetry, clock = clock)
            val analysis = pipeline.run(
                pcm,
                audioId,
                onProgress = { fraction -> onProgress(PipelineProgress.stageAt(fraction, models.opened), fraction) },
                checkCancelled = { throttle.checkpoint { context.ensureActive() } },
            )
            val stageMs = withDecoderOpen(telemetry.timings?.stageMs.orEmpty(), openNanos)
            PassResult(analysis, stageMs, analysis.durationMs, analysis.provenance)
        }
    }

    override suspend fun waveform(sourceUri: String, onProgress: (AnalysisStage, Float) -> Unit): PassResult<BandWaveform> =
        withContext(dispatcher) {
            val context = currentCoroutineContext()
            throttle.beginTrack()
            val openStart = clock.monotonicNanos()
            DecoderPcmSource.open(decoder, sourceUri).use { pcm ->
                val openNanos = clock.monotonicNanos() - openStart
                val pass = WaveformOnlyPass(clock = clock).run(
                    pcm,
                    onProgress = { fraction -> onProgress(if (fraction < 1f) AnalysisStage.DECODE else AnalysisStage.WAVEFORM, fraction) },
                    checkCancelled = { throttle.checkpoint { context.ensureActive() } },
                )
                if (pass.value.columnCount == 0) throw AnalysisException.TooShort(pass.audioMs, MIN_WAVEFORM_MS)
                PassResult(pass.value, withDecoderOpen(pass.stageMs, openNanos), pass.audioMs)
            }
        }

    /** Opening the decoder (the codec set-up) counts as decoding. */
    private fun withDecoderOpen(stageMs: Map<AnalysisStage, Long>, openNanos: Long): Map<AnalysisStage, Long> {
        val out = LinkedHashMap(stageMs)
        out[AnalysisStage.DECODE] = (out[AnalysisStage.DECODE] ?: 0L) + (openNanos + NANOS_PER_MS / 2) / NANOS_PER_MS
        return out
    }

    /** Keeps the pipeline's timings of one run and logs its failures as they happen. */
    private class CapturingTelemetry(private val log: DjAnalysisLog) : AnalysisTelemetry {
        var timings: AnalysisTimings? = null
            private set

        override fun analysed(audioId: AudioId, timings: AnalysisTimings, provenance: AnalysisProvenance) {
            this.timings = timings
        }

        override fun failed(stage: AnalysisStage, cause: Throwable) {
            val what = if (stage == AnalysisStage.INFERENCE) "beat model failed (the qm-dsp grid is used)" else "analysis failed"
            log.warn("$what: stage=$stage ${cause::class.java.simpleName}: ${cause.message}", cause)
        }
    }

    /** Remembers whether this run got a model, so the progress can be labelled. */
    private class TrackingFactory(private val delegate: BeatActivationModelFactory) : BeatActivationModelFactory {
        @Volatile
        var opened = false
            private set

        override suspend fun create(preferred: Accelerator): BeatActivationModel? =
            delegate.create(preferred).also { opened = it != null }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L

        /** One waveform column (1/150 s): audio shorter than that has no waveform to store. */
        const val MIN_WAVEFORM_MS = 7L
    }
}
