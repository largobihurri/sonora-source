package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisFailureKind
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djmodel.GridEdits
import io.sonora.core.djmodel.TempoSegment
import org.junit.Test

class DjTimelineViewTest {

    private val grid = DjBeatGrid(listOf(TempoSegment(0.0, 128.0, 1)))
    private val waveform = BandWaveform(ByteArray(150), ByteArray(150), ByteArray(150), ByteArray(150), version = 1)

    private fun analysis(grid: DjBeatGrid? = this.grid, waveform: BandWaveform = this.waveform, edit: GridEdit? = null) = DjAnalysis(
        durationMs = 1_000,
        grid = grid,
        key = null,
        waveform = waveform,
        provenance = AnalysisProvenance(1, BeatSource.MODEL, "beat-this", Accelerator.CPU, 1L),
        gridEdit = edit,
    )

    @Test
    fun `a ready analysis draws its waveform and grid with no notice`() {
        val ready = analysis()

        val view = DjTimelineView.of(DjTimelineState.Ready(ready))

        assertThat(view.waveform).isEqualTo(waveform)
        assertThat(view.grid).isEqualTo(grid)
        assertThat(view.analysis).isEqualTo(ready)
        assertThat(view.notice).isNull()
        assertThat(view.panel).isNull()
    }

    @Test
    fun `waiting draws an empty waveform and puts a waiting card on the panel`() {
        val view = DjTimelineView.of(DjTimelineState.Waiting)

        assertThat(view.waveform.columnCount).isEqualTo(0)
        assertThat(view.grid).isNull()
        assertThat(view.analysis).isNull()
        assertThat(view.panel).isEqualTo(DjTimelinePanel.Waiting)
        assertThat(view.notice).isNull()
    }

    @Test
    fun `analysing shows the whole percent, floored`() {
        assertThat(DjTimelineView.of(DjTimelineState.Analysing(0.426f)).panel).isEqualTo(DjTimelinePanel.Analysing(42, paused = false))
        assertThat(DjTimelineView.of(DjTimelineState.Analysing(0f)).panel).isEqualTo(DjTimelinePanel.Analysing(0, paused = false))
    }

    @Test
    fun `analysing never claims 100 percent before the analysis is stored`() {
        assertThat(DjTimelineView.of(DjTimelineState.Analysing(1f)).panel).isEqualTo(DjTimelinePanel.Analysing(99, paused = false))
        assertThat(DjTimelineView.of(DjTimelineState.Analysing(0.9999f)).panel).isEqualTo(DjTimelinePanel.Analysing(99, paused = false))
    }

    @Test
    fun `analysing says when the heat has paused it`() {
        assertThat(DjTimelineView.of(DjTimelineState.Analysing(0.5f, paused = true)).panel)
            .isEqualTo(DjTimelinePanel.Analysing(50, paused = true))
    }

    @Test
    fun `analysing draws nothing yet`() {
        val view = DjTimelineView.of(DjTimelineState.Analysing(0.5f))

        assertThat(view.waveform.columnCount).isEqualTo(0)
        assertThat(view.grid).isNull()
    }

    @Test
    fun `a missing waveform keeps the grid and says the waveform is being rebuilt with its progress`() {
        val restored = analysis(waveform = BandWaveform.MISSING)

        val view = DjTimelineView.of(DjTimelineState.ReadyWaveformMissing(restored, 0.5f))

        assertThat(view.waveform.columnCount).isEqualTo(0)
        assertThat(view.grid).isEqualTo(grid)
        assertThat(view.analysis).isEqualTo(restored)
        assertThat(view.panel).isEqualTo(DjTimelinePanel.WaveformMissing(50))
    }

    @Test
    fun `a missing waveform not being computed has no percent`() {
        val view = DjTimelineView.of(DjTimelineState.ReadyWaveformMissing(analysis(waveform = BandWaveform.MISSING), null))

        assertThat(view.panel).isEqualTo(DjTimelinePanel.WaveformMissing(null))
    }

    @Test
    fun `no beat grid draws the waveform without a grid and offers to analyse again`() {
        val beatless = analysis(grid = null)

        val view = DjTimelineView.of(DjTimelineState.NoBeatGrid(beatless))

        assertThat(view.waveform).isEqualTo(waveform)
        assertThat(view.grid).isNull()
        assertThat(view.panel).isEqualTo(DjTimelinePanel.NoBeatGrid)
        assertThat(view.notice).isNull()
    }

    @Test
    fun `a failed analysis draws nothing and offers to try again with the kind of failure`() {
        val view = DjTimelineView.of(DjTimelineState.Failed("decoder exploded", AnalysisFailureKind.CORRUPT))

        assertThat(view.waveform.columnCount).isEqualTo(0)
        assertThat(view.grid).isNull()
        assertThat(view.panel).isEqualTo(DjTimelinePanel.Failed(AnalysisFailureKind.CORRUPT))
    }

    @Test
    fun `a re-analysis keeps the waveform and the grid and tells its progress in the time row`() {
        val ready = analysis()

        val view = DjTimelineView.of(DjTimelineState.Ready(ready, Reanalysis.Running(0.426f, paused = false)))

        assertThat(view.waveform).isEqualTo(waveform)
        assertThat(view.grid).isEqualTo(grid)
        assertThat(view.panel).isNull()
        assertThat(view.notice).isEqualTo(DjTimelineNotice.Reanalysing(42, paused = false))
    }

    @Test
    fun `a paused re-analysis says so`() {
        val view = DjTimelineView.of(DjTimelineState.Ready(analysis(), Reanalysis.Running(0.5f, paused = true)))

        assertThat(view.notice).isEqualTo(DjTimelineNotice.Reanalysing(50, paused = true))
    }

    @Test
    fun `a failed re-analysis tells so in the time row and keeps everything on screen`() {
        val view = DjTimelineView.of(DjTimelineState.Ready(analysis(), Reanalysis.Failed(AnalysisFailureKind.OTHER)))

        assertThat(view.waveform).isEqualTo(waveform)
        assertThat(view.panel).isNull()
        assertThat(view.notice).isEqualTo(DjTimelineNotice.ReanalysisFailed)
    }

    @Test
    fun `a track without a beat grid that is being analysed again shows no card, only the progress`() {
        val view = DjTimelineView.of(DjTimelineState.NoBeatGrid(analysis(grid = null), Reanalysis.Running(0.2f, paused = false)))

        assertThat(view.panel).isNull()
        assertThat(view.notice).isEqualTo(DjTimelineNotice.Reanalysing(20, paused = false))
    }

    @Test
    fun `a track without a beat grid whose re-analysis failed keeps the card and tells so`() {
        val view = DjTimelineView.of(DjTimelineState.NoBeatGrid(analysis(grid = null), Reanalysis.Failed(AnalysisFailureKind.OTHER)))

        assertThat(view.panel).isEqualTo(DjTimelinePanel.NoBeatGrid)
        assertThat(view.notice).isEqualTo(DjTimelineNotice.ReanalysisFailed)
    }

    @Test
    fun `DJ mode off draws nothing and says nothing`() {
        val view = DjTimelineView.of(DjTimelineState.Disabled)

        assertThat(view.waveform.columnCount).isEqualTo(0)
        assertThat(view.notice).isNull()
        assertThat(view.panel).isNull()
    }

    @Test
    fun `the track BPM for the nudges is the grid's own where the playhead is`() {
        val variable = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(2_000.0, 150.0, 1)))
        val view = DjTimelineView.of(DjTimelineState.Ready(analysis(grid = variable)))

        assertThat(view.trackBpmAt(1_000)).isEqualTo(120.0)
        assertThat(view.trackBpmAt(3_000)).isEqualTo(150.0)
    }

    @Test
    fun `without a grid there is no track BPM`() {
        assertThat(DjTimelineView.of(DjTimelineState.NoBeatGrid(analysis(grid = null))).trackBpmAt(0)).isNull()
        assertThat(DjTimelineView.of(DjTimelineState.Waiting).trackBpmAt(0)).isNull()
    }

    @Test
    fun `an edited track draws and reports the edited grid, not the analysed one`() {
        val edit = GridEdit(12_345, 250.0)
        val edited = analysis(edit = edit)

        val view = DjTimelineView.of(DjTimelineState.Ready(edited))

        assertThat(view.grid).isEqualTo(GridEdits.apply(grid, edit))
        assertThat(view.grid).isNotEqualTo(grid)
        assertThat(view.trackBpmAt(0)).isEqualTo(123.45)
    }

    @Test
    fun `an edited grid is drawn while the waveform is still being rebuilt`() {
        val edit = GridEdit(12_345, 250.0)
        val edited = analysis(waveform = BandWaveform.MISSING, edit = edit)

        val view = DjTimelineView.of(DjTimelineState.ReadyWaveformMissing(edited, progress = 0.3f))

        assertThat(view.grid).isEqualTo(GridEdits.apply(grid, edit))
    }

    @Test
    fun `a grid made by an edit on a track without one is drawn`() {
        val edited = analysis(grid = null, edit = GridEdit(12_000, 0.0))

        assertThat(DjTimelineView.of(DjTimelineState.Ready(edited)).grid).isNotNull()
    }

    @Test
    fun `a card that covers the waveform hides the playhead`() {
        val covering = listOf(
            DjTimelineState.Waiting,
            DjTimelineState.Analysing(0.53f),
            DjTimelineState.Analysing(0.53f, paused = true),
            DjTimelineState.ReadyWaveformMissing(analysis(), progress = 0.4f),
            DjTimelineState.ReadyWaveformMissing(analysis(), progress = null),
            DjTimelineState.Failed("boom", AnalysisFailureKind.OTHER),
        )

        for (state in covering) assertThat(DjTimelineView.of(state).showsPlayhead).isFalse()
    }

    @Test
    fun `the playhead stays where the waveform is in view`() {
        val showing = listOf(
            DjTimelineState.Disabled,
            DjTimelineState.Ready(analysis()),
            // The strip of a track without a beat sits along the bottom, with the waveform in view above it.
            DjTimelineState.NoBeatGrid(analysis(grid = null)),
            // While it is analysed again the card steps aside.
            DjTimelineState.NoBeatGrid(analysis(grid = null), Reanalysis.Running(0.2f, paused = false)),
        )

        for (state in showing) assertThat(DjTimelineView.of(state).showsPlayhead).isTrue()
    }
}
