package io.sonora.feature.dj

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjAnalysisControl
import io.sonora.core.djmodel.DjAnalysisPauseSource
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjAnalyzerInfo
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.djmodel.CueColor
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.DjCues
import io.sonora.core.djmodel.DjQuantizeSettings
import io.sonora.core.djmodel.DjTrackAnalyses
import io.sonora.core.djmodel.DjTrackCues
import io.sonora.core.djmodel.DjTrackGridEditor
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djmodel.GridEditor
import io.sonora.core.djmodel.Quantize
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.playback.DjLoopController
import io.sonora.core.domain.playback.DjLoopState
import io.sonora.core.domain.playback.PlaybackClock
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.dubmodel.DubModeSettings
import io.sonora.feature.dj.controls.BeatLengthStepper
import io.sonora.feature.dj.controls.DjLoopPanelState
import io.sonora.feature.dj.controls.DjPadMode
import io.sonora.feature.dj.waveform.WaveformZoom
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The DJ screen's state: the playing entry and the analysis of its track, as one [DjUiState]. The playback tempo is not part of
 * it (the tempo controls follow it themselves, see [DjTrackReadout]).
 *
 * The deck (the waveform and its analysis, the beat grid, the cues and the loop panel) runs while DJ mode or DUB mode is
 * on: the DUB layout keeps DJ mode's deck strip, its pads and its loops, and the two layouts are never on together. The
 * beat grid editor stays the DJ layout's alone. While both modes are off nothing is read: the analysis status (reading
 * it may start the analysis queue) and the stored analyses are only subscribed to while the deck runs, and for the
 * track playing now (a track change drops the previous analysis, never showing it for the new track).
 */
@HiltViewModel
class DjViewModel internal constructor(
    settings: DjModeSettings,
    status: DjAnalysisStatusSource,
    analyses: DjTrackAnalyses,
    playback: Flow<PlaybackState>,
    /** The player's per-frame clock, for the waveform's position ([io.sonora.feature.dj.waveform.rememberDjPosition]). */
    val clock: PlaybackClock,
    private val editor: DjTrackGridEditor,
    private val trackCues: DjTrackCues,
    private val quantizeSettings: DjQuantizeSettings,
    /** The DJ loop (D4.2b): the loop controls call it and the screen follows its state. */
    private val loops: DjLoopController,
    /** Retry and re-analysis of the playing track (D2.11). */
    private val control: DjAnalysisControl = NoAnalysisControl,
    /** Whether the analysis waits for the phone to cool down. */
    pausedByHeat: StateFlow<Boolean> = MutableStateFlow(false),
    /** The analyser this build ships: a stored analysis from an older one is flagged. */
    private val analyzerVersion: Int = 0,
    /** Whether DUB mode is on: its layout keeps the deck (the waveform, the grid, the cues and the loops). */
    dubMode: StateFlow<Boolean> = MutableStateFlow(false),
    /** Moves the playhead (the player keeps playing or staying paused as it was). */
    private val seek: (Long) -> Unit,
) : ViewModel() {

    @Inject
    constructor(
        settings: DjModeSettings,
        status: DjAnalysisStatusSource,
        analyses: DjTrackAnalyses,
        playback: PlaybackController,
        clock: PlaybackClock,
        editor: DjTrackGridEditor,
        trackCues: DjTrackCues,
        quantizeSettings: DjQuantizeSettings,
        loops: DjLoopController,
        control: DjAnalysisControl,
        pause: DjAnalysisPauseSource,
        analyzer: DjAnalyzerInfo,
        dubMode: DubModeSettings,
    ) : this(
        settings, status, analyses, playback.state, clock, editor, trackCues, quantizeSettings, loops,
        control, pause.pausedByHeat, analyzer.version, dubMode.enabled, playback::seekTo,
    )

    /** The DJ layout is on: the grid editor is its alone. */
    private val djLayoutOn = settings.enabled

    /** The deck runs: in DJ mode, and in DUB mode, which keeps DJ mode's deck strip, cues and loops. */
    private val deckOn: StateFlow<Boolean> = combine(settings.enabled, dubMode) { dj, dub -> dj || dub }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.enabled.value || dubMode.value)

    private val zoomState = MutableStateFlow(WaveformZoom.DEFAULT)

    /**
     * The span of the scrolling waveform, kept here so it outlives the layout that shows it (a rotation, the DJ layout
     * leaving and coming back) for as long as the screen lives.
     */
    val zoom: StateFlow<WaveformZoom> = zoomState.asStateFlow()

    fun setZoom(zoom: WaveformZoom) {
        zoomState.value = zoom
    }

    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
    val uiState: StateFlow<DjUiState> = deckOn
        .flatMapLatest { on -> if (on) enabledState(status, analyses, playback, pausedByHeat) else flowOf(DjUiState.OFF) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, if (deckOn.value) DjUiState.WAITING else DjUiState.OFF)

    /**
     * Analyses the playing track again, replacing its stored analysis (the "Analyse again" row and the "old analysis" chip).
     * The user's grid edit and cues are not touched: they are stored apart and the edited grid still overrides the new one.
     * Nothing happens while both DJ mode and DUB mode are off or nothing plays.
     */
    fun reanalyse() {
        val uri = uiState.value.header.uri ?: return
        if (!deckOn.value) return
        control.reanalyse(uri)
    }

    /**
     * The state card's "Try again": a failed track gets a new attempt (which never replaces a stored result), a track that
     * has an analysis (no beat grid found, or a failed new run) is analysed again, replacing it.
     */
    fun retryAnalysis() {
        val ui = uiState.value
        val uri = ui.header.uri ?: return
        if (!deckOn.value) return
        if (ui.timeline is DjTimelineState.Failed) control.retry(uri) else control.reanalyse(uri)
    }

    private val editorOpenFor = MutableStateFlow<String?>(null)

    /**
     * Whether the beat grid editor is open. It opens for the playing track ([openGridEditor]) and only while that track
     * can be edited, in the DJ layout; a track change or DJ mode turning off (to DUB mode too) closes it for good (coming
     * back to the same track later does not reopen it).
     */
    val gridEditorOpen: StateFlow<Boolean> = combine(editorOpenFor, uiState, djLayoutOn) { openFor, ui, dj ->
        dj && openFor != null && openFor == ui.header.uri && ui.editableAnalysis != null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            uiState.collect { ui ->
                val openFor = editorOpenFor.value
                if (openFor != null && openFor != ui.header.uri) editorOpenFor.value = null
            }
        }
        viewModelScope.launch {
            djLayoutOn.collect { on -> if (!on) editorOpenFor.value = null }
        }
    }

    /** Opens the beat grid editor for the playing track, in the DJ layout, when it has an analysis to edit. */
    fun openGridEditor() {
        if (!djLayoutOn.value) return
        val ui = uiState.value
        if (ui.editableAnalysis != null) editorOpenFor.value = ui.header.uri
    }

    fun closeGridEditor() {
        editorOpenFor.value = null
    }

    /** One write at a time, so commands made in quick succession each build on the one before. */
    private val editLock = Mutex()

    /**
     * The last edit this model wrote for [uri] (`null` for a reset): the store reports it back a moment later, so a
     * command made in that moment builds on this instead of the older edit the screen still shows.
     */
    private class Written(val uri: String, val edit: GridEdit?)

    private var written: Written? = null

    /**
     * Applies [command] to the grid edit of the playing track, with the playhead at [positionMs]. The edit is saved and
     * the screens follow the store, so the waveform and the readout change as soon as the store reports it. Nothing
     * happens without a track whose analysis can be edited.
     */
    fun editGrid(command: GridEditCommand, positionMs: Double) {
        if (uiState.value.editableAnalysis == null) return
        viewModelScope.launch {
            editLock.withLock {
                val ui = uiState.value
                val uri = ui.header.uri ?: return@withLock
                val analysis = ui.editableAnalysis ?: return@withLock
                val known = written?.takeIf { it.uri == uri }
                val current = if (known != null) known.edit else analysis.gridEdit
                val next = command.applyTo(GridEditor(analysis.grid, analysis.durationMs.toDouble()), current, positionMs)
                if (saving { editor.save(uri, next) }) written = Written(uri, next)
            }
        }
    }

    /** Removes the grid edit of the playing track: its analysed grid is in force again. */
    fun resetGrid() {
        if (uiState.value.editableAnalysis == null) return
        viewModelScope.launch {
            editLock.withLock {
                val uri = uiState.value.header.uri ?: return@withLock
                if (saving { editor.reset(uri) }) written = Written(uri, null)
            }
        }
    }

    /**
     * The cues of the playing track: empty while both DJ mode and DUB mode are off, nothing plays or the track is not identified yet, and
     * empty (never the previous track's) from the moment the track changes until its own cues arrive.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
    val cues: StateFlow<List<DjCue>> = combine(deckOn, uiState.map { it.header.uri }.distinctUntilChanged()) { on, uri ->
        uri.takeIf { on }
    }
        .distinctUntilChanged()
        .flatMapLatest { uri -> if (uri == null) flowOf(emptyList()) else trackCues.cuesOf(uri).onStart { emit(emptyList()) } }
        // The store has caught up with whatever this model wrote: it is the truth again.
        .onEach { writtenCues = null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether Quantize is on: cues are set, and loop points will be, on the nearest beat of the grid in force. */
    val quantize: StateFlow<Boolean> = quantizeSettings.quantize

    fun setQuantize(on: Boolean) = quantizeSettings.setQuantize(on)

    /** One write at a time, so cues set in quick succession each build on the one before. */
    private val cueLock = Mutex()

    /**
     * The cues this model last wrote for [uri]: the store reports them back a moment later, so a cue added in that
     * moment (a double tap) builds on these instead of the older list the screen still shows. Dropped as soon as the
     * store reports.
     */
    private class WrittenCues(val uri: String, val cues: List<DjCue>)

    private var writtenCues: WrittenCues? = null

    /**
     * A hot cue pad was pressed with the playhead at [positionMs]: an empty pad sets its cue there (on the nearest beat
     * while Q is on); a pad that is set jumps to its cue and the player goes on as it was, playing or paused; a pad that
     * holds a loop (a hot loop) engages it. A jump is immediate, to the cue's stored time: the cue was put on the grid when
     * it was set, so the track lands on a beat.
     */
    fun onHotCue(slot: Int, positionMs: Double) {
        viewModelScope.launch {
            cueLock.withLock {
                val uri = uiState.value.header.uri ?: return@withLock
                val existing = DjCues.hotCue(currentCues(uri), slot)
                if (existing != null) {
                    recall(existing)
                    return@withLock
                }
                val at = cuePosition(positionMs) ?: return@withLock
                store(uri, DjCues.withHotCue(currentCues(uri), slot, at), DjCue.hotId(slot))
            }
        }
    }

    /**
     * Adds a memory cue at [positionMs] (on the nearest beat while Q is on), unless one is already there. While a loop
     * plays it saves the loop instead, as a memory loop at the loop's own points ([positionMs] is not used).
     */
    fun addMemoryCue(positionMs: Double) {
        viewModelScope.launch {
            cueLock.withLock {
                val uri = uiState.value.header.uri ?: return@withLock
                val id = "mem-${UUID.randomUUID()}"
                val before = currentCues(uri)
                val (gridIn, gridOut) = savableLoop()
                if (gridIn != null && gridOut != null) {
                    val after = LoopCues.withMemoryLoop(before, id, gridIn, gridOut)
                    if (after != before) store(uri, after, LoopCues.memoryLoopId(after, id, gridIn))
                    return@withLock
                }
                val at = cuePosition(positionMs) ?: return@withLock
                val after = DjCues.withMemoryCue(before, id, at)
                if (after.size > before.size) store(uri, after, id)
            }
        }
    }

    /** Jumps to the first memory cue after [positionMs] (engaging it if it is a memory loop); nothing happens when there is none. */
    fun nextMemoryCue(positionMs: Double) {
        DjCues.nextMemory(cues.value, positionMs)?.let(::recall)
    }

    /** Jumps to the last memory cue before [positionMs] (engaging it if it is a memory loop); nothing happens when there is none. */
    fun previousMemoryCue(positionMs: Double) {
        DjCues.previousMemory(cues.value, positionMs)?.let(::recall)
    }

    /** The grid times `(In, Out)` of the loop that plays, or `(null, null)` when there is none or it cannot be saved. */
    private fun savableLoop(): Pair<Double?, Double?> {
        if (!loopPanel.value.canSave) return null to null
        val state = loops.loop.value
        return state.gridInMs to state.gridOutMs
    }

    /** A cue was called: a saved loop engages (the player goes to its In when it is not inside it), any other cue is a jump. */
    private fun recall(cue: DjCue) {
        val end = cue.loopEndMs
        if (end != null) loops.engage(cue.positionMs, end) else seek(cue.positionMs.roundToLong())
    }

    // --- Loops (D4.2c) ----------------------------------------------------------------------------------------------

    /** The loop the controller reports, in the time of the playback clock (and the grid's, for saving). */
    val loop: StateFlow<DjLoopState> = loops.loop

    private val padModeState = MutableStateFlow(DjPadMode.CUES)

    /** Which face the pad panel shows (the hot cue pads, or the loop controls); kept here so a layout change does not reset it. */
    val padMode: StateFlow<DjPadMode> = padModeState.asStateFlow()

    fun setPadMode(mode: DjPadMode) {
        padModeState.value = mode
    }

    private val chosenBeats = MutableStateFlow(BeatLengthStepper.DEFAULT)

    /**
     * What the loop buttons show and can do: the controller's state with the track's grid (beat loops need one) and the
     * beat length of the stepper. Loops are unavailable (everything off) with bit-perfect output armed, which the loop sink
     * reports in [DjLoopState.available].
     */
    val loopPanel: StateFlow<DjLoopPanelState> = combine(loops.loop, chosenBeats, uiState) { state, chosen, ui ->
        val analysis = ui.editableAnalysis
        val grid = analysis?.effectiveGrid
        val gridIn = state.gridInMs
        val gridOut = state.gridOutMs
        val measured = if (analysis != null && gridIn != null && gridOut != null) {
            BeatLengthStepper.measured(gridIn, gridOut, grid, analysis.durationMs.toDouble())
        } else {
            null
        }
        DjLoopPanelState.of(state, hasGrid = grid != null, chosenBeats = chosen, measuredBeats = measured)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DjLoopPanelState.OFF)

    /** Loop In (marks the audible point; on the nearest beat while Q is on). */
    fun loopIn() = loops.loopIn()

    /** Loop Out: closes the loop opened by Loop In. */
    fun loopOut() = loops.loopOut()

    /** Exit: leaves the loop without a jump. */
    fun exitLoop() = loops.exit()

    /** Reloop: starts the remembered loop again. */
    fun reloop() = loops.reloop()

    fun halveLoop() = loops.halve()

    fun doubleLoop() = loops.double()

    /** The beat-length stepper's middle was tapped: a beat loop of the length it shows. Needs a grid. */
    fun engageBeatLoop() {
        val panel = loopPanel.value
        if (!panel.beatsEnabled) return
        chosenBeats.value = panel.beats
        loops.beatLoop(panel.beats)
    }

    /**
     * An arrow of the stepper: [delta] sizes up (positive) or down. The length is chosen, and, while a loop plays, engaged at
     * once (a CDJ's beat loop dial); from the length of the loop that plays when it is one of the sizes. Needs a grid.
     */
    fun stepLoopBeats(delta: Int) {
        val panel = loopPanel.value
        if (!panel.beatsEnabled || !BeatLengthStepper.canStep(panel.beats, delta)) return
        val next = BeatLengthStepper.stepped(panel.beats, delta)
        chosenBeats.value = next
        if (panel.engaged) loops.beatLoop(next)
    }

    /**
     * A hot cue pad was held. While a loop plays and the pad is empty it takes the loop (a hot loop, at the grid times of the
     * loop); a pad that is set is never replaced here (holding it edits its cue, in the panel).
     */
    fun onPadLongPress(slot: Int) {
        viewModelScope.launch {
            cueLock.withLock {
                val uri = uiState.value.header.uri ?: return@withLock
                val (gridIn, gridOut) = savableLoop()
                if (gridIn == null || gridOut == null) return@withLock
                val before = currentCues(uri)
                val after = LoopCues.withHotLoop(before, slot, gridIn, gridOut)
                if (after !== before) store(uri, after, DjCue.hotId(slot))
            }
        }
    }

    /** Deletes the cue [id] of the playing track. */
    fun deleteCue(id: String) {
        viewModelScope.launch {
            cueLock.withLock {
                val uri = uiState.value.header.uri ?: return@withLock
                val before = currentCues(uri)
                if (before.none { it.id == id }) return@withLock
                if (saving { trackCues.delete(uri, id) }) writtenCues = WrittenCues(uri, DjCues.removed(before, id))
            }
        }
    }

    /** Renames the cue [id] (trimmed, cut to the longest name). */
    fun renameCue(id: String, name: String) = changeCue(id) { list -> DjCues.renamed(list, id, name) }

    /** Gives the cue [id] the colour [color]. */
    fun recolourCue(id: String, color: CueColor) = changeCue(id) { list -> DjCues.recoloured(list, id, color) }

    private fun changeCue(id: String, change: (List<DjCue>) -> List<DjCue>) {
        viewModelScope.launch {
            cueLock.withLock {
                val uri = uiState.value.header.uri ?: return@withLock
                val before = currentCues(uri)
                if (before.none { it.id == id }) return@withLock
                store(uri, change(before), id)
            }
        }
    }

    /** The cues to build on: what this model wrote last for [uri] while the store has not reported, else what it reports. */
    private fun currentCues(uri: String): List<DjCue> = writtenCues?.takeIf { it.uri == uri }?.cues ?: cues.value

    /** Saves the cue [id] of [after] and remembers [after] as the cues of [uri] until the store reports. */
    private suspend fun store(uri: String, after: List<DjCue>, id: String) {
        val cue = after.firstOrNull { it.id == id } ?: return
        if (saving { trackCues.save(uri, cue) }) writtenCues = WrittenCues(uri, after)
    }

    /**
     * Where a cue set with the playhead at [positionMs] goes: on the nearest beat of the grid in force while Q is on
     * (the playhead as it is without a grid or with Q off), inside the track. `null` without an analysed track.
     */
    private fun cuePosition(positionMs: Double): Double? {
        val analysis = uiState.value.editableAnalysis ?: return null
        val duration = analysis.durationMs.toDouble()
        val at = if (quantize.value) Quantize.snap(positionMs, analysis.effectiveGrid, durationMs = duration) else positionMs
        return if (at.isFinite()) at.coerceIn(0.0, duration) else null
    }

    /** Runs a write; a store that fails leaves the grid as it was (the screen shows what is stored), not a crash. */
    private suspend fun saving(write: suspend () -> Boolean): Boolean = try {
        write()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "The beat grid edit could not be saved", e)
        false
    }

    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
    private fun enabledState(
        status: DjAnalysisStatusSource,
        analyses: DjTrackAnalyses,
        playback: Flow<PlaybackState>,
        pausedByHeat: Flow<Boolean>,
    ): Flow<DjUiState> {
        val uris = playback.map(::playingUri).distinctUntilChanged()
        val statuses = combine(uris, status.status) { uri, all -> uri?.let(all::get) }.distinctUntilChanged()
        // Each analysis travels with the uri it belongs to, so the one of the previous track is never taken for the new one.
        val analysed: Flow<Pair<String?, DjAnalysis?>> = uris.flatMapLatest { uri ->
            if (uri == null) flowOf(null to null) else analyses.analysisOf(uri).map { uri to it }
        }
        // The playback tempo is not here: it changes with every step of a fader drag, and the screens would recompose with
        // each. The tempo controls follow it themselves and apply it to the track's readout (DjTrackReadout.at).
        return combine(playback, statuses, analysed, pausedByHeat.distinctUntilChanged()) { state, playingStatus, found, paused ->
            assemble(PlayingContext(state, playingStatus, found.second.takeIf { found.first == playingUri(state) }, paused))
        }
    }

    private class PlayingContext(val playback: PlaybackState, val status: AnalysisStatus?, val analysis: DjAnalysis?, val paused: Boolean)

    private fun assemble(playing: PlayingContext): DjUiState {
        val state = playing.playback
        val timeline = DjTimelineState.of(enabled = true, playing.status, playing.analysis, playing.paused)
        val analysis = playing.analysis
        // A constant tempo reads the same everywhere, so the position is not followed (it would change the state every
        // 250 ms); a variable one needs the position to pick its segment.
        val readoutAt = if (analysis?.effectiveGrid?.isConstant == false) state.positionMs else 0L
        val outdated = analysis?.isOlderThan(analyzerVersion) == true
        return DjUiState(headerOf(state), timeline, analysis?.let { DjTrackReadout.of(it, readoutAt) }, outdated)
    }

    private fun playingUri(state: PlaybackState): String? = state.currentTrack?.uri?.takeIf(String::isNotEmpty)

    private fun headerOf(state: PlaybackState): DjHeader {
        val track = state.currentTrack ?: return DjHeader.NONE
        return DjHeader(
            uri = playingUri(state),
            queueItemId = state.queue.getOrNull(state.currentIndex)?.id,
            durationMs = state.durationMs.takeIf { it > 0 } ?: track.durationMs,
            isPlaying = state.isPlaying,
        )
    }
}

private const val TAG = "SonoraDjGrid"

/** No analysis behind the screen (tests, previews): nothing can be asked. */
private object NoAnalysisControl : DjAnalysisControl {
    override fun retry(uri: String) = Unit
    override fun reanalyse(uri: String) = Unit
}
