package io.sonora.core.djengine.pipeline

import io.sonora.core.djengine.DJ_ANALYZER_VERSION
import io.sonora.core.djengine.beats.ActivationPeaks
import io.sonora.core.djengine.beats.BarPhaser
import io.sonora.core.djengine.beats.GridFitter
import io.sonora.core.djengine.beats.OnsetEnvelopeSink
import io.sonora.core.djengine.beats.PhaseRefiner
import io.sonora.core.djengine.key.ChromaFrames
import io.sonora.core.djengine.key.HpcpSink
import io.sonora.core.djengine.key.KeyClassifier
import io.sonora.core.djengine.mel.ChunkedInference
import io.sonora.core.djengine.mel.LogMelSink
import io.sonora.core.djengine.waveform.BandWaveformSink
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisTelemetry
import io.sonora.core.djmodel.AnalysisTimings
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.BeatActivationModelFactory
import io.sonora.core.djmodel.BeatActivations
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.ChunkLogits
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.MelSpectrogram
import io.sonora.core.djmodel.OnsetEnvelope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

/**
 * Turns the decoded audio of one track into a [DjAnalysis] in a single decode.
 *
 * ## One decode
 * The [PcmSource] is read block by block ([blockFrames] frames), each block is mixed down to mono once (the mean of
 * the channels) and handed to every stage's sink: the waveform, the chroma for the key, the log-mel front end of the
 * beat model, the onset envelope for the phase refinement and the fallback (qm-dsp) grid. A stage whose factory runs
 * at the source rate gets the mono blocks directly; any other rate gets a resampled copy (one [Resampler] stream per
 * distinct rate, shared by the stages at that rate; [PolyphaseResampling] by default). With the defaults only the mel
 * front end (22.05 kHz) is resampled: the waveform, chroma and onset sinks accept the source rate (see
 * [DEFAULT_WAVEFORM], [DEFAULT_ONSET]). Cancellation ([run]'s `checkCancelled`) is checked before every block and
 * before every model chunk.
 *
 * ## After the decode
 * 1. waveform: the sink's result;
 * 2. key: the chroma frames through [keyDetection] (edma by default);
 * 3. grid with a model: log-mel -> [ChunkedInference] -> [ActivationPeaks.candidates] -> [gridFit] (by default
 *    [DEFAULT_GRID_FIT]: the beats moved [MODEL_LATENCY_MS] earlier, then the constant fit of D1.10) ->
 *    [barPhasing] (D1.29, [DEFAULT_BAR_PHASING]: which beat is the "1", from the downbeat probabilities and the start
 *    of the track; only the bar position changes) -> [phaseRefinement] against the onset envelope when both are configured (none by default; [PhaseRefiner] and
 *    [OnsetEnvelopeSink] of D1.11 are [DEFAULT_PHASE_REFINEMENT] and [DEFAULT_ONSET]). A model that finds no beats
 *    gives no grid (`null`: ambient or silence); the fallback is not asked to overrule it;
 * 4. grid without a model ([BeatActivationModelFactory.create] returned `null` or threw) or when the model throws
 *    during inference: the fallback grid fed during the same decode (refined like a model grid). Under
 *    [FallbackPolicy.ONLY_WITHOUT_MODEL] the fallback is not fed while a model is open, so a model failure costs
 *    exactly one more decode (see the policy);
 * 5. provenance: [DJ_ANALYZER_VERSION], [BeatSource.MODEL] with `modelId` = `<descriptor id>/<version>` (e.g.
 *    `beatthis-small/1`) and the model's accelerator, or [BeatSource.QM_DSP] ([beatSourceId] `qmdsp/1`), and the
 *    clock's time.
 *
 * ## Progress, telemetry, failures
 * Progress rises monotonically from 0 to 1: by decoded frames while decoding (up to [DECODE_SHARE_WITH_MODEL] of the
 * way with a model, [DECODE_SHARE_WITHOUT_MODEL] without; nothing while the length is unknown), then by model chunk,
 * then 1 once the analysis is complete. The shares are planning values until D1.24 measures the device.
 *
 * Each stage's wall time is measured with [clock] ([AnalysisStage.DECODE] = reading and mixing down, a sink's blocks
 * and its finish count for its own stage, the fallback and onset sinks for [AnalysisStage.GRID]) and reported once
 * with [AnalysisTelemetry.analysed]; only stages that ran appear ([AnalysisStage.IDENTIFY] and [AnalysisStage.STORE]
 * belong to the caller). A failure the pipeline recovers from (the model failing to open or during inference) is
 * reported with [AnalysisTelemetry.failed] for [AnalysisStage.INFERENCE] and the analysis goes on; any other failure is
 * reported with the stage that was running and thrown. Cancellation (a [CancellationException] from `checkCancelled`,
 * the sinks or the model) is thrown as it is and reported nowhere.
 *
 * The pipeline opens one model per run (before the decode, so it knows whether the fallback is needed) and closes it
 * at the end; sinks and resampler streams that are [AutoCloseable] are closed at the end too, finished or not (a
 * fallback sink whose grid is not needed is never finished). Not thread-safe per run; separate runs may overlap.
 */
class DjAnalysisPipeline(
    private val modelFactory: BeatActivationModelFactory,
    private val fallbackGrid: StageSinks<DjBeatGrid?>,
    private val telemetry: AnalysisTelemetry,
    private val resampler: Resampler = PolyphaseResampling,
    private val clock: PipelineClock = PipelineClock.SYSTEM,
    private val waveform: StageSinks<BandWaveform> = DEFAULT_WAVEFORM,
    private val chroma: StageSinks<ChromaFrames> = DEFAULT_CHROMA,
    private val mel: StageSinks<MelSpectrogram> = DEFAULT_MEL,
    private val onset: StageSinks<OnsetEnvelope>? = DEFAULT_ONSET,
    private val gridFit: GridFit = DEFAULT_GRID_FIT,
    private val phaseRefinement: PhaseRefinement? = null,
    private val keyDetection: KeyDetection = DEFAULT_KEY_DETECTION,
    private val fallbackPolicy: FallbackPolicy = FallbackPolicy.ALWAYS,
    private val blockFrames: Int = DEFAULT_BLOCK_FRAMES,
    private val barPhasing: BarPhasing? = DEFAULT_BAR_PHASING,
) {
    init {
        require(blockFrames > 0) { "blockFrames must be positive: $blockFrames" }
    }

    /**
     * Analyses [pcm] (identified as [audioId]); see the class comment. [checkCancelled] throws a
     * [CancellationException] to stop (e.g. `coroutineContext.ensureActive()`); [onProgress] gets 0..1.
     */
    suspend fun run(
        pcm: PcmSource,
        audioId: AudioId,
        onProgress: (Float) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): DjAnalysis {
        require(pcm.sampleRateHz > 0 && pcm.channelCount > 0) {
            "Bad PCM format: ${pcm.sampleRateHz} Hz, ${pcm.channelCount} channels"
        }
        val timer = StageTimer(clock)
        val progress = ProgressMeter(onProgress)
        var reported: Throwable? = null
        var model: BeatActivationModel? = null
        val routers = ArrayList<SinkRouter>(2)
        try {
            checkCancelled()
            progress.report(0f)
            model = openModel(timer)
            val reportModelFailure = { failure: Throwable ->
                telemetry.failed(AnalysisStage.INFERENCE, failure)
                reported = failure
            }
            val analysis = Run(pcm, timer, progress, checkCancelled, model, routers, reportModelFailure).analyse()
            telemetry.analysed(audioId, AnalysisTimings(timer.stageMs()), analysis.provenance)
            progress.done()
            return analysis
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e !== reported) telemetry.failed(timer.current, e)
            throw e
        } finally {
            for (router in routers) router.closeAll()
            model?.let { runCatching { it.close() } }
        }
    }

    /** The model, or `null` when there is none or it cannot be opened (reported, not thrown). */
    private suspend fun openModel(timer: StageTimer): BeatActivationModel? {
        val start = timer.now()
        val model = try {
            modelFactory.create(Accelerator.CPU)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            telemetry.failed(AnalysisStage.INFERENCE, e)
            null
        }
        if (model != null) timer.add(AnalysisStage.INFERENCE, timer.now() - start)
        return model
    }

    /** The state of one [run]. */
    private inner class Run(
        private val pcm: PcmSource,
        private val timer: StageTimer,
        private val progress: ProgressMeter,
        private val checkCancelled: () -> Unit,
        private val model: BeatActivationModel?,
        private val routers: MutableList<SinkRouter>,
        private val reportModelFailure: (Throwable) -> Unit,
    ) {
        private val rate = pcm.sampleRateHz
        private val channels = pcm.channelCount
        private val buffer = FloatArray(blockFrames * channels)
        private val mono = FloatArray(blockFrames)
        private val decodeShare = if (model != null) DECODE_SHARE_WITH_MODEL else DECODE_SHARE_WITHOUT_MODEL

        /** Why the model failed during inference, when it did. */
        private var modelFailure: Throwable? = null

        fun analyse(): DjAnalysis {
            val router = newRouter()
            val waveformTap = router.tap(AnalysisStage.WAVEFORM, waveform.forSourceRate(rate))
            val chromaTap = router.tap(AnalysisStage.KEY, chroma.forSourceRate(rate))
            val melTap = model?.let { router.tap(AnalysisStage.MEL, mel.forSourceRate(rate)) }
            val onsetTap = onset?.takeIf { phaseRefinement != null }?.let { router.tap(AnalysisStage.GRID, it.forSourceRate(rate)) }
            val collectFallback = model == null || fallbackPolicy == FallbackPolicy.ALWAYS
            val fallbackTap = if (collectFallback) router.tap(AnalysisStage.GRID, fallbackGrid.forSourceRate(rate)) else null

            val frames = decode(router, reportProgress = true)
            val durationMs = frames * MS_PER_SECOND / rate

            val waveformResult = waveformTap.finish()
            val chromaFrames = chromaTap.finish()
            val key = timer.time(AnalysisStage.KEY) { keyDetection.detect(chromaFrames) }

            var source = BeatSource.QM_DSP
            var grid: DjBeatGrid? = null
            if (model != null && melTap != null) {
                val activations = infer(model, melTap.finish())
                if (activations != null) {
                    source = BeatSource.MODEL
                    grid = timer.time(AnalysisStage.GRID) {
                        val candidates = ActivationPeaks.candidates(activations)
                        gridFit.fit(candidates, durationMs)?.let { fitted ->
                            barPhasing?.rephase(fitted, candidates, waveformResult)?.grid ?: fitted
                        }
                    }
                }
            }
            if (source == BeatSource.QM_DSP) grid = if (fallbackTap != null) fallbackTap.finish() else redecodeFallback()
            if (grid != null && onsetTap != null && phaseRefinement != null) {
                val envelope = onsetTap.finish()
                val unrefined = grid
                grid = timer.time(AnalysisStage.GRID) { phaseRefinement.refine(unrefined, envelope) }
            }

            val descriptor = model?.descriptor.takeIf { source == BeatSource.MODEL }
            val provenance = AnalysisProvenance(
                analyzerVersion = DJ_ANALYZER_VERSION,
                beatSource = source,
                modelId = descriptor?.let { "${it.id}/${it.version}" },
                accelerator = descriptor?.accelerator,
                analysedAtEpochMs = clock.nowEpochMs(),
            )
            return DjAnalysis(durationMs.roundToLong(), grid, key, waveformResult, provenance)
        }

        private fun newRouter(): SinkRouter = SinkRouter(rate, resampler, timer).also { routers += it }

        /** Reads the whole source into [router] and flushes it; returns the frames decoded. */
        private fun decode(router: SinkRouter, reportProgress: Boolean): Long {
            val total = pcm.totalFrames?.takeIf { it > 0 }
            var decoded = 0L
            while (true) {
                checkCancelled()
                timer.current = AnalysisStage.DECODE
                val start = timer.now()
                val frames = pcm.read(buffer)
                if (frames > 0) downmix(frames)
                timer.add(AnalysisStage.DECODE, timer.now() - start)
                if (frames < 0) break
                if (frames == 0) continue
                router.accept(mono, frames)
                decoded += frames
                if (reportProgress && total != null) {
                    progress.report(decodeShare * (decoded.toFloat() / total).coerceAtMost(1f))
                }
            }
            router.flush()
            return decoded
        }

        private fun downmix(frames: Int) {
            check(frames <= blockFrames) { "The source wrote $frames frames into a buffer of $blockFrames" }
            if (channels == 1) {
                buffer.copyInto(mono, 0, 0, frames)
                return
            }
            val scale = 1f / channels
            var at = 0
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += buffer[at++]
                mono[f] = sum * scale
            }
        }

        /**
         * The model's activations, or `null` when it failed (reported, kept in [modelFailure]); cancellation is thrown.
         * Each chunk advances the progress and is preceded by a cancellation check.
         */
        private fun infer(model: BeatActivationModel, melSpectrogram: MelSpectrogram): BeatActivations? {
            val descriptor = model.descriptor
            val expectedChunks = ChunkedInference
                .chunkStarts(melSpectrogram.frameCount, descriptor.chunkFrames, descriptor.borderFrames).size
            val tracked = object : BeatActivationModel by model {
                private var done = 0

                override fun infer(chunk: MelSpectrogram): ChunkLogits {
                    checkCancelled()
                    val logits = model.infer(chunk)
                    done++
                    progress.report(decodeShare + (INFERENCE_END - decodeShare) * done / expectedChunks)
                    return logits
                }

                override fun close() = Unit
            }
            return try {
                timer.time(AnalysisStage.INFERENCE) { ChunkedInference.run(tracked, melSpectrogram) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportModelFailure(e)
                modelFailure = e
                null
            }
        }

        /** [FallbackPolicy.ONLY_WITHOUT_MODEL] after a model failure: a second decode for the fallback alone. */
        private fun redecodeFallback(): DjBeatGrid? {
            val cause = checkNotNull(modelFailure) { "The fallback grid was not collected" }
            if (!pcm.restart()) throw cause
            val router = newRouter()
            val tap = router.tap(AnalysisStage.GRID, fallbackGrid.forSourceRate(rate))
            decode(router, reportProgress = false)
            return tap.finish()
        }
    }

    companion object {
        const val DEFAULT_BLOCK_FRAMES: Int = 4_096

        /** How a fallback (qm-dsp) grid is labelled; a model grid is labelled by its `modelId`. */
        const val QM_DSP_SOURCE_ID: String = "qmdsp/1"

        /** Progress at the end of the decode when a model runs after it (inference dominates on the CPU). */
        const val DECODE_SHARE_WITH_MODEL: Float = 0.3f

        /** Progress at the end of the decode without a model. */
        const val DECODE_SHARE_WITHOUT_MODEL: Float = 0.95f

        /** Progress when the last model chunk is done. */
        const val INFERENCE_END: Float = 0.95f

        private const val MS_PER_SECOND = 1_000.0

        /**
         * How late the beat model's beats sit after rekordbox's grid, taken off by [DEFAULT_GRID_FIT]. D1.20 measured
         * the per-track median offset on 30 reference tracks (constant grids, our decoder time base aligned with
         * rekordbox's): +7.8 ms over all of them, +11.2 ms over the 22 with the right tempo (D1.18 saw +10 to +13 ms on
         * synthetic clicks). Taking 10 ms off moved that median to -1.3 ms, cut the median |offset| from 14.4 to
         * 7.1 ms and raised the F-measure at +-10 ms from 0.318 to 0.562 (8 and 12 ms do about as well).
         */
        const val MODEL_LATENCY_MS: Double = 10.0

        /**
         * The waveform at the source rate when the sink supports it (16..192 kHz), else 44.1 kHz: columns are defined
         * in time (150 per second) at any rate, and running at the source rate saves a resampling pass.
         */
        val DEFAULT_WAVEFORM: StageSinks<BandWaveform> = StageSinks { rate ->
            val supported = rate in BandWaveformSink.MIN_SAMPLE_RATE_HZ..BandWaveformSink.MAX_SAMPLE_RATE_HZ
            BandWaveformSink(if (supported) rate else BandWaveformSink.DEFAULT_SAMPLE_RATE_HZ)
        }

        /** The HPCP chroma at the source rate when the sink supports it (8..192 kHz), else 44.1 kHz. */
        val DEFAULT_CHROMA: StageSinks<ChromaFrames> = StageSinks { rate ->
            HpcpSink(if (rate in HPCP_MIN_RATE_HZ..HPCP_MAX_RATE_HZ) rate else HpcpSink.DEFAULT_SAMPLE_RATE_HZ)
        }

        /** The Beat This! log-mel front end; always 22.05 kHz, so it is the stage that gets a resampled copy. */
        val DEFAULT_MEL: StageSinks<MelSpectrogram> = StageSinks { LogMelSink() }

        /**
         * The low-band onset envelope (D1.11) at the source rate when the sink supports it (4..192 kHz), else its
         * 16 kHz default: its 40..200 Hz band-pass costs less per sample than a polyphase resampling pass, and its
         * transient timing is sample-exact at any rate.
         */
        val DEFAULT_ONSET: StageSinks<OnsetEnvelope> = StageSinks { rate ->
            val supported = rate in OnsetEnvelopeSink.MIN_SAMPLE_RATE_HZ..OnsetEnvelopeSink.MAX_SAMPLE_RATE_HZ
            OnsetEnvelopeSink(if (supported) rate else OnsetEnvelopeSink.DEFAULT_SAMPLE_RATE_HZ)
        }

        /**
         * The beats moved [MODEL_LATENCY_MS] earlier, then [GridFitter.fitConstant]: ONE tempo per track. D1.20 chose
         * it over [GridFitter.fit] (which adds the tempo segments of D1.12 when the beats drift) on 30 reference
         * tracks: on the beat model's real output the drift test split 20 of the 25 constant-tempo tracks (up to 79
         * segments), and the constant fit raised the mean beat F-measure from 0.657 to 0.774 (median 0.746 to 0.998)
         * and the downbeat accuracy from 0.339 to 0.605; even on the 5 tracks rekordbox gives tempo changes it scored
         * better (0.557 to 0.679). Segmentation stays available as [GridFitter.fit] for a later, better beat model.
         */
        val DEFAULT_GRID_FIT: GridFit = GridFit { candidates, durationMs ->
            GridFitter.fitConstant(candidates.shiftedBy(-MODEL_LATENCY_MS), durationMs)
        }

        /**
         * The onset phase refinement of D1.11, NOT used by default: D1.20 found that on real kicks the low-band
         * envelope peaks 30 to 40 ms after rekordbox's beat, so the refiner moved well-placed grids late (median
         * offset per track +7.8 to +12.7 ms, 90th percentile 18 to 43 ms, F-measure at +-25 ms 0.679 to 0.492).
         */
        val DEFAULT_PHASE_REFINEMENT: PhaseRefinement = PhaseRefinement { grid, envelope -> PhaseRefiner.refine(grid, envelope) }

        /**
         * [BarPhaser] with its default options on the candidates moved [MODEL_LATENCY_MS] earlier (the same time base as
         * [DEFAULT_GRID_FIT]'s grid): the model's downbeat probabilities, with the start of the track as a prior. D1.29
         * measured it on the 150 reference tracks of the D1.20 calibration (rekordbox's bar numbers as the reference):
         * the downbeat accuracy rose from 0.606 and the bar phase was right on more of the tracks with accurate beats
         * (F above 0.9); see `odd/tasks/dj-mode.md`.
         */
        val DEFAULT_BAR_PHASING: BarPhasing = BarPhasing { grid, candidates, waveform ->
            BarPhaser.rephase(grid, candidates.shiftedBy(-MODEL_LATENCY_MS), waveform)
        }

        /** [candidates] with every beat and downbeat moved by [deltaMs]. */
        private fun BeatCandidates.shiftedBy(deltaMs: Double): BeatCandidates {
            val beats = beatsMs()
            val downbeats = downbeatsMs()
            for (i in beats.indices) beats[i] += deltaMs
            for (i in downbeats.indices) downbeats[i] += deltaMs
            return BeatCandidates(beats, downbeats, downbeatProbabilities())
        }
        val DEFAULT_KEY_DETECTION: KeyDetection = KeyDetection { KeyClassifier.classify(it) }

        private const val HPCP_MIN_RATE_HZ = 8_000
        private const val HPCP_MAX_RATE_HZ = 192_000
    }
}

/** What found the beat grid: the model's id (`beatthis-small/1`) or [DjAnalysisPipeline.QM_DSP_SOURCE_ID]. */
val AnalysisProvenance.beatSourceId: String get() = modelId ?: DjAnalysisPipeline.QM_DSP_SOURCE_ID
