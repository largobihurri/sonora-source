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
import io.sonora.core.domain.playback.DjLoopController
import io.sonora.core.domain.playback.DjLoopState
import io.sonora.core.domain.playback.PlaybackClock
import io.sonora.feature.dj.controls.DjPadMode
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

/** The loop controls of [DjViewModel]: commands to the controller, the stepper, saved loops as cues, and the pad mode. */
@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class DjViewModelLoopTest {

    private class Settings : DjModeSettings {
        override val enabled: StateFlow<Boolean> = MutableStateFlow(true)
        override fun setEnabled(enabled: Boolean) = Unit
        override val performance: StateFlow<PerformanceMode> = MutableStateFlow(PerformanceMode.DEFAULT)
        override fun setPerformance(performance: PerformanceMode) = Unit
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
    private val loops = FakeLoops()
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

    private fun viewModel() = DjViewModel(
        Settings(), Status(), analyses, playback, clock, Editor(), cues,
        FakeQuantize(on = true), loops,
    ) { seeks += it }

    private fun track(uri: String) = Track(
        id = 7, uri = uri, title = "t", artist = "a", album = "al", albumArtist = null, durationMs = 200_000,
        trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null, mimeType = "audio/flac", sizeBytes = 1,
    )

    private fun play(uri: String) {
        val track = track(uri)
        playback.value = PlaybackState(
            currentTrack = track,
            queue = listOf(QueueItem(39, track(uri + "-before")), QueueItem(40, track)),
            currentIndex = 1,
            isPlaying = true,
        )
    }

    /** 120 BPM from 0 ms: a beat every 500 ms. */
    private fun analysis(grid: DjBeatGrid? = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1)))) = DjAnalysis(
        durationMs = 200_000,
        grid = grid,
        key = null,
        waveform = BandWaveform(ByteArray(150), ByteArray(150), ByteArray(150), ByteArray(150), version = 1),
        provenance = AnalysisProvenance(1, BeatSource.MODEL, "beat-this", Accelerator.CPU, 1L),
    )

    private fun ready(analysis: DjAnalysis = analysis()): DjViewModel {
        analyses.set(uri, analysis)
        play(uri)
        return viewModel()
    }

    private val looping = DjLoopState(
        inMs = 1_010.0, outMs = 3_010.0, looping = true, available = true, gridInMs = 1_000.0, gridOutMs = 3_000.0,
    )

    // --- The buttons ----------------------------------------------------------------------------------------------

    @Test
    fun `the transport buttons go to the controller`() {
        val vm = ready()

        vm.loopIn()
        vm.loopOut()
        vm.exitLoop()
        vm.reloop()
        vm.halveLoop()
        vm.doubleLoop()

        assertThat(loops.calls).containsExactly("in", "out", "exit", "reloop", "half", "double").inOrder()
    }

    @Test
    fun `the loop state is the controller's`() {
        val vm = ready()
        loops.loop.value = looping

        assertThat(vm.loop.value).isEqualTo(looping)
    }

    // --- The beat-length stepper ----------------------------------------------------------------------------------

    @Test
    fun `the stepper starts at four beats and a tap engages a beat loop of the length shown`() {
        val vm = ready()

        assertThat(vm.loopPanel.value.beats).isEqualTo(4.0)
        vm.engageBeatLoop()

        assertThat(loops.calls).containsExactly("beat 4.0")
    }

    @Test
    fun `the arrows change the length without starting a loop while none plays`() {
        val vm = ready()

        vm.stepLoopBeats(+1)
        vm.stepLoopBeats(+1)
        assertThat(vm.loopPanel.value.beats).isEqualTo(16.0)
        vm.stepLoopBeats(-1)
        assertThat(vm.loopPanel.value.beats).isEqualTo(8.0)

        assertThat(loops.calls).isEmpty()
        vm.engageBeatLoop()
        assertThat(loops.calls).containsExactly("beat 8.0")
    }

    @Test
    fun `while a loop plays the arrows engage the new length at once`() {
        val vm = ready()
        loops.loop.value = looping

        vm.stepLoopBeats(-1)

        // The loop is 4 beats (2000 ms at 120 BPM): one step down is 2.
        assertThat(loops.calls).containsExactly("beat 2.0")
    }

    @Test
    fun `an arrow at the end of the sizes does nothing, even while a loop plays`() {
        val vm = ready()
        loops.loop.value = looping.copy(gridOutMs = 1_000.0 + 16_000.0, outMs = 1_010.0 + 16_000.0)

        // 16000 ms at 120 BPM is 32 beats, the longest.
        assertThat(vm.loopPanel.value.beats).isEqualTo(32.0)
        vm.stepLoopBeats(+1)

        assertThat(loops.calls).isEmpty()
        assertThat(vm.loopPanel.value.canStepUp).isFalse()
    }

    @Test
    fun `the stepper shows the length of the loop that plays, so a halving shows`() {
        val vm = ready()
        loops.loop.value = looping.copy(gridOutMs = 2_000.0, outMs = 2_010.0)

        assertThat(vm.loopPanel.value.beats).isEqualTo(2.0)
    }

    @Test
    fun `beat loops do nothing on a track without a grid`() {
        val vm = ready(analysis(grid = null))

        assertThat(vm.loopPanel.value.beatsEnabled).isFalse()
        vm.engageBeatLoop()
        vm.stepLoopBeats(+1)

        assertThat(loops.calls).isEmpty()
    }

    @Test
    fun `with loops unavailable the panel is off`() {
        val vm = ready()
        loops.loop.value = DjLoopState(available = false)

        assertThat(vm.loopPanel.value.available).isFalse()
        assertThat(vm.loopPanel.value.beatsEnabled).isFalse()
        vm.engageBeatLoop()
        assertThat(loops.calls).isEmpty()
    }

    // --- Saved loops ----------------------------------------------------------------------------------------------

    @Test
    fun `holding an empty pad while looping saves the loop there, in grid time`() {
        val vm = ready()
        loops.loop.value = looping

        vm.onPadLongPress(slot = 3)

        val saved = cues.saves.single().second
        assertThat(saved.id).isEqualTo(DjCue.hotId(3))
        assertThat(saved.kind).isEqualTo(CueKind.HOT)
        assertThat(saved.positionMs).isEqualTo(1_000.0)
        assertThat(saved.loopEndMs).isEqualTo(3_000.0)
        assertThat(saved.color).isEqualTo(CuePalette.defaultHot(3))
    }

    @Test
    fun `holding a pad with no loop playing saves nothing`() {
        val vm = ready()
        loops.loop.value = DjLoopState(1_010.0, 3_010.0, available = true, gridInMs = 1_000.0, gridOutMs = 3_000.0)

        vm.onPadLongPress(slot = 3)

        assertThat(cues.saves).isEmpty()
    }

    @Test
    fun `holding a pad that is set does not replace it`() {
        val vm = ready()
        cues.set(uri, listOf(DjCue(DjCue.hotId(3), CueKind.HOT, 3, 500.0, null, CuePalette.defaultHot(3), "")))
        loops.loop.value = looping

        vm.onPadLongPress(slot = 3)

        assertThat(cues.saves).isEmpty()
    }

    @Test
    fun `a loop with no grid time, as the seeking fallback, is not saved`() {
        val vm = ready()
        loops.loop.value = looping.copy(gridInMs = null, gridOutMs = null)

        vm.onPadLongPress(slot = 0)
        vm.addMemoryCue(positionMs = 1_010.0)

        // The memory button falls back to a plain cue at the playhead (on the beat while Q is on).
        assertThat(cues.saves.map { it.second.loopEndMs }).containsExactly(null)
    }

    @Test
    fun `tapping a hot loop pad engages the loop instead of jumping`() {
        val vm = ready()
        cues.set(uri, listOf(DjCue(DjCue.hotId(1), CueKind.HOT, 1, 8_000.0, 10_000.0, CuePalette.defaultHot(1), "")))

        vm.onHotCue(slot = 1, positionMs = 20_000.0)

        assertThat(loops.calls).containsExactly("engage 8000.0 10000.0")
        assertThat(seeks).isEmpty()
    }

    @Test
    fun `tapping a plain hot cue still jumps`() {
        val vm = ready()
        cues.set(uri, listOf(DjCue(DjCue.hotId(1), CueKind.HOT, 1, 8_000.0, null, CuePalette.defaultHot(1), "")))

        vm.onHotCue(slot = 1, positionMs = 20_000.0)

        assertThat(seeks).containsExactly(8_000L)
        assertThat(loops.calls).isEmpty()
    }

    @Test
    fun `the memory button while a loop plays saves a memory loop`() {
        val vm = ready()
        loops.loop.value = looping

        vm.addMemoryCue(positionMs = 2_000.0)

        val saved = cues.saves.single().second
        assertThat(saved.kind).isEqualTo(CueKind.MEMORY)
        assertThat(saved.positionMs).isEqualTo(1_000.0)
        assertThat(saved.loopEndMs).isEqualTo(3_000.0)
    }

    @Test
    fun `the memory button with no loop playing still adds a plain memory cue`() {
        val vm = ready()

        vm.addMemoryCue(positionMs = 2_130.0)

        val saved = cues.saves.single().second
        assertThat(saved.loopEndMs).isNull()
        assertThat(saved.positionMs).isEqualTo(2_000.0)
    }

    @Test
    fun `the next memory cue engages a memory loop it lands on`() {
        val vm = ready()
        cues.set(
            uri,
            listOf(
                DjCue("mem-a", CueKind.MEMORY, null, 5_000.0, 7_000.0, CuePalette.MEMORY_DEFAULT, ""),
                DjCue("mem-b", CueKind.MEMORY, null, 9_000.0, null, CuePalette.MEMORY_DEFAULT, ""),
            ),
        )

        vm.nextMemoryCue(positionMs = 1_000.0)
        vm.nextMemoryCue(positionMs = 6_000.0)

        assertThat(loops.calls).containsExactly("engage 5000.0 7000.0")
        assertThat(seeks).containsExactly(9_000L)
    }

    // --- The pad mode ---------------------------------------------------------------------------------------------

    @Test
    fun `the pads open as cues and the switch keeps the mode across the layout`() {
        val vm = ready()
        assertThat(vm.padMode.value).isEqualTo(DjPadMode.CUES)

        vm.setPadMode(DjPadMode.LOOP)

        assertThat(vm.padMode.value).isEqualTo(DjPadMode.LOOP)
    }
}
