package io.sonora.core.domain.model

import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.PerformanceMode

/** One converter input file and where its output goes. */
data class ConversionRequest(
    /** Document URI of the source, as in `Track.uri`. */
    val sourceUri: String,
    /**
     * Writable document the output is written to (truncated first). The caller creates it, typically as a temporary
     * document, and renames or moves it into place only after [io.sonora.core.domain.port.AudioConverter.verify].
     */
    val outputUri: String,
    val profile: ConversionProfile = ConversionProfiles.DEFAULT,
    val performanceMode: PerformanceMode = PerformanceMode.DEFAULT,
)

/** What a finished conversion produced. */
data class ConversionResult(
    val plan: ConversionPlan,
    /** Frames written to the output. */
    val outputFrames: Long,
    /** Frames decoded from the source. */
    val sourceFrames: Long,
    /** Whether the output carries the source's tags (false when the profile drops them or the source had none). */
    val tagsWritten: Boolean = false,
) {
    val outputDurationUs: Long get() = outputFrames * 1_000_000L / plan.targetSampleRateHz
}

/** Whether a written output decodes back to what the conversion reported; required before replacing an original. */
sealed interface OutputVerification {
    data object Valid : OutputVerification

    data class Invalid(val reason: String, val cause: Throwable? = null) : OutputVerification
}
