package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DjAnalysisGridEditTest {

    private val analysedGrid = DjBeatGrid(listOf(TempoSegment(1300.0, 120.0, 1)))

    private fun analysis(grid: DjBeatGrid? = analysedGrid, edit: GridEdit? = null) = DjAnalysis(
        durationMs = 60_000,
        grid = grid,
        key = null,
        waveform = BandWaveform.MISSING,
        provenance = AnalysisProvenance(1, BeatSource.QM_DSP, analysedAtEpochMs = 1L),
        gridEdit = edit,
    )

    @Test
    fun `an unedited analysis has the analysed grid as its effective grid`() {
        val unedited = analysis()

        assertThat(unedited.gridEdit).isNull()
        assertThat(unedited.isGridEdited).isFalse()
        assertThat(unedited.effectiveGrid).isSameInstanceAs(analysedGrid)
    }

    @Test
    fun `an edit replaces the grid the screens use but never the analysed grid`() {
        val edited = analysis(edit = GridEdit(12_800, 300.0))

        assertThat(edited.isGridEdited).isTrue()
        assertThat(edited.grid).isSameInstanceAs(analysedGrid)
        assertThat(edited.effectiveGrid!!.bpmAt(0.0)).isEqualTo(128.0)
    }

    @Test
    fun `an edit gives an analysis without a steady beat a grid`() {
        assertThat(analysis(grid = null).effectiveGrid).isNull()
        assertThat(analysis(grid = null, edit = GridEdit(12_000, 0.0)).effectiveGrid).isNotNull()
    }

    @Test
    fun `an edit is part of what makes two analyses equal`() {
        assertThat(analysis(edit = GridEdit(12_000, 0.0))).isNotEqualTo(analysis())
        assertThat(analysis(edit = GridEdit(12_000, 0.0))).isEqualTo(analysis(edit = GridEdit(12_000, 0.0)))
    }

    @Test
    fun `withGridEdit sets or clears the edit and keeps the rest`() {
        val base = analysis()

        val edited = base.withGridEdit(GridEdit(12_000, 0.0))

        assertThat(edited.gridEdit).isEqualTo(GridEdit(12_000, 0.0))
        assertThat(edited.withGridEdit(null)).isEqualTo(base)
    }
}
