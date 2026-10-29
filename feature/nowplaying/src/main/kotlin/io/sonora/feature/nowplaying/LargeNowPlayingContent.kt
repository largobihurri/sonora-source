package io.sonora.feature.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.domain.model.PlaybackState

/*
 * Now Playing on a tablet (T5), in the style of Apple Music on iPadOS: fullscreen over the artwork backdrop, with a large
 * artwork and controls scaled up for the room. The sizes come from largeLayoutPlan; these composables only place them.
 *
 * Both arrangements keep the two blocks that turn with the display (the artwork pane and the controls, see TurnStage), so
 * the rotation turn between portrait and landscape works as on a phone. Lyrics and Up Next take the artwork pane's place
 * (never a sheet), which is why the turn needs no third block.
 */

/** Below the controls, as in the phone's arrangements. */
private val LargeBottomMargin = 12.dp

/** Between the landscape controls' rows (in the controls' own scaled dp). */
private val LandscapeControlsGap = 22.dp

/**
 * The landscape tablet arrangement, within the safe insets ([TurnStageLayout] pads it): the artwork on the leading side,
 * as large as the height and the room left by the controls allow, vertically centred; on the trailing side, centred
 * vertically, the title and artist, the scrubber, the transport row, the volume and the bottom capsule row, at
 * [LargeLayoutPlan.controlsScale]. A panel ([SidePanel]) fills the artwork's side instead of the artwork.
 */
@Composable
internal fun LargeLandscapeContent(
    state: PlaybackState,
    volume: Float,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    bottomRow: BottomRow,
    lyricsActions: LyricsActions,
    panel: SidePanel,
) {
    val landscape = NowPlayingLayout.Landscape
    val track = state.currentTrack
    Column(Modifier.fillMaxSize()) {
        Grabber(Modifier.align(Alignment.CenterHorizontally).turningBlock(stage, landscape, NowPlayingBlock.Handle))
        BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = LargeBottomMargin)) {
            val plan = largeLayoutPlan(landscape, maxWidth, maxHeight)
            Row(
                Modifier.fillMaxSize().padding(horizontal = plan.sidePadding),
                horizontalArrangement = Arrangement.spacedBy(plan.columnGap),
            ) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    ArtworkPane(state, viewModel, stage, landscape, panel, lyricsActions, plan)
                }
                Box(
                    Modifier
                        .width(plan.controlsWidth)
                        .fillMaxHeight()
                        .turningBlock(stage, landscape, NowPlayingBlock.Controls)
                        // Below the window's top bar (T71), as on a phone: only the controls move down, inside their block.
                        .clearOfTopBar {
                            (stage.topRowClearance(landscape) - (GrabberRowHeight + TopRowLift).roundToPx()).coerceAtLeast(0)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ScaledDensity(plan.controlsScale) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(LandscapeControlsGap, Alignment.CenterVertically),
                        ) {
                            TrackHeader(
                                track,
                                bottomRow.menu,
                                titleSize = 22.sp,
                                artistSize = 19.sp,
                                titleWeight = FontWeight.SemiBold,
                                star = { HeaderAccessories(track, bottomRow) },
                            )
                            PlayerScrubber(state.durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0L, viewModel)
                            // Room enough for shuffle and repeat, which a phone's landscape leaves to the Up Next sheet.
                            TransportRow(
                                isPlaying = state.isPlaying,
                                shuffle = state.shuffleEnabled,
                                repeat = state.repeatMode,
                                onShuffle = viewModel::toggleShuffle,
                                onRepeat = viewModel::cycleRepeat,
                                onPrevious = viewModel::previous,
                                onToggle = viewModel::togglePlayPause,
                                onNext = viewModel::next,
                            )
                            VolumeSlider(volume, viewModel::setVolume, speakerTouchSide = touchTargetSide(plan.controlsScale))
                            BottomButtons(bottomRow, plan.controlsScale)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The portrait tablet arrangement, within the safe insets: the artwork on top, as large as the controls leave room for
 * and no wider than they are, and below it the controls at [LargeLayoutPlan.controlsScale]. A panel ([SidePanel]) takes
 * all the room above the controls instead of the artwork.
 */
@Composable
internal fun LargePortraitContent(
    state: PlaybackState,
    volume: Float,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    bottomRow: BottomRow,
    lyricsActions: LyricsActions,
    panel: SidePanel,
) {
    val portrait = NowPlayingLayout.Portrait
    Column(Modifier.fillMaxSize()) {
        Grabber(Modifier.align(Alignment.CenterHorizontally).turningBlock(stage, portrait, NowPlayingBlock.Handle))
        BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = LargeBottomMargin)) {
            val plan = largeLayoutPlan(portrait, maxWidth, maxHeight)
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .padding(horizontal = plan.sidePadding)
                    .widthIn(max = plan.controlsWidth)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ArtworkPane(state, viewModel, stage, portrait, panel, lyricsActions, plan)
                }
                Box(Modifier.fillMaxWidth().turningBlock(stage, portrait, NowPlayingBlock.Controls)) {
                    ScaledDensity(plan.controlsScale) {
                        Controls(
                            state,
                            volume,
                            viewModel,
                            bottomRow,
                            scale = plan.controlsScale,
                            speakerTouchSide = touchTargetSide(plan.controlsScale),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The artwork block of [layout]: the artwork as the largest square within [LargeLayoutPlan.artworkSide], or the panel
 * filling the whole room the caller gives it. It is the block the turn moves; the artwork shows the same opaque picture
 * before and after a turn, a panel does not.
 */
@Composable
private fun ArtworkPane(
    state: PlaybackState,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    layout: NowPlayingLayout,
    panel: SidePanel,
    lyricsActions: LyricsActions,
    plan: LargeLayoutPlan,
) {
    val pane = if (panel == SidePanel.None) {
        // Capped first, then squared within the cap and the room: a window short on height gives a smaller square.
        Modifier.sizeIn(maxWidth = plan.artworkSide, maxHeight = plan.artworkSide).aspectRatio(1f)
    } else {
        Modifier.fillMaxSize()
    }
    Box(pane.turningBlock(stage, layout, NowPlayingBlock.Artwork, opaque = panel == SidePanel.None)) {
        when (panel) {
            SidePanel.Queue -> ScaledDensity(plan.controlsScale) { QueueSidePanel(state, viewModel) }
            SidePanel.Lyrics -> ScaledDensity(plan.controlsScale) { ArtworkOrLyrics(state, showLyrics = true, lyricsActions) }
            SidePanel.None -> ArtworkOrLyrics(state, showLyrics = false, lyricsActions)
        }
    }
}

/**
 * [content] drawn [scale] times larger: every size, text and gap grows alike (a larger density, the opposite of the phone
 * landscape's 90 %, T71c), crisp rather than stretched, and touch targets follow. The room it fills is unchanged.
 */
@Composable
internal fun ScaledDensity(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale), content = content)
}
