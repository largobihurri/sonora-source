package io.sonora.core.analysis

import io.sonora.core.domain.analysis.AnalysisException

/**
 * One analysis run over one track's mono samples (the native analyser in the app, a fake in tests). Blocking and
 * not thread-safe; always [close] it, also after a failure.
 */
interface AnalysisEngine : AutoCloseable {

    /**
     * Feeds the next [count] samples of [samples] (from index 0).
     *
     * @throws AnalysisException when the engine fails.
     */
    fun process(samples: FloatArray, count: Int)

    /**
     * Ends the input and returns the result. Callable once.
     *
     * @throws AnalysisException when the audio cannot be analysed (too short, no beats) or the engine fails.
     */
    fun finish(): EngineResult

    /** Releases the engine. Idempotent. */
    override fun close()
}

/** Creates an [AnalysisEngine] for mono audio at a sample rate. */
fun interface AnalysisEngineFactory {

    /** @throws AnalysisException.UnsupportedSampleRate when the engine cannot analyse [sampleRateHz]. */
    fun create(sampleRateHz: Int): AnalysisEngine
}

/** What an [AnalysisEngine] found, in frames of the analysed stream. */
data class EngineResult(
    val bpm: Double,
    /** The earliest grid beat at or after frame 0. */
    val firstBeatFrame: Double,
    /** Position of that beat in its bar, 1..4. */
    val firstBeatInBar: Int,
    /** qm-dsp key index 1..24, or 0 when no key was found. */
    val keyIndex: Int,
    val totalFrames: Long,
    /** Beats found by the tracker before the grid was fitted (diagnostics). */
    val detectedBeats: Int,
)
