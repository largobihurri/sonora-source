package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AnalysisFailureKind
import org.junit.Test

class DjStateCardTextTest {

    @Test
    fun `every state has its own title`() {
        val titles = listOf(
            DjTimelinePanel.Waiting,
            DjTimelinePanel.Analysing(10, paused = false),
            DjTimelinePanel.Analysing(10, paused = true),
            DjTimelinePanel.WaveformMissing(10),
            DjTimelinePanel.WaveformMissing(null),
            DjTimelinePanel.NoBeatGrid,
        ).map(DjStateCardText::title)

        assertThat(titles).containsNoDuplicates()
    }

    @Test
    fun `a pause by the heat says so`() {
        assertThat(DjStateCardText.title(DjTimelinePanel.Analysing(10, paused = true))).isEqualTo(R.string.dj_state_paused_title)
        assertThat(DjStateCardText.hint(DjTimelinePanel.Analysing(10, paused = true))).isEqualTo(R.string.dj_state_paused_hint)
        assertThat(DjStateCardText.paused(DjTimelinePanel.Analysing(10, paused = true))).isTrue()
        assertThat(DjStateCardText.paused(DjTimelinePanel.Analysing(10, paused = false))).isFalse()
    }

    @Test
    fun `each kind of failure has its own reason`() {
        val reasons = AnalysisFailureKind.entries.map { DjStateCardText.title(DjTimelinePanel.Failed(it)) }

        assertThat(reasons).containsNoDuplicates()
        assertThat(DjStateCardText.title(DjTimelinePanel.Failed(AnalysisFailureKind.OTHER))).isEqualTo(R.string.dj_state_failed_other)
    }

    @Test
    fun `a failure offers try again and a track without a beat offers analyse again`() {
        assertThat(DjStateCardText.action(DjTimelinePanel.Failed(AnalysisFailureKind.OTHER))).isEqualTo(R.string.dj_state_try_again)
        assertThat(DjStateCardText.action(DjTimelinePanel.NoBeatGrid)).isEqualTo(R.string.dj_state_analyse_again)
    }

    @Test
    fun `the states that resolve by themselves offer no button`() {
        assertThat(DjStateCardText.action(DjTimelinePanel.Waiting)).isNull()
        assertThat(DjStateCardText.action(DjTimelinePanel.Analysing(5, paused = false))).isNull()
        assertThat(DjStateCardText.action(DjTimelinePanel.WaveformMissing(null))).isNull()
        assertThat(DjStateCardText.actionDescription(DjTimelinePanel.Waiting)).isNull()
    }

    @Test
    fun `the bar fills to the percent the state carries`() {
        assertThat(DjStateCardText.percent(DjTimelinePanel.Analysing(42, paused = false))).isEqualTo(42)
        assertThat(DjStateCardText.percent(DjTimelinePanel.WaveformMissing(7))).isEqualTo(7)
        assertThat(DjStateCardText.percent(DjTimelinePanel.WaveformMissing(null))).isNull()
        assertThat(DjStateCardText.percent(DjTimelinePanel.Waiting)).isNull()
    }

    @Test
    fun `only a song with an edited grid is asked about`() {
        assertThat(askBeforeReanalysing(gridEdited = true)).isTrue()
        assertThat(askBeforeReanalysing(gridEdited = false)).isFalse()
    }
}
