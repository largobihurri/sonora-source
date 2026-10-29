package io.sonora.core.domain.analysis

/**
 * What the analyser found in one track: tempo and beat grid, global key, blue waveforms and the decoded length.
 *
 * [analyzerVersion] identifies the algorithm and its parameters, so cached results can be recomputed when it changes.
 */
data class TrackAnalysis(
    val beatGrid: BeatGrid,
    /** The key that holds for most of the track; `null` when none was detected (silence, pure percussion). */
    val key: MusicalKey?,
    /** Detail and preview waveforms, from the same decode as the grid and key. */
    val waveform: BlueWaveform,
    /** Length of the decoded audio. */
    val durationMs: Long,
    val analyzerVersion: String,
) {
    /** The constant tempo of the track, taken from its [beatGrid]. */
    val bpm: Double get() = beatGrid.bpm
}
