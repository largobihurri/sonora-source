package io.sonora.core.analysis

import io.sonora.core.domain.analysis.AnalysisException

/** Decodes what the JNI bridge returns: SA_* status codes (sonora_analyzer.h) and the result array (jni_bridge.cpp). */
internal object NativeResults {

    /** Length of the result array: bpm, first beat frame, first beat in bar, key, total frames, detected beats. */
    const val SIZE = 6

    /** Mirrors SA_MIN_DURATION_MS. */
    const val MIN_DURATION_MS = 5_000L

    private const val ERR_OUT_OF_MEMORY = -3
    private const val ERR_UNSUPPORTED_SAMPLE_RATE = -5
    private const val ERR_TOO_SHORT = -6
    private const val ERR_NO_BEATS = -7

    fun failure(status: Int, sampleRateHz: Int, framesProcessed: Long): AnalysisException = when (status) {
        ERR_UNSUPPORTED_SAMPLE_RATE -> AnalysisException.UnsupportedSampleRate(sampleRateHz)
        ERR_TOO_SHORT -> AnalysisException.TooShort(framesProcessed * 1_000 / sampleRateHz, MIN_DURATION_MS)
        ERR_NO_BEATS -> AnalysisException.NoBeatsDetected()
        ERR_OUT_OF_MEMORY -> AnalysisException.EngineFailure("native analyser out of memory (status $status)")
        else -> AnalysisException.EngineFailure("native analyser status $status")
    }

    fun parse(values: DoubleArray): EngineResult {
        require(values.size == SIZE) { "Expected $SIZE result values, got ${values.size}" }
        return EngineResult(
            bpm = values[0],
            firstBeatFrame = values[1],
            firstBeatInBar = values[2].toInt(),
            keyIndex = values[3].toInt(),
            totalFrames = values[4].toLong(),
            detectedBeats = values[5].toInt(),
        )
    }
}
