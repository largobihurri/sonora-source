package io.sonora.feature.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.layout.LocalWindowClass
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Track
import io.sonora.feature.dj.DjTimeline
import io.sonora.feature.dj.DjViewModel
import io.sonora.feature.dj.controls.DjCuePanel
import io.sonora.feature.dj.controls.DjGridEditor
import io.sonora.feature.dj.controls.DjTempoSection
import io.sonora.feature.dj.waveform.rememberDjPosition

/**
 * The portrait arrangement in DJ mode (D2.7), within the safe insets ([TurnStageLayout] pads it): the drag handle; a
 * header with the small artwork, title and artist and the usual accessories (the DJ chip first); the timeline (the
 * scrolling waveform taking the room left, the overview, the times and the analysis status); then the tempo section
 * (readout, fader, BPM nudges, range, Master Tempo, reset), the transport, the volume and the bottom capsule. The
 * header and timeline turn as the artwork block, the rest as the controls block, so a rotation turns them like the
 * standard arrangement's.
 *
 * Above the tempo section sits the pad panel ([DjCuePanel]), whose `Cues | Loop` switch swaps two faces of the same
 * height (D4.2c): the hot cue pads A to H with Q and the memory cues, or the loop controls (IN, OUT, EXIT / RELOOP, 1/2X, 2X
 * and the beat-length stepper); the waveform and the overview mark the cues and wash the loop that is set. The "…" menu's Edit beat grid row opens the grid editor ([DjGridEditor]) in place of
 * the cue controls, the tempo section, the volume row and the bottom capsule, so the waveform and its red bar lines
 * stay in view above it (and the transport below it, to listen while editing); Done, Back, a track change or leaving
 * DJ mode close it. An "Edited" badge sits in the time row under the overview while the grid is the user's, next to a small
 * "Old analysis · Update" chip while the stored analysis comes from an older analyser. The waveform area itself tells the
 * state of the analysis (waiting, analysing, paused by the heat, failed, no beat), and the "…" menu's "Analyse again" row
 * (confirmed first when the grid was edited), the chip and the cards' buttons ask for a new one (D2.11).
 *
 * On a large window (a tablet, T6) the same arrangement is roomier ([DjDensity.TABLET]): 56 dp touch rows, an 88 dp play
 * button, a 64 dp artwork, and no 560 dp cap, so the waveform, the overview, the pads and the tempo section span the whole
 * width and the waveform is far taller. The transport row and the bottom capsule keep a width of their own, centred, so
 * their buttons do not drift apart. Phones are untouched: the density and the cap come from [djPortraitMetrics].
 *
 * Height budget (D4.1b): the scrolling waveform is the one flexible row, and every row around it has a fixed, named
 * height ([DjPortraitMetrics]). The density of those rows ([DjDensity]) is the roomiest that leaves the waveform at
 * least [DjWaveformMinHeight] in the height this arrangement is given, with the grid editor open or not; a phone about
 * 700 dp tall gets the compact one, and only a phone too short even for that loses the volume row.
 *
 * The waveforms read a per-frame position ([rememberDjPosition] over the view model's playback clock, falling back to
 * the player's 250 ms position); it runs only while playing and while this arrangement is the one shown (a hidden one
 * kept for the next turn, or fading out in a DJ on/off swap, stays still). Seeks go through Now Playing's view model to the playback controller.
 *
 * Paused, nothing here asks for a frame (D2.7b): the position publishes once per seek, the header's marquee stands
 * still ([headerScrolls]) and Now Playing keeps the artwork backdrop's drift off in DJ mode ([backdropDrifts]), so the
 * translucent controls are not re-sampled. Playing, only the waveform, overview and time layers redraw per frame.
 */
@Composable
internal fun DjPortraitContent(
    state: PlaybackState,
    volume: Float,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    bottomRow: BottomRow,
    /** The arrangement is fading out of a DJ mode just turned off: it keeps showing what it showed ([rememberDjScreen]). */
    frozen: Boolean = false,
    dj: DjViewModel = hiltViewModel(),
) {
    val portrait = NowPlayingLayout.Portrait
    val screen = rememberDjScreen(state, bottomRow, dj, frozen, viewModel.position)
    val ui = screen.ui
    val editing = screen.editing
    val position = screen.position
    val menu = screen.menu
    val track = screen.track
    val large = LocalWindowClass.current.isLarge
    // The rows around the scrolling waveform have fixed heights; the density is the roomiest that leaves it its minimum.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = djPortraitMetrics(maxHeight, editorOpen = editing != null, large = large)
        val tablet = metrics.density == DjDensity.TABLET
        val compact = !tablet && metrics.density != DjDensity.COMFORTABLE
        Column(Modifier.fillMaxSize()) {
            Grabber(
                Modifier.align(Alignment.CenterHorizontally).turningBlock(stage, portrait, NowPlayingBlock.Handle),
                compact = metrics.compactGrabber,
            )
            Column(
                Modifier.fillMaxSize().padding(horizontal = metrics.sidePadding).padding(bottom = metrics.bottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.weight(1f).widthIn(max = metrics.contentMaxWidth).fillMaxWidth().turningBlock(stage, portrait, NowPlayingBlock.Artwork)) {
                    Spacer(Modifier.height(metrics.headerGapAbove))
                    TrackHeader(
                        track,
                        menu,
                        titleSize = if (tablet) 22.sp else if (compact) 17.sp else 19.sp,
                        artistSize = if (tablet) 18.sp else if (compact) 15.sp else 17.sp,
                        titleWeight = FontWeight.SemiBold,
                        leading = { HeaderArtwork(track, metrics.headerArtwork) },
                        // Paused, the title stands still too, so the screen can go idle (D2.7b).
                        scrolls = headerScrolls(
                            hidden = LocalArrangementRole.current() == ArrangementRole.Hidden,
                            djMode = true,
                            playing = state.isPlaying,
                        ),
                        star = { HeaderAccessories(track, bottomRow) },
                    )
                    Spacer(Modifier.height(metrics.headerGapBelow))
                    DjTimeline(
                        timeline = ui.timeline,
                        durationMs = screen.durationMs,
                        position = position,
                        zoom = screen.zoom,
                        onZoomChange = dj::setZoom,
                        onSeek = viewModel::seekTo,
                        gridEdited = screen.gridEdited,
                        outdated = ui.analysisOutdated,
                        onReanalyse = screen.requestReanalysis,
                        onRetry = dj::retryAnalysis,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        cues = screen.cues,
                        loop = screen.loop.region,
                        sizes = metrics.timeline,
                    )
                }
                Column(
                    Modifier
                        .widthIn(max = metrics.contentMaxWidth)
                        .fillMaxWidth()
                        .turningBlock(stage, portrait, NowPlayingBlock.Controls)
                        .padding(top = metrics.controlsTopGap),
                    verticalArrangement = Arrangement.spacedBy(metrics.controls.gap),
                ) {
                    if (editing != null) {
                        // The editor takes the cue panel's and the tempo section's place, and the volume row's and the
                        // capsule's: the waveform stays in view above it, at the same minimum height.
                        DjGridEditor(
                            analysis = editing,
                            position = position,
                            onCommand = dj::editGrid,
                            onReset = dj::resetGrid,
                            onDone = dj::closeGridEditor,
                            sizes = metrics.controls,
                        )
                    } else {
                        DjCuePanel(
                            cues = screen.cues,
                            quantize = screen.quantize,
                            enabled = screen.editableAnalysis != null,
                            position = position,
                            onPad = dj::onHotCue,
                            onQuantize = dj::setQuantize,
                            onAddMemory = dj::addMemoryCue,
                            onPreviousMemory = dj::previousMemoryCue,
                            onNextMemory = dj::nextMemoryCue,
                            onRename = dj::renameCue,
                            onRecolour = dj::recolourCue,
                            onDelete = dj::deleteCue,
                            sizes = metrics.controls,
                            mode = screen.padMode,
                            onMode = dj::setPadMode,
                            loop = screen.loop,
                            loopActions = screen.loopActions,
                        )
                        DjTempoSection(ui.readout, screen.trackBpm, sizes = metrics.controls)
                    }
                    CentredUpTo(metrics.transportMaxWidth) {
                        TransportRow(
                            isPlaying = state.isPlaying,
                            shuffle = state.shuffleEnabled,
                            repeat = state.repeatMode,
                            onShuffle = viewModel::toggleShuffle,
                            onRepeat = viewModel::cycleRepeat,
                            onPrevious = viewModel::previous,
                            onToggle = viewModel::togglePlayPause,
                            onNext = viewModel::next,
                            playSize = metrics.transportPlay,
                            glyphSize = metrics.transportGlyph,
                        )
                    }
                    if (editing == null) {
                        if (metrics.showsVolume) VolumeSlider(volume, viewModel::setVolume)
                        CentredUpTo(metrics.capsuleMaxWidth) { BottomButtons(bottomRow, side = metrics.bottomButtons) }
                    }
                }
            }
        }
        screen.ReanalyseDialog()
    }
}

/**
 * [content] at the width it fills, but at most [max] wide, centred: the transport row and the bottom capsule fill their
 * width, which on a tablet would spread their buttons far apart. Where the column is not wider than [max] (a phone) it is
 * the plain full width, as without this.
 */
@Composable
private fun CentredUpTo(max: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = max).fillMaxWidth()) { content() }
    }
}

/** The song's artwork as a [size] thumbnail (56 dp, 44 dp in the compact densities) at the head of the DJ header. */
@Composable
internal fun HeaderArtwork(track: Track?, size: Dp) {
    val shape = RoundedCornerShape(HeaderArtworkCorner)
    Artwork(
        model = track?.artworkUri,
        modifier = Modifier
            .size(size)
            .shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black),
        shape = shape,
        contentDescription = track?.let { stringResource(R.string.now_playing_artwork, it.album) },
    )
}

private val HeaderArtworkCorner = 8.dp
