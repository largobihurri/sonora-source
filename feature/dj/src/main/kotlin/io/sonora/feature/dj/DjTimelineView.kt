package io.sonora.feature.dj

import io.sonora.core.djmodel.AnalysisFailureKind
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjBeatGrid

/**
 * The short status line in the time row under the DJ timeline, for the states that keep their waveform on screen: a track
 * analysed again (its stored analysis stays visible) or whose new run failed.
 */
sealed interface DjTimelineNotice {

    /** The track is being analysed again; [percent] is 0..99, and [paused] says the phone's heat has stopped it. */
    data class Reanalysing(val percent: Int, val paused: Boolean) : DjTimelineNotice

    /** The new analysis failed; the old one is untouched. */
    data object ReanalysisFailed : DjTimelineNotice
}

/**
 * The card on the waveform panel for the states that have no waveform to draw (or a hint to give about it): what it says,
 * and, where it has one, a button (Analyse again, Try again) the screen wires to the view model.
 */
sealed interface DjTimelinePanel {

    /** Nothing known yet (queued, or being loaded). */
    data object Waiting : DjTimelinePanel

    /** The track is being analysed; [percent] is 0..99 (100 only once it is stored, and then nothing is said). */
    data class Analysing(val percent: Int, val paused: Boolean) : DjTimelinePanel

    /** The grid is known but the waveform is being rebuilt ([percent] 0..99) or waits its turn (`null`). */
    data class WaveformMissing(val percent: Int?) : DjTimelinePanel

    /** The track has no steady beat: no grid, no tempo. The waveform is drawn under the card. */
    data object NoBeatGrid : DjTimelinePanel

    /** The analysis failed, for the reason [kind]. */
    data class Failed(val kind: AnalysisFailureKind) : DjTimelinePanel
}

/**
 * What the DJ timeline draws for a [DjTimelineState]: the [waveform] of the scrolling view and the overview
 * ([BandWaveform.MISSING], an empty strip that still seeks, while there is none), the beat [grid] in force (the user's
 * edit when there is one; `null` hides the ticks), the [analysis] it came from (the key for anything built once per analysis),
 * the [notice] of the time row (`null` when there is nothing to tell) and the [panel] card over the waveform (`null` when
 * the waveform is there to be seen).
 */
data class DjTimelineView(
    val waveform: BandWaveform,
    val grid: DjBeatGrid?,
    val analysis: DjAnalysis?,
    val notice: DjTimelineNotice?,
    val panel: DjTimelinePanel? = null,
) {
    /**
     * The track's own BPM (at normal speed) at [positionMs]: the grid's tempo there, which the ±0.1 BPM nudges scale
     * themselves; `null` without a grid.
     */
    fun trackBpmAt(positionMs: Long): Double? = grid?.bpmAt(positionMs.toDouble())

    /**
     * Whether the scrolling waveform draws its playhead: not under a [panel] card, which is drawn without a background and
     * would show the line through its text. The strip of a track without a beat sits along the bottom edge and leaves the
     * waveform, and so the playhead, in view.
     */
    val showsPlayhead: Boolean get() = panel == null || panel == DjTimelinePanel.NoBeatGrid

    companion object {
        private val EMPTY = DjTimelineView(BandWaveform.MISSING, grid = null, analysis = null, notice = null)

        fun of(state: DjTimelineState): DjTimelineView = when (state) {
            DjTimelineState.Disabled -> EMPTY
            DjTimelineState.Waiting -> EMPTY.copy(panel = DjTimelinePanel.Waiting)
            is DjTimelineState.Analysing -> EMPTY.copy(panel = DjTimelinePanel.Analysing(percentOf(state.progress), state.paused))
            is DjTimelineState.Ready -> DjTimelineView(
                state.analysis.waveform, state.analysis.effectiveGrid, state.analysis, noticeOf(state.reanalysis),
            )
            is DjTimelineState.ReadyWaveformMissing -> DjTimelineView(
                BandWaveform.MISSING,
                state.analysis.effectiveGrid,
                state.analysis,
                notice = null,
                panel = DjTimelinePanel.WaveformMissing(state.progress?.let(::percentOf)),
            )
            is DjTimelineState.NoBeatGrid -> DjTimelineView(
                state.analysis.waveform,
                grid = null,
                state.analysis,
                noticeOf(state.reanalysis),
                // While it is analysed again the card steps aside: the progress says what is going on.
                panel = DjTimelinePanel.NoBeatGrid.takeUnless { state.reanalysis is Reanalysis.Running },
            )
            is DjTimelineState.Failed -> EMPTY.copy(panel = DjTimelinePanel.Failed(state.kind))
        }

        private fun noticeOf(reanalysis: Reanalysis?): DjTimelineNotice? = when (reanalysis) {
            null -> null
            is Reanalysis.Running -> DjTimelineNotice.Reanalysing(percentOf(reanalysis.progress), reanalysis.paused)
            is Reanalysis.Failed -> DjTimelineNotice.ReanalysisFailed
        }

        /** Whole percent of [progress] (0..1), floored and kept under 100, as the header chip shows it. */
        private fun percentOf(progress: Float): Int = (progress * 100f).toInt().coerceIn(0, MAX_PERCENT)

        private const val MAX_PERCENT = 99
    }
}
