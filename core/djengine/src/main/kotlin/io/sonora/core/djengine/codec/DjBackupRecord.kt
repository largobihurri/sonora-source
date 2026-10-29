package io.sonora.core.djengine.codec

import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.KeyEstimate

/**
 * What a backup keeps of one stored analysis: everything except the waveform, which is recomputed deterministically
 * from the audio. [grid] and [key] are `null` when the analysis found none (beatless audio, no key).
 */
data class DjBackupRecord(
    val audioId: AudioId,
    val sampleRate: Int,
    val totalFrames: Long,
    val grid: DjBeatGrid?,
    val key: KeyEstimate?,
    val provenance: AnalysisProvenance,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(totalFrames >= 0) { "totalFrames must not be negative: $totalFrames" }
    }
}
