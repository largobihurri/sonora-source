package io.sonora.feature.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjCue
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playback.ClockSample
import io.sonora.feature.dj.DjTimelineView
import io.sonora.feature.dj.DjUiState
import io.sonora.feature.dj.DjViewModel
import io.sonora.feature.dj.ReanalyseConfirm
import io.sonora.feature.dj.askBeforeReanalysing
import io.sonora.feature.dj.controls.DjLoopActions
import io.sonora.feature.dj.controls.DjLoopPanelState
import io.sonora.feature.dj.controls.DjPadMode
import io.sonora.feature.dj.waveform.WaveformZoom
import io.sonora.feature.dj.waveform.rememberDjPosition
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the DJ arrangements show or call, gathered once for both of them ([DjPortraitContent] and
 * [DjLandscapeContent]): the view model's state, the per-frame position, the "…" menu with the DJ rows, the grid editor
 * being open and the re-analysis confirmation. The arrangements differ in where they put things, not in what they know.
 */
@Stable
internal class DjScreen(
    val dj: DjViewModel,
    val ui: DjUiState,
    val zoom: WaveformZoom,
    val cues: List<DjCue>,
    val quantize: Boolean,
    val padMode: DjPadMode,
    val loop: DjLoopPanelState,
    val loopActions: DjLoopActions,
    val position: () -> Double,
    val track: Track?,
    val durationMs: Long,
    val menu: MoreMenuActions,
    val requestReanalysis: () -> Unit,
    /** The analysis being edited while the grid editor is open, else null. */
    val editing: DjAnalysis?,
    /** The track's own BPM where it plays (the grid's, as edited; the nudges scale it themselves). */
    val trackBpm: Double?,
    private val askReanalyse: Boolean,
    private val dismissReanalyse: () -> Unit,
) {
    val editableAnalysis: DjAnalysis? get() = ui.editableAnalysis
    val gridEdited: Boolean get() = ui.gridEdit != null

    /** The confirmation the "Analyse again" actions raise for a track whose grid was edited (D2.11); nothing otherwise. */
    @Composable
    fun ReanalyseDialog() {
        if (askReanalyse) {
            ReanalyseConfirm(
                onConfirm = {
                    dismissReanalyse()
                    dj.reanalyse()
                },
                onDismiss = dismissReanalyse,
            )
        }
    }
}

/**
 * The [DjScreen] of the arrangement around. With [frozen], the arrangement is fading out of a DJ mode that has just been
 * turned off (D2.10): the view model has already dropped its analysis, cues and loop, so what it shows is kept as it was
 * for the length of the fade, instead of the waveform blanking while it leaves.
 */
@Composable
internal fun rememberDjScreen(
    state: PlaybackState,
    bottomRow: BottomRow,
    dj: DjViewModel,
    frozen: Boolean,
    /** The player's position (ms), four ticks a second: [state] has none, so the screen does not recompose with them. */
    playerPosition: StateFlow<Long>,
): DjScreen {
    val ui = retainedWhile(frozen, dj.uiState.collectAsStateWithLifecycle().value)
    val zoom by dj.zoom.collectAsStateWithLifecycle()
    val editorOpen = retainedWhile(frozen, dj.gridEditorOpen.collectAsStateWithLifecycle().value)
    val cues = retainedWhile(frozen, dj.cues.collectAsStateWithLifecycle().value)
    val quantize by dj.quantize.collectAsStateWithLifecycle()
    val padMode by dj.padMode.collectAsStateWithLifecycle()
    val loop = retainedWhile(frozen, dj.loopPanel.collectAsStateWithLifecycle().value)
    val loopActions = remember(dj) {
        DjLoopActions(
            onIn = dj::loopIn,
            onOut = dj::loopOut,
            onExit = dj::exitLoop,
            onReloop = dj::reloop,
            onHalve = dj::halveLoop,
            onDouble = dj::doubleLoop,
            onStep = dj::stepLoopBeats,
            onBeatLoop = dj::engageBeatLoop,
            onSaveToPad = dj::onPadLongPress,
        )
    }
    val track = state.currentTrack
    val durationMs = state.durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0L
    val shown = LocalArrangementRole.current() == ArrangementRole.Shown
    val slowPosition = playerPosition.collectAsStateWithLifecycle() // read in the lambdas below, never in composition
    val position = rememberDjPosition(
        clock = dj.clock,
        itemId = state.queue.getOrNull(state.currentIndex)?.id ?: ClockSample.NO_ITEM,
        playing = djPositionFrames(state.isPlaying, shown),
        fallbackPositionMs = { slowPosition.value },
    )
    // Back closes the grid editor first; an arrangement kept hidden for the next turn (or fading out) must not take it.
    BackHandler(enabled = editorOpen && shown) { dj.closeGridEditor() }
    val editableAnalysis = ui.editableAnalysis
    // Analysing again asks first when the grid was edited (the edit is kept, and still overrides the new analysis).
    var askReanalyse by remember { mutableStateOf(false) }
    val gridEdited = ui.gridEdit != null
    val requestReanalysis = remember(dj, gridEdited) {
        { if (askBeforeReanalysing(gridEdited)) askReanalyse = true else dj.reanalyse() }
    }
    // The "…" menu offers the editor and the new analysis in the DJ arrangements, where they can be shown.
    val menu = remember(bottomRow.menu, editableAnalysis != null, ui.canReanalyse, requestReanalysis) {
        bottomRow.menu.copy(
            onEditGrid = dj::openGridEditor,
            gridEditable = editableAnalysis != null,
            onReanalyse = requestReanalysis,
            canReanalyse = ui.canReanalyse,
        )
    }
    // The track's own tempo where it plays: only a variable-tempo grid changes it with the position, so read lazily it
    // recomposes at a segment change, not with every tick.
    val timelineView = remember(ui.timeline) { DjTimelineView.of(ui.timeline) }
    val trackBpm by remember(timelineView) { derivedStateOf { timelineView.trackBpmAt(slowPosition.value) } }
    return DjScreen(
        dj = dj,
        ui = ui,
        zoom = zoom,
        cues = cues,
        quantize = quantize,
        padMode = padMode,
        loop = loop,
        loopActions = loopActions,
        position = position,
        track = track,
        durationMs = durationMs,
        menu = menu,
        requestReanalysis = requestReanalysis,
        editing = editableAnalysis.takeIf { editorOpen },
        trackBpm = trackBpm,
        askReanalyse = askReanalyse,
        dismissReanalyse = { askReanalyse = false },
    )
}

/** [value] while not [frozen]; the last one seen before it froze afterwards. */
@Composable
internal fun <T> retainedWhile(frozen: Boolean, value: T): T {
    val kept = remember { Kept(value) }
    if (!frozen) kept.value = value
    return kept.value
}

private class Kept<T>(var value: T)
