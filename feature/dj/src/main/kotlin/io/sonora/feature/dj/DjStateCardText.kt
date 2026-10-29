package io.sonora.feature.dj

import androidx.annotation.StringRes
import io.sonora.core.djmodel.AnalysisFailureKind

/** Which strings the card on the DJ waveform shows for a [DjTimelinePanel]; pure, so the choice is unit tested. */
internal object DjStateCardText {

    @StringRes
    fun title(panel: DjTimelinePanel): Int = when (panel) {
        DjTimelinePanel.Waiting -> R.string.dj_state_waiting_title
        is DjTimelinePanel.Analysing -> if (panel.paused) R.string.dj_state_paused_title else R.string.dj_state_analysing_title
        is DjTimelinePanel.WaveformMissing ->
            if (panel.percent == null) R.string.dj_state_waveform_pending else R.string.dj_state_waveform_title
        DjTimelinePanel.NoBeatGrid -> R.string.dj_state_no_grid_title
        is DjTimelinePanel.Failed -> failure(panel.kind)
    }

    @StringRes
    fun hint(panel: DjTimelinePanel): Int = when (panel) {
        DjTimelinePanel.Waiting -> R.string.dj_state_waiting_hint
        is DjTimelinePanel.Analysing -> if (panel.paused) R.string.dj_state_paused_hint else R.string.dj_state_analysing_hint
        is DjTimelinePanel.WaveformMissing -> R.string.dj_state_waveform_hint
        DjTimelinePanel.NoBeatGrid -> R.string.dj_state_no_grid_hint
        is DjTimelinePanel.Failed -> R.string.dj_state_failed_hint
    }

    /** The button of the card, if it has one: Analyse again for a track without a beat, Try again after a failure. */
    @StringRes
    fun action(panel: DjTimelinePanel): Int? = when (panel) {
        DjTimelinePanel.NoBeatGrid -> R.string.dj_state_analyse_again
        is DjTimelinePanel.Failed -> R.string.dj_state_try_again
        DjTimelinePanel.Waiting, is DjTimelinePanel.Analysing, is DjTimelinePanel.WaveformMissing -> null
    }

    @StringRes
    fun actionDescription(panel: DjTimelinePanel): Int? = when (panel) {
        DjTimelinePanel.NoBeatGrid -> R.string.dj_state_analyse_again_description
        is DjTimelinePanel.Failed -> R.string.dj_state_try_again_description
        DjTimelinePanel.Waiting, is DjTimelinePanel.Analysing, is DjTimelinePanel.WaveformMissing -> null
    }

    /** The percent the card's bar shows (0..99), `null` for a card without a bar fill (waiting, no percent known). */
    fun percent(panel: DjTimelinePanel): Int? = when (panel) {
        is DjTimelinePanel.Analysing -> panel.percent
        is DjTimelinePanel.WaveformMissing -> panel.percent
        else -> null
    }

    /** Whether the card's bar is paused, so it stops moving. */
    fun paused(panel: DjTimelinePanel): Boolean = panel is DjTimelinePanel.Analysing && panel.paused

    @StringRes
    private fun failure(kind: AnalysisFailureKind): Int = when (kind) {
        AnalysisFailureKind.UNREADABLE -> R.string.dj_state_failed_unreadable
        AnalysisFailureKind.UNSUPPORTED -> R.string.dj_state_failed_unsupported
        AnalysisFailureKind.CORRUPT -> R.string.dj_state_failed_corrupt
        AnalysisFailureKind.TOO_SHORT -> R.string.dj_state_failed_too_short
        AnalysisFailureKind.OTHER -> R.string.dj_state_failed_other
    }
}
