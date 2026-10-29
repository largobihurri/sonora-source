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
import io.sonora.core.djmodel.DjAnalysisControl
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.djmodel.DjTrackAnalyses
import io.sonora.core.djmodel.DjTrackGridEditor
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playback.ClockSample
import io.sonora.core.domain.playback.PlaybackClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The analysis states, retry and re-analysis of [DjViewModel] (D2.11). */
@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class DjViewModelAnalysisTest {

    private class Settings(on: Boolean) : DjModeSettings {
        val state = MutableStateFlow(on)
        override val enabled: StateFlow<Boolean> = state
        override fun setEnabled(enabled: Boolean) {
            state.value = enabled
        }
        override val performance: StateFlow<PerformanceMode> = MutableStateFlow(PerformanceMode.DEFAULT)
        override fun setPerformance(performance: PerformanceMode) = Unit
    }

    private class Status : DjAnalysisStatusSource {
        val flow = MutableStateFlow<Map<String, AnalysisStatus>>(emptyMap())
        override val status: StateFlow<Map<String, AnalysisStatus>> = flow
    }

    private class Analyses : DjTrackAnalyses {
        private val flows = HashMap<String, MutableStateFlow<DjAnalysis?>>()

        fun set(uri: String, analysis: DjAnalysis?) {
            flows.getOrPut(uri) { MutableStateFlow(null) }.value = analysis
        }

        override fun analysisOf(uri: String): Flow<DjAnalysis?> = flows.getOrPut(uri) { MutableStateFlow(null) }
    }

    /** Counts every write: a re-analysis must never touch the user's grid edit. */
    private class Editor : DjTrackGridEditor {
        var writes = 0
        override suspend fun save(uri: String, edit: GridEdit): Boolean = true.also { writes++ }
        override suspend fun reset(uri: String): Boolean = true.also { writes++ }
    }

    private class Control : DjAnalysisControl {
        val calls = ArrayList<String>()
        override fun retry(uri: String) {
            calls += "retry $uri"
        }
        override fun reanalyse(uri: String) {
            calls += "reanalyse $uri"
        }
    }

    private val settings = Settings(on = true)
    private val status = Status()
    private val analyses = Analyses()
    private val editor = Editor()
    private val cues = FakeCues()
    private val control = Control()
    private val pause = MutableStateFlow(false)
    private val playback = MutableStateFlow(PlaybackState())
    private val uri = "content://a"
    private val clock = object : PlaybackClock {
        override fun read(into: ClockSample): Boolean = false
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(analyzerVersion: Int = 2) = DjViewModel(
        settings, status, analyses, playback, clock, editor, cues,
        FakeQuantize(), FakeLoops(), control, pause, analyzerVersion,
    ) { }

    private fun track(uri: String) = Track(
        id = 7, uri = uri, title = "t", artist = "a", album = "al", albumArtist = null, durationMs = 200_000,
        trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null, mimeType = "audio/flac", sizeBytes = 1,
    )

    private fun play(uri: String = this.uri) {
        val track = track(uri)
        playback.value = PlaybackState(
            currentTrack = track,
            queue = listOf(QueueItem(39, track(uri + "-before")), QueueItem(40, track)),
            currentIndex = 1,
            isPlaying = true,
        )
    }

    private fun analysis(version: Int = 2, grid: DjBeatGrid? = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1)))) = DjAnalysis(
        durationMs = 200_000,
        grid = grid,
        key = null,
        waveform = BandWaveform(ByteArray(150), ByteArray(150), ByteArray(150), ByteArray(150), version = 1),
        provenance = AnalysisProvenance(version, BeatSource.MODEL, "beat-this", Accelerator.CPU, 1L),
    )

    private fun report(vararg statuses: Pair<String, AnalysisStatus>) {
        status.flow.value = mapOf(*statuses)
    }

    // --- Old analyses ----------------------------------------------------------------------------------------------

    @Test
    fun `an analysis from an older analyser is flagged outdated`() {
        analyses.set(uri, analysis(version = 1))
        play()

        assertThat(viewModel(analyzerVersion = 2).uiState.value.analysisOutdated).isTrue()
    }

    @Test
    fun `an analysis from the current analyser is not`() {
        analyses.set(uri, analysis(version = 2))
        play()

        assertThat(viewModel(analyzerVersion = 2).uiState.value.analysisOutdated).isFalse()
    }

    @Test
    fun `no analysis is not outdated`() {
        play()

        assertThat(viewModel().uiState.value.analysisOutdated).isFalse()
    }

    @Test
    fun `an outdated analysis stops being outdated once the replacing run stores the new one`() {
        analyses.set(uri, analysis(version = 1))
        play()
        val vm = viewModel(analyzerVersion = 2)
        assertThat(vm.uiState.value.analysisOutdated).isTrue()

        analyses.set(uri, analysis(version = 2))

        assertThat(vm.uiState.value.analysisOutdated).isFalse()
    }

    // --- States ----------------------------------------------------------------------------------------------------

    @Test
    fun `a replacing run shows the stored analysis with the progress of the new one`() {
        analyses.set(uri, analysis())
        play()
        report(uri to AnalysisStatus.Analysing(AnalysisStage.MEL, 0.5f, replacing = true))

        val timeline = viewModel().uiState.value.timeline

        assertThat(timeline).isInstanceOf(DjTimelineState.Ready::class.java)
        assertThat((timeline as DjTimelineState.Ready).reanalysis).isEqualTo(Reanalysis.Running(0.5f, paused = false))
    }

    @Test
    fun `the heat pause reaches the analysing state`() {
        play()
        report(uri to AnalysisStatus.Analysing(AnalysisStage.MEL, 0.5f))
        val vm = viewModel()
        assertThat(vm.uiState.value.timeline).isEqualTo(DjTimelineState.Analysing(0.5f, paused = false))

        pause.value = true
        assertThat(vm.uiState.value.timeline).isEqualTo(DjTimelineState.Analysing(0.5f, paused = true))

        pause.value = false
        assertThat(vm.uiState.value.timeline).isEqualTo(DjTimelineState.Analysing(0.5f, paused = false))
    }

    @Test
    fun `the failure kind reaches the failed state`() {
        play()
        report(uri to AnalysisStatus.Failed("gone", AnalysisFailureKind.UNREADABLE))

        assertThat(viewModel().uiState.value.timeline).isEqualTo(DjTimelineState.Failed("gone", AnalysisFailureKind.UNREADABLE))
    }

    @Test
    fun `the pause is not read while DJ mode is off`() {
        settings.state.value = false
        play()

        assertThat(viewModel().uiState.value).isEqualTo(DjUiState.OFF)
    }

    // --- Commands --------------------------------------------------------------------------------------------------

    @Test
    fun `re-analyse asks for the playing track`() {
        analyses.set(uri, analysis())
        play()

        viewModel().reanalyse()

        assertThat(control.calls).containsExactly("reanalyse $uri")
    }

    @Test
    fun `re-analyse never touches the grid edit or the cues`() {
        analyses.set(uri, analysis().withGridEdit(GridEdit(12_000, 100.0)))
        play()
        val vm = viewModel()

        vm.reanalyse()

        assertThat(editor.writes).isEqualTo(0)
        assertThat(cues.saves).isEmpty()
        assertThat(cues.deletes).isEmpty()
        assertThat(vm.uiState.value.gridEdit).isEqualTo(GridEdit(12_000, 100.0))
    }

    @Test
    fun `retry on a failed track asks the queue for a new attempt`() {
        play()
        report(uri to AnalysisStatus.Failed("boom"))

        viewModel().retryAnalysis()

        assertThat(control.calls).containsExactly("retry $uri")
    }

    @Test
    fun `retry on a track that has an analysis re-analyses it`() {
        analyses.set(uri, analysis())
        play()
        report(uri to AnalysisStatus.Failed("boom"))

        viewModel().retryAnalysis()

        assertThat(control.calls).containsExactly("reanalyse $uri")
    }

    @Test
    fun `retry on a track without a beat grid re-analyses it`() {
        analyses.set(uri, analysis(grid = null))
        play()

        viewModel().retryAnalysis()

        assertThat(control.calls).containsExactly("reanalyse $uri")
    }

    @Test
    fun `nothing is asked while nothing plays or DJ mode is off`() {
        val vm = viewModel()
        vm.reanalyse()
        vm.retryAnalysis()
        assertThat(control.calls).isEmpty()

        analyses.set(uri, analysis())
        play()
        settings.state.value = false
        vm.reanalyse()
        vm.retryAnalysis()
        assertThat(control.calls).isEmpty()
    }

    @Test
    fun `asking is possible for a settled track and not while it is analysing or waiting`() {
        play()
        val vm = viewModel()
        assertThat(vm.uiState.value.canReanalyse).isFalse() // waiting

        report(uri to AnalysisStatus.Analysing(AnalysisStage.MEL, 0.2f))
        assertThat(vm.uiState.value.canReanalyse).isFalse() // analysing

        report(uri to AnalysisStatus.Failed("boom"))
        assertThat(vm.uiState.value.canReanalyse).isTrue() // failed

        analyses.set(uri, analysis())
        report(uri to AnalysisStatus.Analysed)
        assertThat(vm.uiState.value.canReanalyse).isTrue() // ready

        report(uri to AnalysisStatus.Analysing(AnalysisStage.MEL, 0.2f, replacing = true))
        assertThat(vm.uiState.value.canReanalyse).isFalse() // being analysed again
    }
}
