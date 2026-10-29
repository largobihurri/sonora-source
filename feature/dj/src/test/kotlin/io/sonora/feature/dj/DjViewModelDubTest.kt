package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.CuePalette
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.DjCue
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

/**
 * The deck of [DjViewModel] in DUB mode: the DUB layout keeps DJ mode's waveform, grid, cues and loops while the DJ flag
 * is off (the two layouts exclude each other), and with both modes off nothing is read, as before.
 */
@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class DjViewModelDubTest {

    private class Settings(on: Boolean) : DjModeSettings {
        override val enabled: StateFlow<Boolean> = MutableStateFlow(on)
        override fun setEnabled(enabled: Boolean) = Unit
        override val performance: StateFlow<PerformanceMode> = MutableStateFlow(PerformanceMode.DEFAULT)
        override fun setPerformance(performance: PerformanceMode) = Unit
    }

    private class Status : DjAnalysisStatusSource {
        override val status: StateFlow<Map<String, AnalysisStatus>> = MutableStateFlow(emptyMap())
    }

    private class Analyses : DjTrackAnalyses {
        val flow = MutableStateFlow<DjAnalysis?>(null)
        var queries = 0
        override fun analysisOf(uri: String): Flow<DjAnalysis?> = flow.also { queries++ }
    }

    private class Editor : DjTrackGridEditor {
        override suspend fun save(uri: String, edit: GridEdit): Boolean = true
        override suspend fun reset(uri: String): Boolean = true
    }

    private val analyses = Analyses()
    private val cues = FakeCues()
    private val loops = FakeLoops()
    private val playback = MutableStateFlow(PlaybackState())
    private val dub = MutableStateFlow(true)
    private val seeks = ArrayList<Long>()
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

    private fun viewModel(djOn: Boolean) = DjViewModel(
        Settings(djOn), Status(), analyses, playback, clock, Editor(), cues, FakeQuantize(on = true), loops,
        dubMode = dub,
    ) { seeks += it }

    private fun track() = Track(
        id = 7, uri = uri, title = "t", artist = "a", album = "al", albumArtist = null, durationMs = 200_000,
        trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null, mimeType = "audio/flac", sizeBytes = 1,
    )

    /** An analysed track (120 BPM from 0 ms) playing, with a hot cue stored on pad B (slot 1) at 2 000 ms. */
    private fun analysedTrackWithCue() {
        analyses.flow.value = DjAnalysis(
            durationMs = 200_000,
            grid = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1))),
            key = null,
            waveform = BandWaveform(ByteArray(150), ByteArray(150), ByteArray(150), ByteArray(150), version = 1),
            provenance = AnalysisProvenance(1, BeatSource.MODEL, "model", Accelerator.CPU, 1L),
        )
        cues.set(uri, listOf(hot(1, 2_000.0)))
        playback.value = PlaybackState(currentTrack = track(), queue = listOf(QueueItem(40, track())), currentIndex = 0, isPlaying = true)
    }

    private fun hot(slot: Int, at: Double) = DjCue(DjCue.hotId(slot), CueKind.HOT, slot, at, null, CuePalette.defaultHot(slot), "")

    @Test
    fun `with DJ mode off and DUB mode on the deck shows the track, its grid and its cues`() {
        analysedTrackWithCue()
        val viewModel = viewModel(djOn = false)

        val ui = viewModel.uiState.value
        assertThat(ui.timeline).isNotEqualTo(DjTimelineState.Disabled)
        assertThat(ui.editableAnalysis).isNotNull()
        assertThat(viewModel.cues.value).containsExactly(hot(1, 2_000.0))
        assertThat(viewModel.loopPanel.value.beatsEnabled).isTrue()
    }

    @Test
    fun `with DJ mode off and DUB mode on the pads jump to a cue and set a new one`() {
        analysedTrackWithCue()
        val viewModel = viewModel(djOn = false)

        viewModel.onHotCue(1, 0.0)
        viewModel.onHotCue(2, 1_130.0)

        assertThat(seeks).containsExactly(2_000L)
        assertThat(cues.saves).containsExactly(uri to hot(2, 1_000.0)) // on the nearest beat, Q on
    }

    @Test
    fun `with DJ mode and DUB mode off nothing is read and the deck stays off`() {
        dub.value = false
        analysedTrackWithCue()
        val viewModel = viewModel(djOn = false)

        viewModel.onHotCue(1, 0.0)
        viewModel.onHotCue(2, 1_130.0)

        assertThat(viewModel.uiState.value).isEqualTo(DjUiState.OFF)
        assertThat(viewModel.cues.value).isEmpty()
        assertThat(viewModel.loopPanel.value.beatsEnabled).isFalse()
        assertThat(analyses.queries).isEqualTo(0)
        assertThat(cues.active).isEmpty()
        assertThat(seeks).isEmpty()
        assertThat(cues.saves).isEmpty()
    }

    @Test
    fun `turning DUB mode off drops the deck like DJ mode off`() {
        analysedTrackWithCue()
        val viewModel = viewModel(djOn = false)

        dub.value = false

        assertThat(viewModel.uiState.value).isEqualTo(DjUiState.OFF)
        assertThat(viewModel.cues.value).isEmpty()
        assertThat(cues.active).isEmpty()
    }

    @Test
    fun `the grid editor stays the DJ layout's and does not open in DUB mode`() {
        analysedTrackWithCue()
        val viewModel = viewModel(djOn = false)

        viewModel.openGridEditor()

        assertThat(viewModel.gridEditorOpen.value).isFalse()
    }
}
