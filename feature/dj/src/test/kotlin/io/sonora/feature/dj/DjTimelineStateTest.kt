package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisFailureKind
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djmodel.TempoSegment
import org.junit.Test

class DjTimelineStateTest {

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

    private val analysing = AnalysisStatus.Analysing(AnalysisStage.DECODE, 0.4f)

    private fun state(enabled: Boolean = true, status: AnalysisStatus? = null, analysis: DjAnalysis? = null, paused: Boolean = false) =
        DjTimelineState.of(enabled, status, analysis, pausedByHeat = paused)

    private val replacing = AnalysisStatus.Analysing(AnalysisStage.GRID, 0.4f, replacing = true)

    @Test
    fun `DJ mode off is disabled whatever else is known`() {
        assertThat(state(enabled = false)).isEqualTo(DjTimelineState.Disabled)
        assertThat(state(enabled = false, status = AnalysisStatus.Analysed, analysis = analysis())).isEqualTo(DjTimelineState.Disabled)
    }

    @Test
    fun `nothing known yet is waiting`() {
        assertThat(state()).isEqualTo(DjTimelineState.Waiting)
        assertThat(state(status = AnalysisStatus.NotAnalysed)).isEqualTo(DjTimelineState.Waiting)
        assertThat(state(status = AnalysisStatus.Queued)).isEqualTo(DjTimelineState.Waiting)
    }

    @Test
    fun `analysed but not loaded yet is waiting`() {
        assertThat(state(status = AnalysisStatus.Analysed)).isEqualTo(DjTimelineState.Waiting)
    }

    @Test
    fun `an analysis in progress carries its progress`() {
        assertThat(state(status = analysing)).isEqualTo(DjTimelineState.Analysing(0.4f))
    }

    @Test
    fun `the progress is kept inside 0 to 1`() {
        assertThat(state(status = AnalysisStatus.Analysing(AnalysisStage.MEL, 1.7f))).isEqualTo(DjTimelineState.Analysing(1f))
        assertThat(state(status = AnalysisStatus.Analysing(AnalysisStage.MEL, -0.2f))).isEqualTo(DjTimelineState.Analysing(0f))
        assertThat(state(status = AnalysisStatus.Analysing(AnalysisStage.MEL, Float.NaN))).isEqualTo(DjTimelineState.Analysing(0f))
    }

    @Test
    fun `a complete analysis is ready`() {
        val analysis = analysis()

        assertThat(state(status = AnalysisStatus.Analysed, analysis = analysis)).isEqualTo(DjTimelineState.Ready(analysis))
    }

    @Test
    fun `a stored analysis is ready before the status says so`() {
        val analysis = analysis()

        assertThat(state(status = null, analysis = analysis)).isEqualTo(DjTimelineState.Ready(analysis))
        assertThat(state(status = AnalysisStatus.NotAnalysed, analysis = analysis)).isEqualTo(DjTimelineState.Ready(analysis))
    }

    @Test
    fun `a stored analysis stays on screen while the track is analysed again`() {
        val analysis = analysis()

        assertThat(state(status = analysing, analysis = analysis)).isEqualTo(DjTimelineState.Ready(analysis))
    }

    @Test
    fun `an analysis without a waveform is ready with the waveform missing`() {
        val restored = analysis(waveform = BandWaveform.MISSING)

        assertThat(state(status = AnalysisStatus.Analysed, analysis = restored))
            .isEqualTo(DjTimelineState.ReadyWaveformMissing(restored, progress = null))
    }

    @Test
    fun `the missing waveform shows the progress while it is being computed`() {
        val restored = analysis(waveform = BandWaveform.MISSING)

        assertThat(state(status = AnalysisStatus.Analysing(AnalysisStage.WAVEFORM, 0.25f), analysis = restored))
            .isEqualTo(DjTimelineState.ReadyWaveformMissing(restored, progress = 0.25f))
    }

    @Test
    fun `an analysis without a beat grid says so`() {
        val ambient = analysis(grid = null)

        assertThat(state(status = AnalysisStatus.Analysed, analysis = ambient)).isEqualTo(DjTimelineState.NoBeatGrid(ambient))
    }

    @Test
    fun `a missing waveform comes before a missing grid`() {
        val restored = analysis(grid = null, waveform = BandWaveform.MISSING)

        assertThat(state(status = AnalysisStatus.Analysed, analysis = restored))
            .isEqualTo(DjTimelineState.ReadyWaveformMissing(restored, progress = null))
    }

    @Test
    fun `a failure without an analysis carries the reason`() {
        assertThat(state(status = AnalysisStatus.Failed("decode"))).isEqualTo(DjTimelineState.Failed("decode"))
    }

    @Test
    fun `a failure carries the kind the user can act on`() {
        assertThat(state(status = AnalysisStatus.Failed("gone", AnalysisFailureKind.UNREADABLE)))
            .isEqualTo(DjTimelineState.Failed("gone", AnalysisFailureKind.UNREADABLE))
    }

    @Test
    fun `a failure while the waveform is missing is a failure`() {
        val restored = analysis(waveform = BandWaveform.MISSING)

        assertThat(state(status = AnalysisStatus.Failed("decode"), analysis = restored)).isEqualTo(DjTimelineState.Failed("decode"))
    }

    @Test
    fun `a failed new run does not hide a good analysis, it says the re-analysis failed`() {
        val analysis = analysis()

        assertThat(state(status = AnalysisStatus.Failed("decode", AnalysisFailureKind.CORRUPT), analysis = analysis))
            .isEqualTo(DjTimelineState.Ready(analysis, Reanalysis.Failed(AnalysisFailureKind.CORRUPT)))
    }

    @Test
    fun `a replacing run keeps the analysis on screen and carries its progress`() {
        val analysis = analysis()

        assertThat(state(status = replacing, analysis = analysis))
            .isEqualTo(DjTimelineState.Ready(analysis, Reanalysis.Running(0.4f, paused = false)))
    }

    @Test
    fun `a replacing run on a track without a beat grid keeps its state too`() {
        val ambient = analysis(grid = null)

        assertThat(state(status = replacing, analysis = ambient))
            .isEqualTo(DjTimelineState.NoBeatGrid(ambient, Reanalysis.Running(0.4f, paused = false)))
        assertThat(state(status = AnalysisStatus.Failed("x"), analysis = ambient))
            .isEqualTo(DjTimelineState.NoBeatGrid(ambient, Reanalysis.Failed(AnalysisFailureKind.OTHER)))
    }

    @Test
    fun `a replacing run keeps the edited grid in force`() {
        val edited = analysis(edit = GridEdit(12_000, 0.0))

        assertThat(state(status = replacing, analysis = edited))
            .isEqualTo(DjTimelineState.Ready(edited, Reanalysis.Running(0.4f, paused = false)))
    }

    @Test
    fun `a heat pause is carried by the run that is paused`() {
        assertThat(state(status = analysing, paused = true)).isEqualTo(DjTimelineState.Analysing(0.4f, paused = true))
        assertThat(state(status = replacing, analysis = analysis(), paused = true))
            .isEqualTo(DjTimelineState.Ready(analysis(), Reanalysis.Running(0.4f, paused = true)))
    }

    @Test
    fun `a heat pause says nothing when no analysis is running`() {
        assertThat(state(status = AnalysisStatus.Queued, paused = true)).isEqualTo(DjTimelineState.Waiting)
        assertThat(state(status = AnalysisStatus.Analysed, analysis = analysis(), paused = true))
            .isEqualTo(DjTimelineState.Ready(analysis()))
    }

    @Test
    fun `an edit gives a track without a steady beat a grid, so it is ready`() {
        val edited = analysis(grid = null, edit = GridEdit(12_000, 0.0))

        assertThat(state(status = AnalysisStatus.Analysed, analysis = edited)).isEqualTo(DjTimelineState.Ready(edited))
    }

    @Test
    fun `an edited track with a grid is ready`() {
        val edited = analysis(edit = GridEdit(12_000, 0.0))

        assertThat(state(status = AnalysisStatus.Analysed, analysis = edited)).isEqualTo(DjTimelineState.Ready(edited))
    }

    @Test
    fun `the analysis behind a state is found whatever the state is doing`() {
        val ready = analysis()
        val ambient = analysis(grid = null)

        assertThat(DjTimelineState.Ready(ready).analysis).isEqualTo(ready)
        assertThat(DjTimelineState.NoBeatGrid(ambient).analysis).isEqualTo(ambient)
        assertThat(DjTimelineState.ReadyWaveformMissing(ready, progress = null).analysis).isEqualTo(ready)
        assertThat(DjTimelineState.Waiting.analysis).isNull()
        assertThat(DjTimelineState.Disabled.analysis).isNull()
        assertThat(DjTimelineState.Analysing(0.5f).analysis).isNull()
        assertThat(DjTimelineState.Failed("x").analysis).isNull()
    }
}
