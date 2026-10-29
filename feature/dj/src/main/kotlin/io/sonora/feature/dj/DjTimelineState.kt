package io.sonora.feature.dj

import io.sonora.core.djmodel.AnalysisFailureKind
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.DjAnalysis

/**
 * A new analysis of a track that already has a stored one (the user asked for it): the stored analysis stays on screen
 * while it [Running] (with its progress, and whether the heat has [Running.paused] it), or the new run [Failed].
 */
sealed interface Reanalysis {

    /** The new run is going, [progress] (0..1) done. */
    data class Running(val progress: Float, val paused: Boolean) : Reanalysis

    /** The new run failed; the stored analysis is untouched. */
    data class Failed(val kind: AnalysisFailureKind) : Reanalysis
}

/** What the DJ timeline (the waveform area) has to show for the playing track. */
sealed interface DjTimelineState {

    /** DJ mode is off. */
    data object Disabled : DjTimelineState

    /** Nothing is known yet: nothing plays, the track is queued for analysis, or its analysis is being loaded. */
    data object Waiting : DjTimelineState

    /**
     * The track is being analysed and nothing of it is stored yet; [progress] is 0..1, and [paused] says the analysis
     * stands still because the phone is hot.
     */
    data class Analysing(val progress: Float, val paused: Boolean = false) : DjTimelineState

    /** The analysis is complete: beat grid and waveform. A new run asked for by the user shows in [reanalysis]. */
    data class Ready(val analysis: DjAnalysis, val reanalysis: Reanalysis? = null) : DjTimelineState

    /**
     * The analysis is stored without its waveform (restored from a backup); the waveform is being computed when
     * [progress] (0..1) is not `null`, and still to be scheduled otherwise.
     */
    data class ReadyWaveformMissing(val analysis: DjAnalysis, val progress: Float?) : DjTimelineState

    /** The track has a waveform but no steady beat (ambient, speech): no grid is drawn and there is no tempo. */
    data class NoBeatGrid(val analysis: DjAnalysis, val reanalysis: Reanalysis? = null) : DjTimelineState

    /**
     * The analysis failed and nothing usable is stored; [reason] is the analyser's own text (for the log, not the user)
     * and [kind] what the user can be told.
     */
    data class Failed(val reason: String, val kind: AnalysisFailureKind = AnalysisFailureKind.OTHER) : DjTimelineState

    companion object {
        /**
         * The state for [status] (the analysis state of the playing track, `null` while not reported) and [analysis] (the
         * stored one, `null` while there is none).
         *
         * A stored analysis shows whatever the status says, because it is already good (a new run or a failed new run
         * does not blank the screen: it only adds a [Reanalysis] to the state), with two exceptions: a missing waveform is
         * shown as such, with its progress while the waveform is computed and as a failure if that run failed. Without an
         * analysis the status decides. [pausedByHeat] marks the analysing states as paused.
         */
        fun of(enabled: Boolean, status: AnalysisStatus?, analysis: DjAnalysis?, pausedByHeat: Boolean = false): DjTimelineState = when {
            !enabled -> Disabled
            analysis == null -> withoutAnalysis(status, pausedByHeat)
            analysis.waveformMissing -> when (status) {
                is AnalysisStatus.Failed -> Failed(status.reason, status.kind)
                is AnalysisStatus.Analysing -> ReadyWaveformMissing(analysis, progressOf(status))
                else -> ReadyWaveformMissing(analysis, progress = null)
            }
            analysis.effectiveGrid == null -> NoBeatGrid(analysis, reanalysisOf(status, pausedByHeat))
            else -> Ready(analysis, reanalysisOf(status, pausedByHeat))
        }

        private fun withoutAnalysis(status: AnalysisStatus?, paused: Boolean): DjTimelineState = when (status) {
            is AnalysisStatus.Analysing -> Analysing(progressOf(status), paused)
            is AnalysisStatus.Failed -> Failed(status.reason, status.kind)
            else -> Waiting
        }

        /** The new run behind a stored analysis: a replacing run in progress, or a failure; any other status says nothing. */
        private fun reanalysisOf(status: AnalysisStatus?, paused: Boolean): Reanalysis? = when {
            status is AnalysisStatus.Analysing && status.replacing -> Reanalysis.Running(progressOf(status), paused)
            status is AnalysisStatus.Failed -> Reanalysis.Failed(status.kind)
            else -> null
        }

        private fun progressOf(status: AnalysisStatus.Analysing): Float =
            if (status.progress.isNaN()) 0f else status.progress.coerceIn(0f, 1f)
    }
}

/** The stored analysis this state shows, whatever its waveform or beat grid is doing; `null` while there is none. */
val DjTimelineState.analysis: DjAnalysis?
    get() = when (this) {
        is DjTimelineState.Ready -> analysis
        is DjTimelineState.ReadyWaveformMissing -> analysis
        is DjTimelineState.NoBeatGrid -> analysis
        DjTimelineState.Disabled, DjTimelineState.Waiting, is DjTimelineState.Analysing, is DjTimelineState.Failed -> null
    }
