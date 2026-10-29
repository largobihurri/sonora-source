package io.sonora.feature.nowplaying

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.sonora.core.designsystem.motion.prefersReducedMotion
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.domain.model.PlaybackState
import io.sonora.feature.dj.DjTimeline
import io.sonora.feature.dj.DjTimelineSizes
import io.sonora.feature.dj.DjViewModel
import io.sonora.feature.dj.controls.DjControlSizes
import io.sonora.feature.dj.controls.DjCuePanel
import io.sonora.feature.dj.controls.DjDeckRow
import io.sonora.feature.dj.controls.DjPanelLayout
import io.sonora.feature.dub.DubScreen
import io.sonora.feature.dub.DubViewModel
import io.sonora.feature.dub.controls.DubIndicators

/**
 * DUB mode's Now Playing layout (S2, design §4): the track header with its "…" menu (whose DUB Mode row leaves the
 * mode), a compact deck strip (DJ mode's waveform with its beat grid, playhead, cues and loop, and DJ mode's loop and hot
 * cue controls under it, so a loop can hold a riddim: [DubDeckControls]) and the DUB screen under it, which arranges
 * itself for portrait, landscape and tablet by the room it gets.
 * The beat LEDs follow the grid at the position heard, updated only when the beat changes and only while playing.
 */
@Composable
internal fun DubContent(
    state: PlaybackState,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    layout: NowPlayingLayout,
    bottomRow: BottomRow,
    frozen: Boolean = false,
    dj: DjViewModel = hiltViewModel(),
    dub: DubViewModel = hiltViewModel(),
) {
    val screen = rememberDjScreen(state, bottomRow, dj, frozen, viewModel.position)
    val grid = screen.ui.editableAnalysis?.effectiveGrid
    val beatLed = rememberBeatLed(grid, screen.position, playing = state.isPlaying && !frozen)
    val reducedMotion = LocalContext.current.prefersReducedMotion()
    DubScreen(
        viewModel = dub,
        beatLed = beatLed,
        reducedMotion = reducedMotion,
        modifier = Modifier.fillMaxSize().turningBlock(stage, layout, NowPlayingBlock.Controls),
    ) {
        Grabber(Modifier.align(Alignment.CenterHorizontally), compact = true)
        TrackHeader(
            screen.track,
            screen.menu,
            titleSize = 17.sp,
            artistSize = 15.sp,
            titleWeight = FontWeight.SemiBold,
            leading = { HeaderArtwork(screen.track, HeaderArtworkSize) },
            scrolls = headerScrolls(hidden = LocalArrangementRole.current() == ArrangementRole.Hidden, djMode = true, playing = state.isPlaying),
            star = { HeaderAccessories(screen.track, bottomRow) },
        )
        DjTimeline(
            timeline = screen.ui.timeline,
            durationMs = screen.durationMs,
            position = screen.position,
            zoom = screen.zoom,
            onZoomChange = dj::setZoom,
            onSeek = viewModel::seekTo,
            gridEdited = screen.gridEdited,
            outdated = screen.ui.analysisOutdated,
            onReanalyse = screen.requestReanalysis,
            onRetry = dj::retryAnalysis,
            cues = screen.cues,
            loop = screen.loop.region,
            sizes = DjTimelineSizes.Compact,
            modifier = Modifier.fillMaxWidth().height(DeckStripHeight),
        )
        DeckControls(screen)
    }
    screen.ReanalyseDialog()
}

/** DJ mode's loop and hot cue controls under the deck strip, in one row or two by the width they get. */
@Composable
private fun DeckControls(screen: DjScreen) {
    val dj = screen.dj
    val enabled = screen.editableAnalysis != null
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        when (dubDeckControls(maxWidth)) {
            DubDeckControls.ONE_ROW -> DjDeckRow(
                cues = screen.cues,
                enabled = enabled,
                position = screen.position,
                onPad = dj::onHotCue,
                mode = screen.padMode,
                onMode = dj::setPadMode,
                loop = screen.loop,
                loopActions = screen.loopActions,
                sizes = DubDeckSizes,
            )
            DubDeckControls.PAD_PANEL -> DjCuePanel(
                cues = screen.cues,
                quantize = screen.quantize,
                enabled = enabled,
                position = screen.position,
                onPad = dj::onHotCue,
                onQuantize = dj::setQuantize,
                onAddMemory = dj::addMemoryCue,
                onPreviousMemory = dj::previousMemoryCue,
                onNextMemory = dj::nextMemoryCue,
                onRename = dj::renameCue,
                onRecolour = dj::recolourCue,
                onDelete = dj::deleteCue,
                sizes = DubDeckSizes,
                mode = screen.padMode,
                onMode = dj::setPadMode,
                loop = screen.loop,
                loopActions = screen.loopActions,
            )
        }
    }
}

/**
 * How the DUB layout shows DJ mode's loop and hot cue controls: [ONE_ROW] ([DjDeckRow]) where its eight pads keep a usable
 * width (landscape, a tablet), else DJ mode's own two-row pad panel ([PAD_PANEL], [DjCuePanel]) at [DubDeckSizes].
 */
internal enum class DubDeckControls(val height: Dp) {
    ONE_ROW(DubDeckSizes.control),
    PAD_PANEL(DubDeckSizes.modePanel),
}

/** The [DubDeckControls] for a strip [width] wide. */
internal fun dubDeckControls(width: Dp): DubDeckControls =
    if (DjPanelLayout.deckRowFits(width, DubDeckSizes.gap)) DubDeckControls.ONE_ROW else DubDeckControls.PAD_PANEL

/** The deck controls' rows: a little under DJ mode's compact rows, so the DUB pads keep their room. */
internal val DubDeckSizes = DjControlSizes(control = 36.dp, slim = 30.dp, gap = 4.dp)

/** The lit beat LED at [position] (ms) on [grid], polled per frame only while [playing] and written only on a change. */
@Composable
private fun rememberBeatLed(grid: DjBeatGrid?, position: () -> Double, playing: Boolean): Int? {
    var lit by remember { mutableStateOf<Int?>(null) }
    val read by rememberUpdatedState(position)
    LaunchedEffect(grid, playing) {
        if (grid == null) {
            lit = null
            return@LaunchedEffect
        }
        lit = DubIndicators.beatLed(beatsIntoBar(grid, read()))
        while (playing) {
            withFrameMillis { }
            val next = DubIndicators.beatLed(beatsIntoBar(grid, read()))
            if (next != lit) lit = next
        }
    }
    return lit
}

/** Beats since a bar's first beat at [timeMs], from the tempo segment playing then. */
internal fun beatsIntoBar(grid: DjBeatGrid, timeMs: Double): Double? {
    if (!timeMs.isFinite() || grid.segments.isEmpty()) return null
    val segment = grid.segments.lastOrNull { it.startMs <= timeMs } ?: grid.segments.first()
    return (timeMs - segment.startMs) / segment.beatLengthMs + (segment.beatInBarAtStart - 1)
}

/** The deck strip: the scrolling waveform over the overview and the time row. */
private val DeckStripHeight = 120.dp
private val HeaderArtworkSize = 44.dp
