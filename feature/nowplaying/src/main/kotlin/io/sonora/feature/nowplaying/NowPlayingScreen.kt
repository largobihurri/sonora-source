package io.sonora.feature.nowplaying

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.GlossyGrabber
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.component.coverflow.CoverFlowHandoff
import io.sonora.core.designsystem.component.translucentGloss
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.LocalWindowClass
import io.sonora.core.designsystem.motion.SheetBackMotion
import io.sonora.core.designsystem.motion.SheetPresentation
import io.sonora.core.designsystem.motion.prefersReducedMotion
import io.sonora.core.designsystem.motion.sheetPresentFrame
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.translucent.LocalTranslucentPolicy
import io.sonora.core.designsystem.translucent.TranslucentBubble
import io.sonora.core.designsystem.translucent.rememberTranslucentBackdrop
import io.sonora.core.designsystem.translucent.translucentBackdropSource
import io.sonora.core.designsystem.translucent.translucentBubbleLayer
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playback.SleepTimerState
import io.sonora.feature.equalizer.EqualizerPanel
import io.sonora.feature.equalizer.R as EqualizerR
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

private const val MEDIA_OUTPUT_ACTION = "com.android.settings.panel.action.MEDIA_OUTPUT"
private const val MEDIA_OUTPUT_PACKAGE_EXTRA = "com.android.settings.panel.extra.PACKAGE_NAME"

/**
 * Full-screen Apple Music style Now Playing sheet, presented by the app shell through [sheet], which the mini player
 * drives too. Dragging the sheet down moves it with the finger (and up again, freely); the release settles it open or
 * dismissed by distance and velocity. A back gesture shrinks and lowers it as it progresses and dismisses it on commit.
 * The "…" menu's "Add to Playlist" hands the current song to [onAddToPlaylist] (the shell shows the picker).
 * With [turnsBlocks], a display rotation turns the artwork and the controls into their new places, the drag handle
 * cross-fading in place and the backdrop fixed on the display (see [TurnStage]); the shell sets it
 * together with a seamless window rotation. In gravity mode (Settings) the groups hang level as the phone tilts
 * instead (see [GravityLevel]), laid out in the phone's own portrait frame whichever way the shell turns the window
 * ([GravityFrameLayout]); each magnetic detent that catches them goes to [onGravityDetent] (a quarter turn 0..3 of the
 * phone's frame), from which the shell latches where a dismissal leads ([GravityDestination]).
 * Opened from a capsule (T67: the Cover Flow's "Now Playing" capsule, or the mini player), it is drawn into [bubble]
 * instead of sliding up: the translucent surface grows out of the capsule, the backdrop and then the controls fade in
 * inside it; with [handoff] its artwork is the capsule's shared one, carried into its place (and back) while its own
 * hides (see [ArtworkShare]).
 */
@Composable
fun NowPlayingScreen(
    sheet: SheetPresentation,
    modifier: Modifier = Modifier,
    onAddToPlaylist: (Track) -> Unit = {},
    turnsBlocks: Boolean = false,
    onGravityDetent: (Int) -> Unit = {},
    bubble: TranslucentBubble? = null,
    handoff: CoverFlowHandoff? = null,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    // Without the position (always 0 in it): the player republishes four times a second while it plays and the screen
    // would recompose with each. What shows the position reads viewModel.position itself (PlayerScrubber, the lyrics, the DJ screen).
    val state by viewModel.steadyState.collectAsStateWithLifecycle()
    val position = viewModel.position.collectAsStateWithLifecycle()
    // One step of the backdrop's drift for each change of the playing position (four a second): it shares the progress bar's frame.
    val driftTicks = remember(position) { snapshotFlow { position.value } }
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val buttons by viewModel.buttons.collectAsStateWithLifecycle()
    val showLyrics by viewModel.showLyrics.collectAsStateWithLifecycle()
    val lyricsPanel by viewModel.lyrics.collectAsStateWithLifecycle()
    val fileInfo by viewModel.fileInfo.collectAsStateWithLifecycle()
    val favourite by viewModel.favourite.collectAsStateWithLifecycle()
    val gravity by viewModel.gravity.collectAsStateWithLifecycle()
    val abLoop by viewModel.abLoop.collectAsStateWithLifecycle()
    val tagEditor: TagEditorViewModel = hiltViewModel()
    val tagEdit by tagEditor.state.collectAsStateWithLifecycle()
    val sleepTimer: SleepTimerViewModel = hiltViewModel()
    val sleep by sleepTimer.state.collectAsStateWithLifecycle()
    val sleepCustomMinutes by sleepTimer.customMinutes.collectAsStateWithLifecycle()
    val djMode: DjModeViewModel = hiltViewModel()
    val djEnabled by djMode.enabled.collectAsStateWithLifecycle()
    val dubMode: DubModeViewModel = hiltViewModel()
    val dubEnabled by dubMode.enabled.collectAsStateWithLifecycle()
    // DJ's and DUB's layouts are deck layouts: neither hangs level, shows lyrics or lets the backdrop drift.
    val deckOn = deckLayout(djEnabled, dubEnabled).deckOn
    val windowClass = LocalWindowClass.current
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolume()
        onPauseOrDispose { }
    }

    val scope = rememberCoroutineScope()
    var showQueue by rememberSaveable { mutableStateOf(false) }
    // Hoisted here, not kept by the bottom row, so an open equalizer survives a rotation (which swaps the layout).
    var showEqualizer by rememberSaveable { mutableStateOf(false) }
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }
    val back = remember { SheetBackMotion() }
    val context = LocalContext.current
    // Only a sheet heading open owns back; while it is being pulled up or is closing, back goes to the screen below.
    val backEnabled = sheet.targetOpen
    PredictiveBackHandler(enabled = backEnabled) { events ->
        val reduced = context.prefersReducedMotion()
        try {
            events.collect { if (!reduced) back.moved(it.progress) }
        } catch (e: CancellationException) {
            scope.launch { back.progress.animateTo(0f, spring(dampingRatio = 0.8f)) }
            throw e
        }
        sheet.close() // the exit slide continues from the shrunken (or dragged) frame
    }
    // A dismissed sheet keeps its back frame for the exit slide; if it is opened again before that ends (back is
    // re-enabled), it must come back full size.
    LaunchedEffect(backEnabled) {
        if (backEnabled) back.reshown()
    }

    // DJ mode lays out its own portrait arrangement, which never hangs level.
    val hangsLevel = gravityApplies(gravity, deckOn, largeScreen = windowClass.isLarge)
    // What takes the artwork's place (T5): lyrics, and on a tablet Up Next too instead of a sheet; the taps keep one open.
    val style = nowPlayingStyle(windowClass, deckOn)
    val lyricsOpen = lyricsShown(showLyrics, deckOn)
    val panels = PanelState(lyrics = lyricsOpen, queue = showQueue)
    val panel = sidePanelShown(style, panels)
    val applyPanels: (PanelState) -> Unit = { next ->
        if (next.lyrics != lyricsOpen) viewModel.toggleLyrics()
        showQueue = next.queue
    }
    val level = rememberGravityLevel(hangsLevel)
    val displayRotation = rememberDisplayRotation()
    // Toward the floor (T63.6): in gravity mode the sheet slides away, and is dragged away, on the side the groups hang
    // toward in the window, taken while it rests open (never mid-gesture); it rises from the bottom again once closed.
    // Each detent caught while it heads open latches where a dismissal leads (T63.8).
    val slideQuarter by rememberGravitySheetSide(level, hangsLevel, sheet, displayRotation, onGravityDetent)
    var sheetSize by remember { mutableStateOf(Size.Zero) }
    val share = rememberArtworkShare(handoff)
    val movingShape = remember { RoundedCornerShape(NowPlayingSheetRadius) }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { sheetSize = it.toSize() }
            // Where it is laid out, outside its own layer: the shared artwork's flight lands there.
            .then(if (share != null) Modifier.onGloballyPositioned { share.origin = it.positionInRoot() } else Modifier)
            .graphicsLayer {
                if (bubble != null) {
                    translucentBubbleLayer(bubble) { sheetLayer(sheet, back, slideQuarter) }
                } else {
                    sheetLayer(sheet, back, slideQuarter)
                }
            }
            // The corners are read in the layer, not in composition: reading the progress here recomposed the whole screen on
            // every frame of the opening, the closing and a drag (T10).
            .graphicsLayer {
                shape = if (sheetCornered(sheet.progress.value, back.progress.value, bubbled = bubble != null)) movingShape else RectangleShape
                clip = true
            }
            .draggable(
                orientation = dismissOrientation(slideQuarter),
                state = rememberDraggableState { delta -> sheet.dragBy(dismissDrag(slideQuarter, delta, sheetSize)) },
                onDragStarted = { sheet.dragStarted() },
                onDragStopped = { velocity -> sheet.dragStopped(dismissDrag(slideQuarter, velocity, sheetSize)) },
            ),
    ) {
        val stage = rememberTurnStage(turnsBlocks)
        if (share != null) ArtworkShareTarget(share, stage) { sharesArtwork(panel != SidePanel.None, hangsLevel, deckOn) }
        // The artwork backdrop is what the Now Playing translucent buttons sample; fixed on the display, a
        // turn leaves it as is. In a bubble it fades in over the translucent surface, recorded whole for the buttons.
        val translucent = rememberTranslucentBackdrop()
        val backdropFade = if (bubble != null) Modifier.graphicsLayer { alpha = bubble.backdropAlpha(sheetSize) } else Modifier
        // Still with a deck on (its drift would re-sample the deck screen's translucent surfaces, D2.7b), while the sheet moves and while paused.
        ArtworkBackdrop(state.currentTrack?.artworkUri, backdropFade.translucentBackdropSource(translucent), drifting = backdropDrifts(deckOn, sheet.covers, state.isPlaying), driftTicks = driftTicks)
        CompositionLocalProvider(LocalTranslucentBackdrop provides translucent, LocalArtworkShare provides share) {
            NowPlayingContent(
                modifier = if (bubble != null) Modifier.graphicsLayer { alpha = bubble.contentAlpha(sheetSize) } else Modifier,
                state = state,
                volume = volume,
                viewModel = viewModel,
                stage = stage,
                level = level.takeIf { hangsLevel },
                displayRotation = displayRotation,
                panel = panel,
                bottomRow = BottomRow(
                    buttons = buttons,
                    showLyrics = panel == SidePanel.Lyrics,
                    onLyrics = { applyPanels(panels.lyricsTapped(style)) },
                    onQueue = { applyPanels(panels.queueTapped(style)) },
                    showQueue = panel == SidePanel.Queue,
                    onEqualizer = { showEqualizer = true },
                    onFileInfo = viewModel::showFileInfo,
                    menu = MoreMenuActions(
                        onAddToPlaylist = onAddToPlaylist,
                        onSleepTimer = { showSleepTimer = true },
                        abLoop = abLoop.enabled,
                        onAbLoop = viewModel::toggleAbLoop,
                        onSpeedPitch = rememberOpenSpeedPitch(),
                        djMode = djEnabled,
                        onDjMode = { djMode.setEnabled(!djEnabled) },
                        dubMode = dubEnabled,
                        onDubMode = { dubMode.setEnabled(!dubEnabled) },
                    ),
                    favourite = favourite,
                    onFavourite = viewModel::toggleFavourite,
                    sleepTimer = sleep,
                    onSleepTimer = { showSleepTimer = true },
                    djMode = djEnabled,
                    dubMode = dubEnabled,
                ),
                lyricsActions = LyricsActions(
                    panel = lyricsPanel,
                    position = { position.value },
                    onSeek = viewModel::seekTo,
                    onSearch = viewModel::searchLyrics.takeIf { viewModel.canSearchLyrics },
                    onRemoveDownloaded = viewModel::removeDownloadedLyrics,
                ),
            )
        }
    }
    if (queueAsSheet(style, panels)) {
        QueuePanel(state, viewModel) { showQueue = false }
    }
    if (showEqualizer) EqualizerPanel(onDismiss = { showEqualizer = false })
    if (showSleepTimer) SleepTimerPanel(sleep, sleepCustomMinutes, sleepTimer, onDismiss = { showSleepTimer = false })
    // Edit replaces File Info while it is open; File Info comes back afterwards, read again after a save.
    if (tagEdit != TagEditorState.Closed) {
        EditTagsSheet(tagEdit, tagEditor)
    } else if (fileInfo != FileInfoSheet.Hidden) {
        FileInfoPanel(fileInfo, state.currentTrack, onDismiss = viewModel::dismissFileInfo, onEdit = tagEditor::open)
    }
    // DJ mode has its own tempo controls; leaving the host closes a panel left open.
    if (!djEnabled) PlaybackTempoPanelHost()
    LaunchedEffect(tagEdit) {
        val saved = tagEdit as? TagEditorState.Saved ?: return@LaunchedEffect
        viewModel.reloadFileInfo(saved.track)
        tagEditor.close()
    }
    // The view model outlives this screen (the mini player shares it): a panel or sheet left open must not reappear
    // next time.
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.dismissFileInfo()
            tagEditor.close()
        }
    }
}

/**
 * Whether the sheet has its rounded corners: while it moves ([progress] short of open, or shrinking with the back gesture
 * by [backProgress]), except as a [bubbled] Now Playing, which draws the bubble's own.
 */
internal fun sheetCornered(progress: Float, backProgress: Float, bubbled: Boolean): Boolean =
    !bubbled && (progress < 1f || backProgress > 0f)

/**
 * The sheet's place at its open progress and in the back gesture's frame: it slides from the side of [slideQuarter],
 * shrinking a little as it goes.
 */
private fun GraphicsLayerScope.sheetLayer(sheet: SheetPresentation, back: SheetBackMotion, slideQuarter: Int) {
    val present = sheetPresentFrame(sheet.progress.value)
    val frame = back.frame
    val slide = sheetSlide(slideQuarter, present.offsetFraction + frame.offsetFraction, size)
    translationX = slide.x
    translationY = slide.y
    scaleX = present.scale * frame.scale
    scaleY = present.scale * frame.scale
}

/** The optional buttons and actions of the row under the volume slider. */
internal class BottomRow(
    val buttons: NowPlayingButtons,
    val showLyrics: Boolean,
    val onLyrics: () -> Unit,
    val onQueue: () -> Unit,
    val onEqualizer: () -> Unit,
    val onFileInfo: () -> Unit,
    /** The "…" menu's actions, in the header beside the title. */
    val menu: MoreMenuActions,
    /** The song playing is a favourite; its star sits in the header, before the "…" button. */
    val favourite: Boolean,
    val onFavourite: () -> Unit,
    /** The running sleep timer, whose countdown badge sits in the header too; null while none is set. */
    val sleepTimer: SleepTimerState?,
    /** Opens the Sleep Timer panel (from the "…" menu and the badge). */
    val onSleepTimer: () -> Unit,
    /** DJ mode is on: the header shows its chip instead of the tempo badge and the row drops lyrics. */
    val djMode: Boolean = false,
    /** DUB mode is on: Now Playing shows the DUB layout (exclusive with DJ mode's). */
    val dubMode: Boolean = false,
    /** Up Next is showing in the artwork's place (a tablet, see [SidePanel]); its button then closes it. */
    val showQueue: Boolean = false,
)

/**
 * Portrait: artwork above the controls. Landscape (the window is wider than tall): artwork on the left half, the
 * controls on the right half, as iOS Music does. The decision is [nowPlayingLayout]; both arrangements reuse the
 * same pieces, and everything worth keeping across a rotation lives in the view model or in saved state above. While
 * turns are enabled the [stage] keeps both composed (each pads by the safe insets of its own orientation); the artwork
 * (or lyrics) pane and the controls are the two blocks that move, the drag handle cross-fades. With a gravity [level]
 * the portrait arrangement hangs its groups level instead ([GravityPortraitContent]), in the phone's own portrait frame
 * whichever way the window is turned ([GravityFrameLayout], at [displayRotation]). In DJ mode the portrait arrangement
 * is [DjPortraitContent] and the landscape one [DjLandscapeContent] (D2.10); turning DJ mode on or off swaps them over 300 ms ([DjSwitch]).
 */
@Composable
private fun NowPlayingContent(
    state: PlaybackState,
    volume: Float,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    level: GravityLevelState?,
    displayRotation: IntState,
    bottomRow: BottomRow,
    lyricsActions: LyricsActions,
    panel: SidePanel,
    modifier: Modifier = Modifier,
) {
    val windowClass = LocalWindowClass.current
    // The DJ on/off swap (D2.10): the gravity frame or the staged arrangements are the outer shell (gravity mode hangs
    // the groups level only outside DJ mode), and each staged arrangement swaps its standard layout for the DJ one.
    val deck = deckLayout(bottomRow.djMode, bottomRow.dubMode)
    DjSwitch(target = ContentShell(level, deck), key = { it.level != null }, modifier = modifier.fillMaxSize()) { shell, shellLeaving ->
        if (shell.level != null) {
            GravityFrameLayout(displayRotation, Modifier.fillMaxSize()) {
                GravityPortraitContent(state, volume, viewModel, shell.level, bottomRow, lyricsActions)
            }
        } else {
            TurnStageLayout(stage, Modifier.fillMaxSize()) { layout ->
                DjSwitch(target = shell.deck, key = { it }) { shown, leaving ->
                    // A tablet's standard layouts (T5); a window that is not large, or a deck layout, keeps the phone's.
                    val large = nowPlayingStyle(windowClass, shown.deckOn) == NowPlayingStyle.Large
                    val djMode = shown == DeckLayout.DJ
                    // DUB mode's own layout (S2), in both orientations; it arranges itself by the room it gets.
                    if (shown == DeckLayout.DUB) {
                        DubContent(state, viewModel, stage, layout, bottomRow, frozen = leaving || shellLeaving)
                    } else when (layout) {
                        NowPlayingLayout.Portrait -> if (showsDjLayout(djMode)) {
                            DjPortraitContent(state, volume, viewModel, stage, bottomRow, frozen = leaving || shellLeaving)
                        } else if (large) {
                            LargePortraitContent(state, volume, viewModel, stage, bottomRow, lyricsActions, panel)
                        } else {
                            PortraitContent(state, volume, viewModel, stage, bottomRow, lyricsActions)
                        }
                        NowPlayingLayout.Landscape -> if (showsDjLayout(djMode)) {
                            DjLandscapeContent(state, volume, viewModel, stage, bottomRow, frozen = leaving || shellLeaving)
                        } else if (large) {
                            LargeLandscapeContent(state, volume, viewModel, stage, bottomRow, lyricsActions, panel)
                        } else {
                            LandscapeContent(state, volume, viewModel, stage, bottomRow, lyricsActions)
                        }
                    }
                }
            }
        }
    }
}

/** What [NowPlayingContent]'s outer switch is composed with: gravity mode's [level] (null outside it) and the deck layout. */
private data class ContentShell(val level: GravityLevelState?, val deck: DeckLayout)

/** The portrait arrangement, within the safe insets ([TurnStageLayout] pads it). */
@Composable
private fun PortraitContent(
    state: PlaybackState,
    volume: Float,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    bottomRow: BottomRow,
    lyricsActions: LyricsActions,
) {
    val portrait = NowPlayingLayout.Portrait
    Column(Modifier.fillMaxSize()) {
        Grabber(Modifier.align(Alignment.CenterHorizontally).turningBlock(stage, portrait, NowPlayingBlock.Handle))
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.weight(1f).fillMaxWidth().widthIn(max = 560.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).turningBlock(stage, portrait, NowPlayingBlock.Artwork, opaque = !bottomRow.showLyrics)) {
                    ArtworkOrLyrics(state, bottomRow.showLyrics, lyricsActions)
                }
            }
            Controls(state, volume, viewModel, bottomRow, Modifier.turningBlock(stage, portrait, NowPlayingBlock.Controls))
        }
    }
}

/**
 * The portrait arrangement in gravity mode, laid out as [PortraitContent] but hanging level as the phone tilts
 * ([hangsLevel]): the artwork (or lyrics) turns about its own centre within the room between the top handle and the
 * controls, fitted for its square alone, or at the diagonals shrinking no further than 0.85 over the handle row and the
 * controls as far as the phone's frame allows (T63.14); the controls turn about their own centre as one rigid block at full size
 * (T63.15), which may overlap the artwork or leave the window at the diagonals; the backdrop behind them stays still.
 * The drag handle does not turn (T63.6): the one at the top of the phone fades out as
 * it tilts away from upright, and for each other quarter turn a handle drawn upright for it fades in just beyond the
 * artwork's gravity-up edge ([handleLayer]).
 */
@Composable
private fun GravityPortraitContent(
    state: PlaybackState,
    volume: Float,
    viewModel: NowPlayingViewModel,
    level: GravityLevelState,
    bottomRow: BottomRow,
    lyricsActions: LyricsActions,
) {
    val artwork = remember { LevelGroup() }
    val bottom = remember { LevelGroup() }
    val angle = level::angle
    val handleOffset = with(LocalDensity.current) { GravityHandleOffset.toPx() }
    // The artwork may overlap the handle row and the controls as it turns, down to 0.85, never leaving the frame (T63.14).
    Column(Modifier.fillMaxSize().levelFrame(artwork)) {
        Grabber(Modifier.align(Alignment.CenterHorizontally).graphicsLayer { alpha = handleAlpha(0, angle()) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.matchParentSize().hangsLevel(artwork, angle), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.padding(horizontal = 28.dp).widthIn(max = 560.dp).fillMaxWidth().aspectRatio(1f).levelPart(artwork, 0),
                ) {
                    ArtworkOrLyrics(state, bottomRow.showLyrics, lyricsActions)
                }
            }
            for (quarter in 1..3) {
                Grabber(
                    Modifier.graphicsLayer {
                        val placed = artwork.content
                        val handle = handleLayer(quarter, placed, Rect(Offset.Zero, artwork.areaSize), angle(), handleOffset)
                        translationX = handle.center.x - size.width / 2f
                        translationY = handle.center.y - size.height / 2f
                        rotationZ = handle.rotation
                        alpha = if (placed.isEmpty) 0f else handle.alpha
                    },
                )
            }
        }
        // The controls turn as one block (T63.15, the user's call): no button moves against the others and nothing
        // shrinks, even where the turned block overlaps the artwork or the window's edges.
        Box(Modifier.fillMaxWidth().hangsLevel(bottom, angle, rigid = true), contentAlignment = Alignment.TopCenter) {
            Controls(state, volume, viewModel, bottomRow, Modifier.padding(horizontal = 28.dp).padding(bottom = 12.dp).levelPart(bottom, 0))
        }
    }
}

/** From the artwork's gravity-up edge to the centre of gravity mode's handle beside it. */
private val GravityHandleOffset = 12.dp

/**
 * The corners of a moving Now Playing sheet (square at rest); a bubble being dismissed starts from them too (T67.6, see
 * [TranslucentBubble]).
 */
val NowPlayingSheetRadius = 28.dp

/**
 * The iOS landscape arrangement, within the safe insets, its left and right padded alike ([arrangementPadding]: both
 * halves stay centred on the screen; [TurnStageLayout] pads it).
 */
@Composable
private fun LandscapeContent(
    state: PlaybackState,
    volume: Float,
    viewModel: NowPlayingViewModel,
    stage: TurnStage,
    bottomRow: BottomRow,
    lyricsActions: LyricsActions,
) {
    val landscape = NowPlayingLayout.Landscape
    val track = state.currentTrack
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Grabber(Modifier.align(Alignment.CenterHorizontally).turningBlock(stage, landscape, NowPlayingBlock.Handle))
        Row(
            Modifier.fillMaxSize().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                // The artwork is the largest square that fits; the lyrics take the whole half.
                val pane = if (bottomRow.showLyrics) Modifier.fillMaxSize() else Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true)
                Box(pane.turningBlock(stage, landscape, NowPlayingBlock.Artwork, opaque = !bottomRow.showLyrics)) {
                    ArtworkOrLyrics(state, bottomRow.showLyrics, lyricsActions)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .widthIn(max = 560.dp)
                    .turningBlock(stage, landscape, NowPlayingBlock.Controls)
                    // Below the window's top bar (T71): only the controls move down, inside their block; the artwork stays.
                    .clearOfTopBar {
                        (stage.topRowClearance(landscape) - (GrabberRowHeight + TopRowLift).roundToPx()).coerceAtLeast(0)
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                // The whole block at LandscapeControlsScale (T71c): a smaller density lays every size, text and gap out
                // smaller (crisp text, touch targets follow), and the column spans the same share of the block, so the
                // controls also sit closer together.
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density * LandscapeControlsScale, density.fontScale),
                ) {
                    Column(
                        Modifier.fillMaxWidth(LandscapeControlsScale).fillMaxHeight(LandscapeControlsScale),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TrackHeader(
                            track,
                            bottomRow.menu,
                            titleSize = 19.sp,
                            artistSize = 17.sp,
                            titleWeight = FontWeight.SemiBold,
                            star = { HeaderAccessories(track, bottomRow) },
                        )
                        PlayerScrubber(state.durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0L, viewModel)
                        CompactTransportRow(
                            isPlaying = state.isPlaying,
                            onPrevious = viewModel::previous,
                            onToggle = viewModel::togglePlayPause,
                            onNext = viewModel::next,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        VolumeSlider(volume, viewModel::setVolume)
                        BottomButtons(bottomRow, LandscapeControlsScale)
                    }
                }
            }
        }
    }
}

/** The drag handle at the top of the sheet, with a still glossy finish (T63.7). */
@Composable
internal fun Grabber(modifier: Modifier = Modifier, compact: Boolean = false) {
    GlossyGrabber(
        modifier.padding(top = if (compact) 6.dp else 8.dp, bottom = if (compact) 4.dp else 8.dp).size(width = 40.dp, height = GrabberThickness),
    )
}

private val GrabberThickness = 5.dp

/** The height of the portrait [Grabber]'s row: 21 dp, or 15 dp [compact] (the DJ screen's budget, [DjPortraitMetrics]). */
internal fun grabberRowHeight(compact: Boolean): Dp = if (compact) 15.dp else 21.dp

/** The height of the [Grabber]'s row, above the landscape controls. */
internal val GrabberRowHeight = 21.dp

/** The landscape controls block's size relative to its column (T71c): every size, text and gap at 90%. */
private const val LandscapeControlsScale = 0.9f

/** How much higher than the grabber row's clearance the landscape top row sits under the window's top bar (T71b). */
internal val TopRowLift = 8.dp

/**
 * Moves this node's content down by [clearance] px (read in layout: a change relays out this node only), keeping the
 * node's own size and place.
 */
internal fun Modifier.clearOfTopBar(clearance: Density.() -> Int): Modifier = layout { measurable, constraints ->
    val top = clearance().coerceIn(0, constraints.maxHeight)
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = (constraints.minHeight - top).coerceAtLeast(0),
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight - top else constraints.maxHeight,
        ),
    )
    layout(placeable.width, placeable.height + top) { placeable.place(0, top) }
}

/** The lyrics panel's state and what its controls do. */
internal class LyricsActions(
    val panel: LyricsPanel,
    /** The playback position (ms), read by the synced lyrics only. */
    val position: () -> Long,
    val onSeek: (Long) -> Unit,
    /** The online search; `null` in a build without one (Google Play), where the panel offers none. */
    val onSearch: (() -> Unit)?,
    val onRemoveDownloaded: () -> Unit,
)

/** Artwork that shrinks with a spring while paused, exactly like Apple Music; the lyrics panel instead when open. */
@Composable
internal fun ArtworkOrLyrics(state: PlaybackState, showLyrics: Boolean, lyrics: LyricsActions) {
    if (showLyrics) {
        LyricsView(
            panel = lyrics.panel,
            positionMs = lyrics.position,
            onSeek = lyrics.onSeek,
            onSearch = lyrics.onSearch,
            onRemoveDownloaded = lyrics.onRemoveDownloaded,
        )
        return
    }
    val scale by animateFloatAsState(
        if (state.isPlaying) 1f else 0.8f,
        spring(dampingRatio = 0.65f, stiffness = 300f),
        label = "artworkScale",
    )
    val shape = RoundedCornerShape(ArtworkCorner)
    // Opened from the Cover Flow, the artwork lands where this one is and hides it while it flies (see ArtworkShare).
    val share = LocalArtworkShare.current
    Artwork(
        model = state.currentTrack?.artworkUri,
        shape = shape,
        contentDescription = state.currentTrack?.let { stringResource(R.string.now_playing_artwork, it.album) },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                if (share != null) {
                    share.scale = scale
                    alpha = if (share.hidden()) 0f else 1f
                }
            }
            .shadow(if (state.isPlaying) 28.dp else 12.dp, shape, ambientColor = Color.Black, spotColor = Color.Black),
    )
}

/**
 * The portrait controls: title row, scrubber, transport buttons, volume slider and bottom row. On a tablet they are drawn
 * at [scale] ([ScaledDensity]) with [speakerTouchSide] touch targets for the volume's speakers.
 */
@Composable
internal fun Controls(
    state: PlaybackState,
    volume: Float,
    viewModel: NowPlayingViewModel,
    bottomRow: BottomRow,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    speakerTouchSide: Dp = 18.dp,
) {
    val track = state.currentTrack
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.height(16.dp))
        TrackHeader(
            track,
            bottomRow.menu,
            star = { HeaderAccessories(track, bottomRow) },
        )
        Spacer(Modifier.height(8.dp))
        PlayerScrubber(state.durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0L, viewModel)
        Spacer(Modifier.height(4.dp))
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
        Spacer(Modifier.height(4.dp))
        VolumeSlider(volume, viewModel::setVolume, speakerTouchSide)
        Spacer(Modifier.height(8.dp))
        BottomButtons(bottomRow, scale)
    }
}

/**
 * What sits before the header's "…" button ([headerSlots]): the DJ analysis chip in DJ mode, otherwise the speed and
 * pitch while either differs from normal; the sleep timer's countdown while one runs, and the song's star.
 */
@Composable
internal fun HeaderAccessories(track: Track?, row: BottomRow) {
    for (slot in headerSlots(row.djMode, sleepTimer = row.sleepTimer != null, track = track != null)) {
        when (slot) {
            HeaderSlot.DJ_CHIP -> DjStatusChip(Modifier.padding(end = 10.dp))
            HeaderSlot.TEMPO_BADGE -> PlaybackTempoBadge(Modifier.padding(end = 10.dp))
            HeaderSlot.SLEEP_TIMER -> row.sleepTimer?.let { timer ->
                SleepTimerBadge(timer.remainingMs, row.onSleepTimer)
                Spacer(Modifier.width(10.dp))
            }
            HeaderSlot.FAVOURITE -> {
                FavouriteButton(row.favourite, row.onFavourite)
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

/**
 * Title and artist (scrolling when too long) with the "…" button at the end, after the optional [star], and an
 * optional [leading] piece before them (DJ mode's small artwork), kept the same gap from the text as the buttons are.
 * They scroll only when [scrolls] ([headerScrolls]): by default whenever the arrangement is not kept hidden for the
 * next turn ([LocalArrangementRole]); otherwise they stand still, with only the right edge faded.
 */
@Composable
internal fun TrackHeader(
    track: Track?,
    menu: MoreMenuActions,
    titleSize: TextUnit = 21.sp,
    artistSize: TextUnit = 19.sp,
    titleWeight: FontWeight = FontWeight.Bold,
    leading: (@Composable () -> Unit)? = null,
    scrolls: Boolean = headerScrolls(hidden = LocalArrangementRole.current() == ArrangementRole.Hidden, djMode = false, playing = true),
    star: @Composable () -> Unit = {},
) {
    val scrolling = if (scrolls) MarqueeAnimationMode.Immediately else MarqueeAnimationMode.WhileFocused
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(HeaderTextGap))
        }
        // The marquee clips at the column's end: keep it the row's own 10 dp off the buttons instead of flush with them.
        Column(Modifier.weight(1f).padding(end = HeaderTextGap)) {
            FadingMarqueeText(
                track?.title ?: stringResource(R.string.now_playing_not_playing),
                color = Color.White,
                fontSize = titleSize,
                animationMode = scrolling,
                fontWeight = titleWeight,
            )
            FadingMarqueeText(
                track?.artist.orEmpty(),
                color = Secondary,
                fontSize = artistSize,
                animationMode = scrolling,
            )
        }
        star()
        MoreButton(track, menu) { open -> RoundButton(SonoraIcons.More, stringResource(R.string.now_playing_more), open) }
    }
}

/** Gap between the header's title and artist and its buttons, as between the buttons themselves (L8). */
private val HeaderTextGap = 10.dp

/**
 * The visible buttons of [bottomSlotsFor] (equalizer, lyrics, Up Next, output switcher, Cast, File Info) in one
 * translucent capsule over the artwork backdrop, each the same fixed size, spread with equal gaps: the first at the
 * capsule's left end, the last at its right. A lone button would sit in the middle. Optional buttons follow Settings
 * (and the device, for the output switcher; the build and the device, for Cast: see [LocalCastButton]), which do not
 * change while the row is on screen, so spreading only the visible ones never shuffles buttons under a finger. Each
 * button's touch target is [bottomButtonSide] for a block drawn at [scale], or [side] when the caller sets it (the DJ
 * screen's compact densities use 44 dp).
 */
@Composable
internal fun BottomButtons(row: BottomRow, scale: Float = 1f, side: Dp = bottomButtonSide(scale)) {
    val context = LocalContext.current
    val outputIntent = remember(context) { mediaOutputIntent(context) }
    val castButton = LocalCastButton.current
    val slots = bottomSlotsFor(
        row.djMode,
        lyrics = row.buttons.lyrics,
        output = outputIntent != null,
        fileInfo = row.buttons.fileInfo,
        cast = castButton != null,
    )
    val target = Modifier.size(side)
    // A bar of buttons over imagery: untinted and always dark, whatever the app theme.
    TranslucentSurface(Modifier.fillMaxWidth(), shape = CapsuleShape, elevation = 0.dp, darkTheme = true, tinted = false) {
        val label = LocalTranslucentContentColors.current.label
        val secondary = label.copy(alpha = SecondaryAlpha)
        Row(
            Modifier.fillMaxWidth().clip(CapsuleShape).padding(horizontal = BottomCapsulePadding),
            horizontalArrangement = if (slots.size == 1) Arrangement.Center else Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (slot in slots) {
                when (slot) {
                    BottomSlot.LYRICS -> GlyphButton(
                        SonoraIcons.Lyrics,
                        stringResource(if (row.showLyrics) R.string.now_playing_hide_lyrics else R.string.now_playing_show_lyrics),
                        row.onLyrics,
                        BottomGlyph,
                        target,
                        tint = if (row.showLyrics) label else secondary,
                    )
                    BottomSlot.OUTPUT -> GlyphButton(SonoraIcons.AudioOutput, stringResource(R.string.now_playing_audio_output), {
                        try {
                            outputIntent?.let(context::startActivity)
                        } catch (_: ActivityNotFoundException) {
                            // Resolved earlier but vanished; nothing sensible to do.
                        }
                    }, BottomGlyph, target, tint = secondary)
                    BottomSlot.CAST -> castButton?.invoke(secondary, target)
                    BottomSlot.UP_NEXT -> GlyphButton(
                        SonoraIcons.Queue,
                        stringResource(if (row.showQueue) R.string.now_playing_hide_up_next else R.string.now_playing_up_next),
                        row.onQueue,
                        BottomGlyph,
                        target,
                        tint = if (row.showQueue) label else secondary,
                    )
                    BottomSlot.EQUALIZER -> GlyphButton(
                        SonoraIcons.Equalizer,
                        stringResource(EqualizerR.string.equalizer_title),
                        row.onEqualizer,
                        BottomGlyph,
                        target,
                        tint = secondary,
                    )
                    BottomSlot.FILE_INFO ->
                        GlyphButton(SonoraIcons.Info, stringResource(R.string.now_playing_file_info), row.onFileInfo, BottomGlyph, target, tint = secondary)
                }
            }
        }
    }
}

private val BottomGlyph = 24.dp

/** Between the capsule's rounded ends and its first and last buttons. */
private val BottomCapsulePadding = 6.dp

/** An inactive bottom-row glyph: the translucent label, softened as [Secondary] softens white. */
private const val SecondaryAlpha = 0.6f

/** The song playing's star: filled while it is a favourite; a tap adds or removes it. */
@Composable
private fun FavouriteButton(favourite: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (favourite) R.string.now_playing_on else R.string.now_playing_off)
    RoundButton(
        if (favourite) SonoraIcons.StarFilled else SonoraIcons.Star,
        stringResource(R.string.now_playing_favourite),
        onToggle,
        Modifier.semantics { stateDescription = state },
    )
}

/**
 * A small round translucent button: translucency over the artwork backdrop when enabled, otherwise a light veil with
 * the flat tier's light, so both read as the same translucent surface.
 */
@Composable
private fun RoundButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (LocalTranslucentPolicy.current.samplesBackdrop) {
        // Always dark here, whatever the app theme.
        TranslucentSurface(shape = CircleShape, elevation = 0.dp, darkTheme = true, tinted = false, modifier = modifier) {
            GlyphButton(icon, contentDescription, onClick, 20.dp)
        }
    } else {
        GlyphButton(
            icon,
            contentDescription,
            onClick,
            20.dp,
            modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).translucentGloss(CircleShape),
        )
    }
}

/** System media output switcher (Android 11+ settings panel), or null when the device doesn't offer it. */
private fun mediaOutputIntent(context: Context): Intent? {
    val intent = Intent(MEDIA_OUTPUT_ACTION)
        .putExtra(MEDIA_OUTPUT_PACKAGE_EXTRA, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return intent.takeIf { it.resolveActivity(context.packageManager) != null }
}
