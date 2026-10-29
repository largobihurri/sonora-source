package io.sonora.feature.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.sonora.core.designsystem.layout.LocalWindowClass
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.domain.model.PlaybackState
import io.sonora.feature.dj.DjTimeline
import io.sonora.feature.dj.DjTimelineSizes
import io.sonora.feature.dj.DjViewModel
import io.sonora.feature.dj.controls.DjCuePanel
import io.sonora.feature.dj.controls.DjGridEditor
import io.sonora.feature.dj.controls.DjTempoColumn
import io.sonora.feature.dj.controls.DjTempoSection

/**
 * The landscape arrangement in DJ mode (D2.10), within the safe insets ([TurnStageLayout] pads it); the numbers are
 * [DjLandscapeMetrics] and [djLandscapeWidths]:
 *
 * - the drag handle, then a slim header: the small artwork, title and artist, previous / play / next, and the usual
 *   accessories (the DJ chip first) and "…" menu;
 * - the scrolling waveform across the whole width, taking the height the rows under it leave (at least 120 dp);
 * - under it, on the left, the overview with the times and the Cues | Loop pad panel; on the right, beside both, the
 *   tempo section (readout, fader and nudges, range and Master Tempo). The analysis cards and the old-analysis chip are
 *   [DjTimeline]'s own, in compact looks for the short waveform;
 * - tall windows (a tablet) also get a row with the volume slider and the bottom capsule under the deck; a phone has no
 *   room for it (the hardware keys set the volume; Up Next, the equalizer and the rest are one rotation away).
 *
 * On a large window (a tablet, T6) the arrangement is the single deck of [DjLandscapeDensity.TABLET] (see
 * [DjLandscapeMetrics]): the header without the transport; the waveform across the whole width; the overview and times;
 * under them previous / play / next on the leading side of the deck and the pad panel in the middle; the volume slider and
 * the bottom capsule under those; and at the trailing edge a tall tempo column, from the overview down to the bottom, whose
 * [DjTempoColumn] vertical fader is like a CDJ pitch fader. The editor (below) then takes the right column and the
 * transport stands under the waveform's column.
 *
 * With the grid editor open ("…" menu, Edit beat grid) the pad panel and the tempo section make way: the editor takes the
 * right column from the header down, and the waveform and overview stay in view to its left, the transport in the header
 * above still at hand to listen while editing. Done, Back, a track change or leaving DJ mode close it.
 *
 * The header and the waveform turn as the artwork block and the deck as the controls block, like the portrait one's. The
 * timeline is one movable composition, so opening the editor moves the waveform without rebuilding it. Everything else
 * (state, position source, the idle-redraw rules) is shared with [DjPortraitContent] through [DjScreen].
 */
@Composable
internal fun DjLandscapeContent(
    state: PlaybackState,
    volume: Float,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    bottomRow: BottomRow,
    frozen: Boolean = false,
    dj: DjViewModel = hiltViewModel(),
) {
    val landscape = NowPlayingLayout.Landscape
    val screen = rememberDjScreen(state, bottomRow, dj, frozen, viewModel.position)
    val editing = screen.editing
    val current by rememberUpdatedState(screen)
    val timeline = remember {
        movableContentOf<Modifier, DjTimelineSizes, Dp> { modifier, sizes, endInset ->
            val s = current
            DjTimeline(
                timeline = s.ui.timeline,
                durationMs = s.durationMs,
                position = s.position,
                zoom = s.zoom,
                onZoomChange = s.dj::setZoom,
                onSeek = viewModel::seekTo,
                gridEdited = s.gridEdited,
                outdated = s.ui.analysisOutdated,
                onReanalyse = s.requestReanalysis,
                onRetry = s.dj::retryAnalysis,
                modifier = modifier,
                cues = s.cues,
                loop = s.loop.region,
                sizes = sizes,
                endInset = endInset,
            )
        }
    }
    val density = LocalDensity.current
    val large = LocalWindowClass.current.isLarge
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Below the window's top bar (T71), as the standard landscape arrangement clears it: the handle's row and the
        // lift are already counted; the rest of the arrangement moves down.
        val clearance = with(density) {
            (stage.topRowClearance(landscape) - (grabberRowHeight(compact = true) + TopRowLift).roundToPx()).coerceAtLeast(0).toDp()
        }
        val metrics = djLandscapeMetrics(maxHeight - clearance, editorOpen = editing != null, width = maxWidth, large = large)
        val widths = djLandscapeWidths(maxWidth - metrics.sidePadding * 2, tablet = metrics.tabletDeck)
        Column(Modifier.fillMaxSize()) {
            Grabber(
                Modifier.align(Alignment.CenterHorizontally).turningBlock(stage, landscape, NowPlayingBlock.Handle),
                compact = metrics.compactGrabber,
            )
            Box(Modifier.fillMaxSize().padding(horizontal = metrics.sidePadding).padding(top = clearance, bottom = metrics.bottomPadding)) {
                if (editing == null) {
                    if (metrics.tabletDeck) {
                        TabletDeckLayout(state, volume, viewModel, stage, bottomRow, screen, metrics, widths, timeline)
                    } else {
                        DeckLayout(state, volume, viewModel, stage, bottomRow, screen, metrics, widths, timeline)
                    }
                } else {
                    EditorLayout(state, viewModel, stage, bottomRow, screen, editing, metrics, widths, timeline)
                }
            }
        }
        screen.ReanalyseDialog()
    }
}

/** The normal arrangement: header and waveform above, the deck (overview, pads, tempo and, on a tablet, volume) under. */
@Composable
private fun DeckLayout(
    state: PlaybackState,
    volume: Float,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    bottomRow: BottomRow,
    screen: DjScreen,
    metrics: DjLandscapeMetrics,
    widths: DjLandscapeWidths,
    timeline: @Composable (Modifier, DjTimelineSizes, Dp) -> Unit,
) {
    val landscape = NowPlayingLayout.Landscape
    val bottomRowHeight = if (metrics.showsBottomRow) metrics.bottomRowGap + metrics.bottomButtons else 0.dp
    // The deck's pad panel sits under the overview and the times; the tempo section beside them (see the timeline's endInset).
    val padPanel = metrics.deckGap + metrics.controls.modePanel
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(bottom = padPanel + bottomRowHeight).turningBlock(stage, landscape, NowPlayingBlock.Artwork)) {
            DjHeader(state, viewModel, bottomRow, screen, metrics)
            timeline(Modifier.weight(1f).fillMaxWidth(), metrics.timeline, widths.gap + widths.tempo)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().turningBlock(stage, landscape, NowPlayingBlock.Controls)) {
            Box(Modifier.fillMaxWidth().height(metrics.deckHeight)) {
                DjCuePanel(
                    cues = screen.cues,
                    quantize = screen.quantize,
                    enabled = screen.editableAnalysis != null,
                    position = screen.position,
                    onPad = screen.dj::onHotCue,
                    onQuantize = screen.dj::setQuantize,
                    onAddMemory = screen.dj::addMemoryCue,
                    onPreviousMemory = screen.dj::previousMemoryCue,
                    onNextMemory = screen.dj::nextMemoryCue,
                    onRename = screen.dj::renameCue,
                    onRecolour = screen.dj::recolourCue,
                    onDelete = screen.dj::deleteCue,
                    modifier = Modifier.align(Alignment.BottomStart).width(minOf(widths.left, MaxPadPanelWidth)),
                    sizes = metrics.controls,
                    mode = screen.padMode,
                    onMode = screen.dj::setPadMode,
                    loop = screen.loop,
                    loopActions = screen.loopActions,
                )
                DjTempoSection(
                    screen.ui.readout,
                    screen.trackBpm,
                    modifier = Modifier.align(Alignment.BottomEnd).width(widths.tempo),
                    sizes = metrics.controls,
                )
            }
            if (metrics.showsBottomRow) {
                Spacer(Modifier.height(metrics.bottomRowGap))
                Row(Modifier.fillMaxWidth().height(metrics.bottomButtons), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { VolumeSlider(volume, viewModel::setVolume) }
                    Spacer(Modifier.width(BottomCapsuleGap))
                    Box(Modifier.width(metrics.bottomCapsuleWidth)) { BottomButtons(bottomRow, side = metrics.bottomButtons) }
                }
            }
        }
    }
}

/**
 * The tablet's single deck (T6): the header and the full-width waveform above; the overview and times, then the transport
 * and the pad panel, then the volume slider and the bottom capsule, in the left part; and the tempo column at the trailing
 * edge, from the overview down to the bottom, so its vertical fader is as tall as the deck. The timeline's [endInset] keeps
 * the overview clear of the column. Numbers: [DjLandscapeMetrics] and [djLandscapeWidths] (tablet).
 */
@Composable
private fun TabletDeckLayout(
    state: PlaybackState,
    volume: Float,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    bottomRow: BottomRow,
    screen: DjScreen,
    metrics: DjLandscapeMetrics,
    widths: DjLandscapeWidths,
    timeline: @Composable (Modifier, DjTimelineSizes, Dp) -> Unit,
) {
    val landscape = NowPlayingLayout.Landscape
    // Under the waveform's column: the gap, the pad panel row and the bottom row (the overview is the timeline's own).
    val deckRows = metrics.deckGap + metrics.controls.modePanel + metrics.bottomRowGap + metrics.bottomButtons
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(bottom = deckRows).turningBlock(stage, landscape, NowPlayingBlock.Artwork)) {
            DjHeader(state, viewModel, bottomRow, screen, metrics)
            timeline(Modifier.weight(1f).fillMaxWidth(), metrics.timeline, widths.gap + widths.tempo)
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(metrics.tempoColumnHeight)
                .turningBlock(stage, landscape, NowPlayingBlock.Controls),
        ) {
            Column(Modifier.align(Alignment.BottomStart).width(widths.left)) {
                Row(Modifier.fillMaxWidth().height(metrics.controls.modePanel), verticalAlignment = Alignment.CenterVertically) {
                    DeckTransport(state, viewModel, metrics, Modifier.width(metrics.deckTransportWidth))
                    Spacer(Modifier.width(DjTabletTransportGap))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        DjCuePanel(
                            cues = screen.cues,
                            quantize = screen.quantize,
                            enabled = screen.editableAnalysis != null,
                            position = screen.position,
                            onPad = screen.dj::onHotCue,
                            onQuantize = screen.dj::setQuantize,
                            onAddMemory = screen.dj::addMemoryCue,
                            onPreviousMemory = screen.dj::previousMemoryCue,
                            onNextMemory = screen.dj::nextMemoryCue,
                            onRename = screen.dj::renameCue,
                            onRecolour = screen.dj::recolourCue,
                            onDelete = screen.dj::deleteCue,
                            modifier = Modifier.widthIn(max = DjTabletMaxPadPanel),
                            sizes = metrics.controls,
                            mode = screen.padMode,
                            onMode = screen.dj::setPadMode,
                            loop = screen.loop,
                            loopActions = screen.loopActions,
                        )
                    }
                }
                Spacer(Modifier.height(metrics.bottomRowGap))
                Row(Modifier.fillMaxWidth().height(metrics.bottomButtons), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { VolumeSlider(volume, viewModel::setVolume) }
                    Spacer(Modifier.width(BottomCapsuleGap))
                    Box(Modifier.width(metrics.bottomCapsuleWidth)) { BottomButtons(bottomRow, side = metrics.bottomButtons) }
                }
            }
            DjTempoColumn(
                screen.ui.readout,
                screen.trackBpm,
                modifier = Modifier.align(Alignment.TopEnd).width(widths.tempo).fillMaxHeight(),
                sizes = metrics.controls,
            )
        }
    }
}

/** Previous, play and next on the tablet deck: the big play button between the two glyphs ([DjLandscapeMetrics.deckTransportWidth] wide). */
@Composable
private fun DeckTransport(state: PlaybackState, viewModel: NowPlayingViewModel, metrics: DjLandscapeMetrics, modifier: Modifier = Modifier) {
    CompactTransportRow(
        isPlaying = state.isPlaying,
        onPrevious = viewModel::previous,
        onToggle = viewModel::togglePlayPause,
        onNext = viewModel::next,
        modifier = modifier,
        playSize = metrics.transportPlay,
        glyphSize = metrics.transportGlyph,
        spacing = 4.dp,
    )
}

/** The arrangement with the grid editor open: the header over the waveform and its overview, the editor on the right. */
@Composable
private fun EditorLayout(
    state: PlaybackState,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    bottomRow: BottomRow,
    screen: DjScreen,
    editing: DjAnalysis,
    metrics: DjLandscapeMetrics,
    widths: DjLandscapeWidths,
    timeline: @Composable (Modifier, DjTimelineSizes, Dp) -> Unit,
) {
    val landscape = NowPlayingLayout.Landscape
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.turningBlock(stage, landscape, NowPlayingBlock.Artwork)) { DjHeader(state, viewModel, bottomRow, screen, metrics) }
        Row(Modifier.weight(1f).fillMaxWidth().turningBlock(stage, landscape, NowPlayingBlock.Controls)) {
            timeline(Modifier.weight(1f).fillMaxHeight(), metrics.timeline, 0.dp)
            Spacer(Modifier.width(widths.gap))
            Column(Modifier.width(widths.tempo).fillMaxHeight()) {
                // Six rows of 34 dp fit under the header on a phone ([DjLandscapeMetrics.editorFits]); the scroll is only a
                // safety net for a window shorter than that.
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    DjGridEditor(
                        analysis = editing,
                        position = screen.position,
                        onCommand = screen.dj::editGrid,
                        onReset = screen.dj::resetGrid,
                        onDone = screen.dj::closeGridEditor,
                        sizes = metrics.controls,
                    )
                }
                if (metrics.tabletDeck) {
                    // The tablet header has no transport, so it stands at the bottom of this column, where the free room is.
                    Box(Modifier.fillMaxWidth().height(metrics.controls.gap + metrics.transportPlay).padding(top = metrics.controls.gap), contentAlignment = Alignment.Center) {
                        DeckTransport(state, viewModel, metrics)
                    }
                }
            }
        }
    }
}

/** The slim header: artwork, title and artist, the transport, then the chip, sleep timer, star and "…". */
@Composable
private fun DjHeader(state: PlaybackState, viewModel: NowPlayingViewModel, bottomRow: BottomRow, screen: DjScreen, metrics: DjLandscapeMetrics) {
    Column {
        Spacer(Modifier.height(metrics.headerGapAbove))
        Box(Modifier.fillMaxWidth().height(metrics.headerHeight), contentAlignment = Alignment.CenterStart) {
            TrackHeader(
                screen.track,
                screen.menu,
                titleSize = metrics.titleSize,
                artistSize = metrics.artistSize,
                titleWeight = FontWeight.SemiBold,
                leading = { HeaderArtwork(screen.track, metrics.headerArtwork) },
                // Paused, the title stands still too, so the screen can go idle (D2.7b).
                scrolls = headerScrolls(
                    hidden = LocalArrangementRole.current() == ArrangementRole.Hidden,
                    djMode = true,
                    playing = state.isPlaying,
                ),
                star = {
                    // A tablet's transport stands on the deck, not in the header.
                    if (metrics.showsHeaderTransport) {
                        CompactTransportRow(
                            isPlaying = state.isPlaying,
                            onPrevious = viewModel::previous,
                            onToggle = viewModel::togglePlayPause,
                            onNext = viewModel::next,
                            modifier = Modifier,
                            playSize = metrics.transportPlay,
                            glyphSize = metrics.transportGlyph,
                            spacing = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    HeaderAccessories(screen.track, bottomRow)
                },
            )
        }
        Spacer(Modifier.height(metrics.headerGapBelow))
    }
}

/** The pad panel never grows past this on a wide window (it is 328 dp on a phone). */
private val MaxPadPanelWidth = 560.dp

/** Between the volume slider and the bottom capsule (the capsule's width is [DjLandscapeMetrics.bottomCapsuleWidth]). */
private val BottomCapsuleGap = 16.dp

