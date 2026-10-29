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
import io.sonora.core.djmodel.DjCues
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

/** The cue and quantize operations of [DjViewModel]: pads, memory cues, jumps, edits of a cue and the Q setting. */
@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class DjViewModelCuesTest {

    private class Settings(on: Boolean) : DjModeSettings {
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

    private class Status : DjAnalysisStatusSource {
        override val status: StateFlow<Map<String, AnalysisStatus>> = MutableStateFlow(emptyMap())
    }

    private class Analyses : DjTrackAnalyses {
        private val flows = HashMap<String, MutableStateFlow<DjAnalysis?>>()

        fun set(uri: String, analysis: DjAnalysis?) {
            flows.getOrPut(uri) { MutableStateFlow(null) }.value = analysis
        }

        override fun analysisOf(uri: String): Flow<DjAnalysis?> = flows.getOrPut(uri) { MutableStateFlow(null) }
    }

    private class Editor : DjTrackGridEditor {
        override suspend fun save(uri: String, edit: GridEdit): Boolean = true

        override suspend fun reset(uri: String): Boolean = true
    }

    private val analyses = Analyses()
    private val cues = FakeCues()
    private val quantizeSetting = FakeQuantize(on = true)
    private val playback = MutableStateFlow(PlaybackState())
    private val seeks = ArrayList<Long>()
    private val uri = "content://a"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val clock = object : PlaybackClock {
        override fun read(into: ClockSample): Boolean = false
    }

    private fun viewModel(on: Boolean = true) = DjViewModel(
        Settings(on), Status(), analyses, playback, clock, Editor(), cues, quantizeSetting,
        FakeLoops(),
    ) { seeks += it }

    private fun track(uri: String) = Track(
        id = 7, uri = uri, title = "t", artist = "a", album = "al", albumArtist = null, durationMs = 200_000,
        trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null, mimeType = "audio/flac", sizeBytes = 1,
    )

    private fun play(uri: String, queueItemId: Long = 40) {
        val track = track(uri)
        playback.value = PlaybackState(
            currentTrack = track,
            queue = listOf(QueueItem(queueItemId - 1, track(uri + "-before")), QueueItem(queueItemId, track)),
            currentIndex = 1,
            isPlaying = true,
        )
    }

    /** 120 BPM from 0 ms: a beat every 500 ms. */
    private fun analysis(edit: GridEdit? = null, grid: DjBeatGrid? = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1)))) = DjAnalysis(
        durationMs = 200_000,
        grid = grid,
        key = null,
        waveform = BandWaveform(ByteArray(150), ByteArray(150), ByteArray(150), ByteArray(150), version = 1),
        provenance = AnalysisProvenance(1, BeatSource.MODEL, "beat-this", Accelerator.CPU, 1L),
        gridEdit = edit,
    )

    private fun ready(uri: String = this.uri, analysis: DjAnalysis = analysis()): DjViewModel {
        analyses.set(uri, analysis)
        play(uri)
        return viewModel()
    }

    private fun hot(slot: Int, at: Double, name: String = "") =
        DjCue(DjCue.hotId(slot), CueKind.HOT, slot, at, null, CuePalette.defaultHot(slot), name)

    @Test
    fun `the cues are the playing track's and follow its changes`() {
        cues.set(uri, listOf(hot(0, 1_000.0)))
        val viewModel = ready()

        assertThat(viewModel.cues.value).containsExactly(hot(0, 1_000.0))

        cues.set(uri, listOf(hot(0, 1_000.0), hot(1, 2_000.0)))
        assertThat(viewModel.cues.value).hasSize(2)
    }

    @Test
    fun `a track change shows the new track's cues at once, never the previous track's`() {
        cues.set(uri, listOf(hot(0, 1_000.0)))
        val viewModel = ready()
        analyses.set("content://b", analysis())

        play("content://b", queueItemId = 41)

        assertThat(viewModel.cues.value).isEmpty()
        assertThat(cues.active).containsExactly("content://b")
    }

    @Test
    fun `with DJ mode off nothing is read and there are no cues`() {
        cues.set(uri, listOf(hot(0, 1_000.0)))
        analyses.set(uri, analysis())
        play(uri)

        val viewModel = viewModel(on = false)

        assertThat(viewModel.cues.value).isEmpty()
        assertThat(cues.active).isEmpty()
    }

    @Test
    fun `an empty pad sets a hot cue at the playhead, on the nearest beat while Q is on`() {
        val viewModel = ready()

        viewModel.onHotCue(2, 1_130.0)

        assertThat(cues.saves).containsExactly(uri to hot(2, 1_000.0))
        assertThat(seeks).isEmpty()
    }

    @Test
    fun `with Q off a hot cue is set exactly at the playhead`() {
        quantizeSetting.setQuantize(false)
        val viewModel = ready()

        viewModel.onHotCue(0, 1_130.0)

        assertThat(cues.saves.single().second.positionMs).isEqualTo(1_130.0)
    }

    @Test
    fun `quantizing follows the edited grid, not the analysed one`() {
        // 100 BPM edited in (a beat every 600 ms from 0): 1130 is nearer 1200 than 600.
        val viewModel = ready(analysis = analysis(edit = GridEdit(10_000, 0.0)))

        viewModel.onHotCue(0, 1_130.0)

        assertThat(cues.saves.single().second.positionMs).isWithin(1e-6).of(1_200.0)
    }

    @Test
    fun `a track with no beat grid sets the cue at the playhead even with Q on`() {
        val viewModel = ready(analysis = analysis(grid = null))

        viewModel.onHotCue(0, 1_130.0)

        assertThat(cues.saves.single().second.positionMs).isEqualTo(1_130.0)
    }

    @Test
    fun `a pad that is set jumps to its cue and changes nothing stored`() {
        cues.set(uri, listOf(hot(3, 12_345.4)))
        val viewModel = ready()

        viewModel.onHotCue(3, 90_000.0)

        assertThat(seeks).containsExactly(12_345L)
        assertThat(cues.saves).isEmpty()
    }

    @Test
    fun `a cue past the end of the track is not set`() {
        quantizeSetting.setQuantize(false)
        val viewModel = ready()

        viewModel.onHotCue(0, 250_000.0)

        assertThat(cues.saves.single().second.positionMs).isEqualTo(200_000.0)
    }

    @Test
    fun `pads and memory cues need an analysed track to be set, and nothing is stored without one`() {
        play(uri) // no analysis for it
        val viewModel = viewModel()

        viewModel.onHotCue(0, 1_000.0)
        viewModel.addMemoryCue(1_000.0)

        assertThat(cues.saves).isEmpty()
    }

    @Test
    fun `a track that is not identified stores nothing and does not fail`() {
        cues.identified = false
        val viewModel = ready()

        viewModel.onHotCue(0, 1_000.0)

        assertThat(cues.saves).isEmpty()
    }

    @Test
    fun `a store that fails leaves the cues as they were`() {
        cues.failure = IllegalStateException("disk full")
        val viewModel = ready()

        viewModel.onHotCue(0, 1_000.0)

        assertThat(viewModel.cues.value).isEmpty()
    }

    @Test
    fun `a memory cue is added at the playhead on the nearest beat`() {
        val viewModel = ready()

        viewModel.addMemoryCue(2_240.0)

        val cue = cues.saves.single().second
        assertThat(cue.kind).isEqualTo(CueKind.MEMORY)
        assertThat(cue.positionMs).isWithin(1e-6).of(2_000.0)
        assertThat(cue.color).isEqualTo(CuePalette.MEMORY_DEFAULT)
        assertThat(cue.id).isNotEmpty()
    }

    @Test
    fun `a double tap does not add a second memory cue even when the store is slow`() {
        cues.reachesScreens = false
        val viewModel = ready()

        viewModel.addMemoryCue(2_000.0)
        viewModel.addMemoryCue(2_001.0)

        assertThat(cues.saves).hasSize(1)
    }

    @Test
    fun `the memory cues are found one after another`() {
        cues.set(uri, DjCues.withMemoryCue(DjCues.withMemoryCue(emptyList(), "a", 10_000.0), "b", 20_000.0))
        val viewModel = ready()

        viewModel.nextMemoryCue(0.0)
        viewModel.nextMemoryCue(10_000.0)
        viewModel.nextMemoryCue(20_000.0) // none after the last: nothing happens
        viewModel.previousMemoryCue(30_000.0)
        viewModel.previousMemoryCue(10_000.0) // none before the first

        assertThat(seeks).containsExactly(10_000L, 20_000L, 20_000L).inOrder()
    }

    @Test
    fun `a cue is deleted by id`() {
        cues.set(uri, listOf(hot(0, 1_000.0), hot(1, 2_000.0)))
        val viewModel = ready()

        viewModel.deleteCue("hot-0")

        assertThat(cues.deletes).containsExactly(uri to "hot-0")
        assertThat(viewModel.cues.value.map { it.id }).containsExactly("hot-1")
    }

    @Test
    fun `a cue is renamed and recoloured, keeping its place`() {
        cues.set(uri, listOf(hot(0, 1_000.0)))
        val viewModel = ready()
        val blue = CuePalette.byIndex(10)!!

        viewModel.renameCue("hot-0", "  Drop ")
        viewModel.recolourCue("hot-0", blue)

        val cue = viewModel.cues.value.single()
        assertThat(cue.name).isEqualTo("Drop")
        assertThat(cue.color).isEqualTo(blue)
        assertThat(cue.positionMs).isEqualTo(1_000.0)
    }

    @Test
    fun `renaming or deleting a cue that is gone changes nothing`() {
        val viewModel = ready()

        viewModel.renameCue("hot-5", "x")
        viewModel.recolourCue("hot-5", CuePalette.COLORS[3])

        assertThat(cues.saves).isEmpty()
    }

    @Test
    fun `the Q setting is the stored one and can be switched`() {
        val viewModel = ready()

        assertThat(viewModel.quantize.value).isTrue()
        viewModel.setQuantize(false)

        assertThat(viewModel.quantize.value).isFalse()
        assertThat(quantizeSetting.quantize.value).isFalse()
    }

    @Test
    fun `a write after a track change goes to the new track`() {
        cues.reachesScreens = false
        val viewModel = ready()
        viewModel.addMemoryCue(2_000.0)
        analyses.set("content://b", analysis())

        play("content://b", queueItemId = 41)
        viewModel.addMemoryCue(2_000.0)

        assertThat(cues.saves.map { it.first }).containsExactly(uri, "content://b").inOrder()
    }
}
