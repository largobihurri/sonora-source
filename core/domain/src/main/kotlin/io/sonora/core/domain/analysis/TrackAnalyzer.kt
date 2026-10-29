package io.sonora.core.domain.analysis

/**
 * Analyses one track for DJ use: tempo, a constant beat grid with downbeats, and the global musical key.
 *
 * Analysis decodes the whole file and is CPU-heavy (seconds per track on a phone); [analyze] is a cancellable
 * suspending call that does the work on the implementation's own dispatcher.
 */
interface TrackAnalyzer {

    /**
     * Analyses [sourceUri] (a document URI string, as in `Track.uri`), reporting the fraction done (0..1) to
     * [onProgress] from the analysing thread as it goes.
     *
     * @throws io.sonora.core.domain.audio.PcmDecodeException when the source cannot be opened or decoded.
     * @throws AnalysisException when the audio decodes but cannot be analysed.
     */
    suspend fun analyze(sourceUri: String, onProgress: (Float) -> Unit = {}): TrackAnalysis
}

/** Why decoded audio could not be analysed. Cancellation is never reported as one of these. */
sealed class AnalysisException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The track is shorter than the analyser needs to find a tempo. */
    class TooShort(val durationMs: Long, val minimumMs: Long) :
        AnalysisException("Track too short to analyse: $durationMs ms (minimum $minimumMs ms)")

    /** No steady beat was found (silence, ambient or beatless material). */
    class NoBeatsDetected : AnalysisException("No steady beat detected")

    /** The decoded sample rate is outside what the analyser supports. */
    class UnsupportedSampleRate(val sampleRateHz: Int) :
        AnalysisException("Unsupported sample rate for analysis: $sampleRateHz Hz")

    /** The analysis engine failed unexpectedly (out of memory, internal error). */
    class EngineFailure(val detail: String, cause: Throwable? = null) :
        AnalysisException("Analysis failed: $detail", cause)
}
