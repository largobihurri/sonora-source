package io.sonora.feature.dj

import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.GridEdit

/**
 * What the DJ header knows about the playing entry: its [uri], the id of its [queueItemId] (the same track queued twice
 * is two entries, so a screen restarts its work per entry), its [durationMs] and whether it [isPlaying].
 */
data class DjHeader(val uri: String?, val queueItemId: Long?, val durationMs: Long, val isPlaying: Boolean) {
    companion object {
        /** Nothing is playing. */
        val NONE = DjHeader(uri = null, queueItemId = null, durationMs = 0L, isPlaying = false)
    }
}

/**
 * Everything the DJ screen shows apart from the moving parts. The playback position is not in it, because it changes
 * every frame: the waveform reads it from the playback clock. The [readout] is the one at the start of the track for a
 * constant tempo and follows the playback position (as the player reports it) only for a track whose tempo changes. It
 * has no playback tempo in it either, which changes with every step of a fader drag: the tempo controls apply theirs
 * ([DjTrackReadout.at]).
 */
data class DjUiState(
    val header: DjHeader,
    val timeline: DjTimelineState,
    val readout: DjTrackReadout?,
    /** The stored analysis comes from an older analyser than this build's: the screen offers to update it (D2.11). */
    val analysisOutdated: Boolean = false,
) {

    /**
     * Whether "Analyse again" can be asked now: the playing track has settled (analysed, without a beat grid or failed),
     * and no run is going or waiting for it, as the track being analysed for the first time will be anyway.
     */
    val canReanalyse: Boolean
        get() = header.uri != null && when (val state = timeline) {
            is DjTimelineState.Ready -> state.reanalysis !is Reanalysis.Running
            is DjTimelineState.NoBeatGrid -> state.reanalysis !is Reanalysis.Running
            is DjTimelineState.ReadyWaveformMissing -> state.progress == null
            is DjTimelineState.Failed -> true
            DjTimelineState.Disabled, DjTimelineState.Waiting, is DjTimelineState.Analysing -> false
        }

    /** The analysis of the playing track when its grid can be edited (it has one, with or without a beat grid), else `null`. */
    val editableAnalysis: DjAnalysis? get() = timeline.analysis

    /** The user's grid edit of the playing track, `null` while it is unedited. */
    val gridEdit: GridEdit? get() = timeline.analysis?.gridEdit

    companion object {
        /** DJ mode off. */
        val OFF = DjUiState(DjHeader.NONE, DjTimelineState.Disabled, readout = null)

        /** DJ mode on, nothing known yet. */
        val WAITING = DjUiState(DjHeader.NONE, DjTimelineState.Waiting, readout = null)
    }
}
