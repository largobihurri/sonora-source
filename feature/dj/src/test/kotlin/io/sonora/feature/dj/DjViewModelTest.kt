package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjTrackCues
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.djmodel.DjTrackAnalyses
import io.sonora.core.djmodel.DjTrackGridEditor
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playback.ClockSample
import io.sonora.core.domain.playback.PlaybackClock
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.feature.dj.waveform.WaveformZoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class DjViewModelTest {

    private class FakeSettings(on: Boolean) : DjModeSettings {
        private val state = MutableStateFlow(on)
        override val enabled: StateFlow<Boolean> = state

        override fun setEnabled(enabled: Boolean) {
            state.value = enabled
        }

        private val mode = MutableStateFlow(PerformanceMode.DEFAULT)
        override val performance: StateFlow<PerformanceMode> = mode

        override fun setPerformance(performance: PerformanceMode) {
            mode.value = performance
        }
    }

    /** Reading [status] is what starts the analysis in the app, so the fake counts how often it is read. */
    private class FakeStatus : DjAnalysisStatusSource {
        val flow = MutableStateFlow<Map<String, AnalysisStatus>>(emptyMap())
        var reads = 0
            private set

        override val status: StateFlow<Map<String, AnalysisStatus>>
            get() {
                reads++
                return flow
            }
    }

    private class FakeAnalyses : DjTrackAnalyses {
        private val flows = HashMap<String, MutableStateFlow<DjAnalysis?>>()
        val active = HashSet<String>()
        val everRequested = HashSet<String>()

        fun set(uri: String, analysis: DjAnalysis?) {
            flows.getOrPut(uri) { MutableStateFlow(null) }.value = analysis
        }

        fun current(uri: String): DjAnalysis? = flows[uri]?.value

        override fun analysisOf(uri: String): Flow<DjAnalysis?> {
            everRequested += uri
            return flows.getOrPut(uri) { MutableStateFlow(null) }
                .onStart { active += uri }
                .onCompletion { active -= uri }
        }
    }

    /** Writes where the app does, in effect: into the analysis the fake store hands out, as the real editor's store would. */
    private class FakeEditor(private val analyses: FakeAnalyses) : DjTrackGridEditor {
        val saves = ArrayList<Pair<String, GridEdit>>()
        val resets = ArrayList<String>()
        var identified = true

        /** `false` makes the store slow: the write is recorded but the screens do not see it (yet). */
        var reachesScreens = true

        override suspend fun save(uri: String, edit: GridEdit): Boolean {
            if (!identified) return false
            saves += uri to edit
            if (reachesScreens) analyses.current(uri)?.let { analyses.set(uri, it.withGridEdit(edit)) }
            return true
        }

        override suspend fun reset(uri: String): Boolean {
            if (!identified) return false
            resets += uri
            if (reachesScreens) analyses.current(uri)?.let { analyses.set(uri, it.withGridEdit(null)) }
            return true
        }
    }

    private val status = FakeStatus()
    private val analyses = FakeAnalyses()
    private val trackCues = FakeCues()
    private val quantizeSetting = FakeQuantize()
    private val seeks = ArrayList<Long>()
    private val editor = FakeEditor(analyses)
    private val playback = MutableStateFlow(PlaybackState())
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

    private fun viewModel(settings: DjModeSettings = FakeSettings(on = true)) =
        DjViewModel(settings, status, analyses, playback, clock, editor, trackCues, quantizeSetting, FakeLoops()) { seeks += it }

    private fun track(uri: String, durationMs: Long = 200_000) = Track(
        id = 7, uri = uri, title = "t", artist = "a", album = "al", albumArtist = null, durationMs = durationMs,
        trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null, mimeType = "audio/flac", sizeBytes = 1,
    )

    private fun play(uri: String, queueItemId: Long = 40, isPlaying: Boolean = true, durationMs: Long = 0, positionMs: Long = 0) {
        val track = track(uri)
        playback.value = PlaybackState(
            currentTrack = track,
            queue = listOf(QueueItem(queueItemId - 1, track(uri + "-before")), QueueItem(queueItemId, track)),
            currentIndex = 1,
            isPlaying = isPlaying,
            durationMs = durationMs,
            positionMs = positionMs,
        )
    }

    private fun analysis(
        grid: DjBeatGrid? = DjBeatGrid(listOf(TempoSegment(0.0, 128.0, 1))),
        waveform: BandWaveform = BandWaveform(ByteArray(150), ByteArray(150), ByteArray(150), ByteArray(150), version = 1),
    ) = DjAnalysis(
        durationMs = 200_000,
        grid = grid,
        key = KeyEstimate(MusicalKey.A_MINOR, 0.9, "edma"),
        waveform = waveform,
        provenance = AnalysisProvenance(1, BeatSource.MODEL, "beat-this", Accelerator.CPU, 1L),
    )

    @Test
    fun `with DJ mode off the state is disabled and nothing is read`() {
        play("content://a")
        val viewModel = viewModel(FakeSettings(on = false))

        assertThat(viewModel.uiState.value.timeline).isEqualTo(DjTimelineState.Disabled)
        assertThat(viewModel.uiState.value.readout).isNull()
        assertThat(status.reads).isEqualTo(0)
        assertThat(analyses.everRequested).isEmpty()
    }

    @Test
    fun `nothing playing is waiting with an empty header`() {
        val viewModel = viewModel()

        assertThat(viewModel.uiState.value.timeline).isEqualTo(DjTimelineState.Waiting)
        assertThat(viewModel.uiState.value.header).isEqualTo(DjHeader.NONE)
        assertThat(viewModel.uiState.value.readout).isNull()
    }

    @Test
    fun `the header carries the uri the queue item id the duration and whether it plays`() {
        play("content://a", queueItemId = 41, isPlaying = true, durationMs = 199_000)
        val viewModel = viewModel()

        assertThat(viewModel.uiState.value.header).isEqualTo(
            DjHeader(uri = "content://a", queueItemId = 41, durationMs = 199_000, isPlaying = true),
        )

        play("content://a", queueItemId = 41, isPlaying = false, durationMs = 199_000)
        assertThat(viewModel.uiState.value.header.isPlaying).isFalse()
    }

    @Test
    fun `the track duration is used until the player reports one`() {
        play("content://a", durationMs = 0)

        assertThat(viewModel().uiState.value.header.durationMs).isEqualTo(200_000)
    }

    @Test
    fun `the same track queued twice has two different queue item ids`() {
        play("content://a", queueItemId = 41)
        val viewModel = viewModel()
        play("content://a", queueItemId = 55)

        assertThat(viewModel.uiState.value.header.queueItemId).isEqualTo(55)
    }

    @Test
    fun `a stored analysis is ready with the track's readout, tempo not applied`() {
        val analysis = analysis()
        analyses.set("content://a", analysis)
        status.flow.value = mapOf("content://a" to AnalysisStatus.Analysed)
        play("content://a")

        val state = viewModel().uiState.value

        assertThat(state.timeline).isEqualTo(DjTimelineState.Ready(analysis))
        assertThat(state.readout).isEqualTo(DjTrackReadout(gridBpm = 128.0, key = MusicalKey.A_MINOR))
        // The tempo controls apply the tempo they follow themselves.
        assertThat(state.readout?.at(PlaybackTempo(speedBasisPoints = 10_125)))
            .isEqualTo(DjReadout(bpm = 129.6, tempoPercent = 1.25, key = MusicalKey.A_MINOR))
    }

    @Test
    fun `an analysis that arrives later replaces the waiting state`() {
        play("content://a")
        status.flow.value = mapOf("content://a" to AnalysisStatus.Analysing(AnalysisStage.DECODE, 0.5f))
        val viewModel = viewModel()
        assertThat(viewModel.uiState.value.timeline).isEqualTo(DjTimelineState.Analysing(0.5f))
        assertThat(viewModel.uiState.value.readout).isNull()

        val analysis = analysis()
        analyses.set("content://a", analysis)
        status.flow.value = mapOf("content://a" to AnalysisStatus.Analysed)

        assertThat(viewModel.uiState.value.timeline).isEqualTo(DjTimelineState.Ready(analysis))
    }

    @Test
    fun `a failure is shown with its reason`() {
        play("content://a")
        status.flow.value = mapOf("content://a" to AnalysisStatus.Failed("decode"))

        assertThat(viewModel().uiState.value.timeline).isEqualTo(DjTimelineState.Failed("decode"))
    }

    @Test
    fun `a track without a beat grid has a readout without tempo`() {
        val ambient = analysis(grid = null)
        analyses.set("content://a", ambient)
        play("content://a")

        val state = viewModel().uiState.value

        assertThat(state.timeline).isEqualTo(DjTimelineState.NoBeatGrid(ambient))
        assertThat(state.readout?.gridBpm).isNull()
        assertThat(state.readout?.key).isEqualTo(MusicalKey.A_MINOR)
    }

    @Test
    fun `the status of other tracks does not matter`() {
        play("content://a")
        status.flow.value = mapOf("content://b" to AnalysisStatus.Failed("decode"))

        assertThat(viewModel().uiState.value.timeline).isEqualTo(DjTimelineState.Waiting)
    }

    @Test
    fun `a track change drops the previous analysis at once and stops observing it`() {
        analyses.set("content://a", analysis())
        play("content://a")
        val viewModel = viewModel()
        assertThat(viewModel.uiState.value.timeline).isInstanceOf(DjTimelineState.Ready::class.java)

        play("content://b", queueItemId = 41)

        assertThat(viewModel.uiState.value.timeline).isEqualTo(DjTimelineState.Waiting)
        assertThat(viewModel.uiState.value.readout).isNull()
        assertThat(analyses.active).containsExactly("content://b")
    }

    @Test
    fun `the position ticks do not change a constant grid state`() {
        analyses.set("content://a", analysis())
        play("content://a", positionMs = 0)
        val viewModel = viewModel()
        val before = viewModel.uiState.value

        play("content://a", positionMs = 90_000)

        assertThat(viewModel.uiState.value).isEqualTo(before)
    }

    @Test
    fun `a variable tempo readout follows the playback position`() {
        val grid = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(2_000.0, 150.0, 1)))
        analyses.set("content://a", analysis(grid = grid))
        play("content://a", positionMs = 500)
        val viewModel = viewModel()
        assertThat(viewModel.uiState.value.readout?.gridBpm).isEqualTo(120.0)

        play("content://a", positionMs = 2_500)

        assertThat(viewModel.uiState.value.readout?.gridBpm).isEqualTo(150.0)
    }

    @Test
    fun `turning DJ mode off stops reading and observing`() {
        val settings = FakeSettings(on = true)
        analyses.set("content://a", analysis())
        play("content://a")
        val viewModel = viewModel(settings)
        assertThat(analyses.active).containsExactly("content://a")

        settings.setEnabled(false)

        assertThat(viewModel.uiState.value.timeline).isEqualTo(DjTimelineState.Disabled)
        assertThat(analyses.active).isEmpty()
    }

    @Test
    fun `turning DJ mode on starts from the playing track`() {
        val settings = FakeSettings(on = false)
        val analysis = analysis()
        analyses.set("content://a", analysis)
        play("content://a")
        val viewModel = viewModel(settings)

        settings.setEnabled(true)

        assertThat(viewModel.uiState.value.timeline).isEqualTo(DjTimelineState.Ready(analysis))
    }

    @Test
    fun `the waveform opens at the default span`() {
        assertThat(viewModel().zoom.value).isEqualTo(WaveformZoom.DEFAULT)
    }

    @Test
    fun `the span set by a pinch is kept`() {
        val model = viewModel()

        model.setZoom(WaveformZoom.SECONDS_16)

        assertThat(model.zoom.value).isEqualTo(WaveformZoom.SECONDS_16)
    }

    @Test
    fun `the screen reads the injected playback clock`() {
        assertThat(viewModel().clock).isSameInstanceAs(clock)
    }

    // --- beat grid editing

    private fun editable(uri: String = "content://a", grid: DjBeatGrid? = DjBeatGrid(listOf(TempoSegment(0.0, 128.0, 1)))) {
        analyses.set(uri, analysis(grid = grid))
        status.flow.value = mapOf(uri to AnalysisStatus.Analysed)
        play(uri)
    }

    @Test
    fun `a bar start set at the playhead is saved under the playing uri and shown at once`() {
        editable()
        val viewModel = viewModel()

        viewModel.editGrid(GridEditCommand.SetBarStartHere, positionMs = 4_700.0)

        // 128 BPM: beats every 468.75 ms; the beat nearest 4700 is the 11th, at 4687.5, a bar start (bars are 1875 ms).
        val edit = GridEdit(12_800, 4_687.5) // the phrase starts at the beat set (a phrase is 7500 ms)
        assertThat(editor.saves).containsExactly("content://a" to edit)
        assertThat(viewModel.uiState.value.gridEdit).isEqualTo(edit)
        assertThat(viewModel.uiState.value.editableAnalysis!!.effectiveGrid!!.beats(4_000.0).first { it.beatInBar == 1 }.timeMs)
            .isWithin(1e-6).of(937.5)
    }

    @Test
    fun `the readout follows an edited tempo live`() {
        editable()
        val viewModel = viewModel()

        viewModel.editGrid(GridEditCommand.AdjustBpm(25), positionMs = 0.0)

        assertThat(viewModel.uiState.value.readout?.at(PlaybackTempo.DEFAULT)?.bpm).isEqualTo(128.3)
        assertThat((viewModel.uiState.value.timeline as DjTimelineState.Ready).analysis.effectiveGrid!!.bpmAt(0.0)).isEqualTo(128.25)
    }

    @Test
    fun `commands in a row build on each other even before the store shows the first`() {
        editable()
        editor.reachesScreens = false
        val viewModel = viewModel()

        viewModel.editGrid(GridEditCommand.ShiftBeats(1), 0.0)
        viewModel.editGrid(GridEditCommand.ShiftBeats(1), 0.0)
        viewModel.editGrid(GridEditCommand.AdjustBpm(1), 0.0)

        // From the analysed bar start 0: +468.75 +468.75, then the tempo to 128.01 keeping the anchor.
        assertThat(editor.saves.map { it.second }).containsExactly(
            GridEdit(12_800, 468.75),
            GridEdit(12_800, 937.5),
            GridEdit(12_801, 937.5),
        ).inOrder()
    }

    @Test
    fun `an edit already stored is the one a command builds on`() {
        editable()
        analyses.set("content://a", analyses.current("content://a")!!.withGridEdit(GridEdit(13_000, 100.0)))
        val viewModel = viewModel()

        viewModel.editGrid(GridEditCommand.Nudge(10.0), 0.0)

        assertThat(editor.saves.single().second).isEqualTo(GridEdit(13_000, 110.0))
    }

    @Test
    fun `resetting removes the edit and the next command starts from the analysis again`() {
        editable()
        editor.reachesScreens = false
        val viewModel = viewModel()
        viewModel.editGrid(GridEditCommand.AdjustBpm(100), 0.0)

        viewModel.resetGrid()
        viewModel.editGrid(GridEditCommand.AdjustBpm(1), 0.0)

        assertThat(editor.resets).containsExactly("content://a")
        assertThat(editor.saves.last().second).isEqualTo(GridEdit(12_801, 0.0))
    }

    @Test
    fun `resetting brings the analysed grid back on the screen`() {
        editable()
        val viewModel = viewModel()
        viewModel.editGrid(GridEditCommand.AdjustBpm(100), 0.0)
        assertThat(viewModel.uiState.value.gridEdit).isNotNull()

        viewModel.resetGrid()

        assertThat(viewModel.uiState.value.gridEdit).isNull()
        assertThat(viewModel.uiState.value.readout?.gridBpm).isEqualTo(128.0)
    }

    @Test
    fun `a track whose identity is not known stores nothing and keeps its grid`() {
        editable()
        editor.identified = false
        val viewModel = viewModel()
        val before = viewModel.uiState.value

        viewModel.editGrid(GridEditCommand.AdjustBpm(1), 0.0)

        assertThat(viewModel.uiState.value).isEqualTo(before)
    }

    @Test
    fun `without an analysis nothing is edited`() {
        play("content://a")
        val viewModel = viewModel()

        viewModel.editGrid(GridEditCommand.AdjustBpm(1), 0.0)
        viewModel.resetGrid()

        assertThat(editor.saves).isEmpty()
        assertThat(editor.resets).isEmpty()
    }

    @Test
    fun `with DJ mode off nothing is edited`() {
        analyses.set("content://a", analysis())
        play("content://a")
        val viewModel = viewModel(FakeSettings(on = false))

        viewModel.editGrid(GridEditCommand.AdjustBpm(1), 0.0)

        assertThat(editor.saves).isEmpty()
    }

    @Test
    fun `a track without a steady beat gets a grid from its first edit`() {
        editable(grid = null)
        val viewModel = viewModel()
        assertThat(viewModel.uiState.value.timeline).isInstanceOf(DjTimelineState.NoBeatGrid::class.java)

        viewModel.editGrid(GridEditCommand.SetBarStartHere, positionMs = 2_500.0)

        assertThat(viewModel.uiState.value.timeline).isInstanceOf(DjTimelineState.Ready::class.java)
        assertThat(viewModel.uiState.value.readout?.gridBpm).isEqualTo(120.0)
        assertThat(editor.saves.single().second.anchorMs).isWithin(1e-6).of(2_500.0)
    }

    @Test
    fun `a bar shift saves a phrase start four beats later and the bar lines stay where they were`() {
        editable()
        val viewModel = viewModel()

        viewModel.editGrid(GridEditCommand.ShiftBars(1), positionMs = 0.0)

        // 128 BPM: the analysed first downbeat is at 0 and starts a phrase; a bar is 1875 ms.
        assertThat(editor.saves).containsExactly("content://a" to GridEdit(12_800, 1_875.0))
        val grid = viewModel.uiState.value.editableAnalysis!!.effectiveGrid!!
        val beats = grid.beats(10_000.0)
        val phrases = grid.phraseStarts(beats)
        assertThat(beats.filterIndexed { i, _ -> phrases[i] }.map { it.timeMs }).containsExactly(1_875.0, 9_375.0).inOrder()
        assertThat(beats.filter { it.beatInBar == 1 }.first().timeMs).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `bar shifts in a row build on each other`() {
        editable()
        editor.reachesScreens = false
        val viewModel = viewModel()

        viewModel.editGrid(GridEditCommand.ShiftBars(1), 0.0)
        viewModel.editGrid(GridEditCommand.ShiftBars(1), 0.0)
        viewModel.editGrid(GridEditCommand.ShiftBars(-1), 0.0)

        assertThat(editor.saves.map { it.second.anchorMs }).containsExactly(1_875.0, 3_750.0, 1_875.0).inOrder()
    }

    @Test
    fun `the editor is closed until it is opened`() {
        editable()
        val viewModel = viewModel()

        assertThat(viewModel.gridEditorOpen.value).isFalse()

        viewModel.openGridEditor()
        assertThat(viewModel.gridEditorOpen.value).isTrue()

        viewModel.closeGridEditor()
        assertThat(viewModel.gridEditorOpen.value).isFalse()
    }

    @Test
    fun `the editor does not open without an analysis to edit`() {
        play("content://a")
        val viewModel = viewModel()

        viewModel.openGridEditor()

        assertThat(viewModel.gridEditorOpen.value).isFalse()
    }

    @Test
    fun `the editor closes when the track changes and does not come back with it`() {
        editable("content://a")
        analyses.set("content://b", analysis())
        val viewModel = viewModel()
        viewModel.openGridEditor()

        play("content://b", queueItemId = 41)
        assertThat(viewModel.gridEditorOpen.value).isFalse()

        play("content://a", queueItemId = 42)
        assertThat(viewModel.gridEditorOpen.value).isFalse()
    }

    @Test
    fun `the editor closes when DJ mode is turned off`() {
        val settings = FakeSettings(on = true)
        editable()
        val viewModel = viewModel(settings)
        viewModel.openGridEditor()

        settings.setEnabled(false)
        assertThat(viewModel.gridEditorOpen.value).isFalse()

        settings.setEnabled(true)
        assertThat(viewModel.gridEditorOpen.value).isFalse()
    }

    @Test
    fun `a command after a track change builds on the new track's edit, not the previous one's`() {
        editable("content://a")
        editor.reachesScreens = false
        analyses.set("content://b", analysis())
        val viewModel = viewModel()
        viewModel.editGrid(GridEditCommand.AdjustBpm(100), 0.0)

        play("content://b", queueItemId = 41)
        viewModel.editGrid(GridEditCommand.AdjustBpm(1), 0.0)

        assertThat(editor.saves.last()).isEqualTo("content://b" to GridEdit(12_801, 0.0))
    }
}
