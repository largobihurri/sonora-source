package io.sonora.core.djengine.pipeline

import io.sonora.core.djengine.beats.BarPhaseResult
import io.sonora.core.djengine.key.ChromaFrames
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.djmodel.MonoSink
import io.sonora.core.djmodel.MonoSinkFactory
import io.sonora.core.djmodel.OnsetEnvelope

/**
 * Streaming sample-rate conversion of mono audio, injected so the pipeline does not depend on a particular resampler;
 * [PolyphaseResampling] (the polyphase resampler of `:core:audiodsp`) is the default.
 */
fun interface Resampler {

    /**
     * A sink that takes mono audio at [inputRateHz], converts it to [outputRateHz] and passes every converted block to
     * [target]'s [MonoSink.accept]. Its [MonoSink.finish] flushes the conversion (the last output samples reach
     * [target]) and does NOT finish [target]: the pipeline does that itself.
     */
    fun into(target: MonoSink<*>, inputRateHz: Int, outputRateHz: Int): MonoSink<Unit>
}

/**
 * Chooses the sink factory of one analysis stage once the decoded sample rate is known, so a stage that can run at any
 * rate (the waveform, the chroma) runs at the source rate and only the stages with a fixed rate (the log-mel front end,
 * 22.05 kHz) get a resampled copy. The factory's own `sampleRateHz` decides: equal to [sourceRateHz] means the sink is
 * fed the decoded mono directly, anything else means through a [Resampler].
 */
fun interface StageSinks<out R> {

    fun forSourceRate(sourceRateHz: Int): MonoSinkFactory<R>
}

/** Fits a grid to the beat candidates of a track of [durationMs]; `null` when there is no steady beat. */
fun interface GridFit {

    fun fit(candidates: BeatCandidates, durationMs: Double): DjBeatGrid?
}

/**
 * Decides the bar phase of a grid that [GridFit] fitted to the model's [candidates] (the "1" of the bars), from their
 * downbeat probabilities and the start of the track in [waveform] (D1.29). Only the bar position of the grid may
 * change; the default is [DjAnalysisPipeline.DEFAULT_BAR_PHASING].
 */
fun interface BarPhasing {

    fun rephase(grid: DjBeatGrid, candidates: BeatCandidates, waveform: BandWaveform): BarPhaseResult
}

/** Moves a fitted grid onto the onsets of [envelope] (phase refinement, D1.11); returns [grid] when it changes nothing. */
fun interface PhaseRefinement {

    fun refine(grid: DjBeatGrid, envelope: OnsetEnvelope): DjBeatGrid
}

/** The global key of a track from its chroma frames, or `null` when there is none. */
fun interface KeyDetection {

    fun detect(chroma: ChromaFrames): KeyEstimate?
}

/** Wall and monotonic time for the pipeline; [SYSTEM] in production, a fake in tests. */
interface PipelineClock {

    /** Milliseconds since the epoch, stored as `AnalysisProvenance.analysedAtEpochMs`. */
    fun nowEpochMs(): Long

    /** A monotonic reading in nanoseconds, for the stage timings. */
    fun monotonicNanos(): Long

    companion object {
        val SYSTEM: PipelineClock = object : PipelineClock {
            override fun nowEpochMs(): Long = System.currentTimeMillis()

            override fun monotonicNanos(): Long = System.nanoTime()
        }
    }
}

/** When the pipeline feeds the fallback (qm-dsp) grid sink during the decode. */
enum class FallbackPolicy {

    /**
     * Always, model or not: if the model fails during inference the fallback grid is already there and the track is
     * decoded once. Costs the fallback's per-block work on every track.
     */
    ALWAYS,

    /**
     * Only when no model could be opened. If the model then fails during inference the fallback was not collected, so
     * the pipeline decodes the track a SECOND time, feeding only the fallback sink, after [PcmSource.restart]; when the
     * source cannot restart, the inference failure is thrown.
     */
    ONLY_WITHOUT_MODEL,
}
