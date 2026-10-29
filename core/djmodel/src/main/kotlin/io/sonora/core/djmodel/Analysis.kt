package io.sonora.core.djmodel

import io.sonora.core.domain.analysis.MusicalKey

/** The global key of a track with how sure the detector is ([strength] 0..1) and what found it ([method]). */
data class KeyEstimate(val key: MusicalKey, val strength: Double, val method: String) {
    init {
        require(strength in 0.0..1.0) { "strength must be 0..1: $strength" }
        require(method.isNotBlank()) { "method must not be blank" }
    }
}

/** Where the beat grid came from. */
enum class BeatSource { MODEL, QM_DSP }

/** What produced an analysis: [analyzerVersion] of the engine, the [beatSource], and the model and accelerator if any. */
data class AnalysisProvenance(
    val analyzerVersion: Int,
    val beatSource: BeatSource,
    val modelId: String? = null,
    val accelerator: Accelerator? = null,
    val analysedAtEpochMs: Long,
) {
    init {
        require(analyzerVersion >= 1) { "analyzerVersion must be positive: $analyzerVersion" }
        require((beatSource == BeatSource.MODEL) == (modelId != null)) { "A model id goes with the MODEL source and only with it" }
    }
}

/**
 * Everything the analyser finds in one track. [grid] is `null` for audio without a steady beat (ambient, speech); the
 * [key] is `null` when none was detected; the [waveform] is always there, except that an analysis restored from a
 * backup has [BandWaveform.MISSING] until the analyser recomputes it (see [waveformMissing]).
 *
 * [gridEdit] is the user's correction of the grid (`null` while the track is unedited). It is not stored with the
 * analysis: [DjAnalysisStore] always returns `null` here, and [DjTrackAnalyses] adds the edit it finds for the same
 * audio. [grid] always stays the analysed one; whatever shows or uses the beats (the DJ screens, the cues, the USB
 * export) must read [effectiveGrid].
 */
data class DjAnalysis(
    val durationMs: Long,
    val grid: DjBeatGrid?,
    val key: KeyEstimate?,
    val waveform: BandWaveform,
    val provenance: AnalysisProvenance,
    val gridEdit: GridEdit? = null,
) {
    init {
        require(durationMs >= 0) { "durationMs must not be negative: $durationMs" }
    }

    /** The grid in force: the user's edit when there is one, else the analysed [grid]. */
    val effectiveGrid: DjBeatGrid? = GridEdits.apply(grid, gridEdit)

    /** Whether the user has corrected the grid. */
    val isGridEdited: Boolean get() = gridEdit != null

    /** Whether a newer analyser than the one that made this analysis exists ([currentVersion]); nothing replaces it by itself. */
    fun isOlderThan(currentVersion: Int): Boolean = provenance.analyzerVersion < currentVersion

    /** This analysis with [edit] as its grid edit (`null` removes it). */
    fun withGridEdit(edit: GridEdit?): DjAnalysis = copy(gridEdit = edit)

    /**
     * Whether the waveform still has to be computed: the analysis came from a backup (which keeps the grid and the key
     * but no waveform, because it is recomputed deterministically from the audio) and the track has some audio. Such an
     * analysis is complete in everything else, so only the waveform stage needs to run for it.
     */
    val waveformMissing: Boolean get() = waveform.columnCount == 0 && durationMs > 0
}
