package io.sonora.player.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.feature.nowplaying.GravityDestination
import io.sonora.feature.nowplaying.NowPlayingViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import io.sonora.core.designsystem.component.ContextMenuHost
import io.sonora.core.designsystem.component.LocalBottomChrome
import io.sonora.core.designsystem.component.LocalScrollToTop
import io.sonora.core.designsystem.component.ScrollToTop
import io.sonora.core.designsystem.component.coverflow.CoverFlowHandoff
import io.sonora.core.designsystem.component.coverflow.LocalCoverFlowHandoff
import io.sonora.core.designsystem.component.coverflow.PrefetchSharedArtwork
import io.sonora.core.designsystem.component.coverflow.SharedArtworkFlight
import io.sonora.core.designsystem.translucent.TranslucentBubble
import io.sonora.core.designsystem.translucent.TranslucentBubbleSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.translucent.MorphFrame
import io.sonora.player.export.ExportShellViewModel
import io.sonora.core.designsystem.theme.ChromeTint
import io.sonora.core.designsystem.theme.LocalChromeTint
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.designsystem.translucent.translucentBackdropSource
import io.sonora.core.designsystem.translucent.rememberTranslucentBackdrop
import io.sonora.core.designsystem.layout.LocalTopChromeInset
import io.sonora.core.designsystem.layout.LocalWindowClass
import io.sonora.core.designsystem.layout.WindowClass
import io.sonora.core.designsystem.motion.LocalTabStack
import io.sonora.core.designsystem.motion.TabStack
import io.sonora.core.designsystem.motion.prefersReducedMotion
import io.sonora.core.designsystem.motion.rememberSheetPresentation
import io.sonora.core.domain.model.Track
import io.sonora.feature.cdjexport.ExportScreen
import io.sonora.feature.converter.ConverterScreen
import io.sonora.feature.library.AddToPlaylistOverlay
import io.sonora.feature.library.AlbumsCoverFlow
import io.sonora.feature.library.LibraryLink
import io.sonora.feature.library.LibraryPage
import io.sonora.feature.library.LibraryPicker
import io.sonora.feature.library.LibraryScreen
import io.sonora.feature.library.SearchScreen
import io.sonora.feature.nowplaying.NowPlayingScreen
import io.sonora.feature.nowplaying.WarmBackdropPalette
import io.sonora.feature.nowplaying.NowPlayingSheetRadius
import io.sonora.feature.nowplaying.fileinfo.LibraryFileInfo
import io.sonora.feature.settings.ArchiveImportHost
import io.sonora.feature.settings.ArchiveToImport
import io.sonora.feature.settings.IMPORT_ARCHIVE_VIEW_MODEL_KEY
import io.sonora.feature.settings.ImportArchiveViewModel
import io.sonora.feature.settings.SettingsPage
import io.sonora.feature.settings.SettingsScreen
import io.sonora.feature.settings.open
import io.sonora.player.navigation.TopLevelRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * App shell: the tab screens (one sliding strip, driven by the tab bar) with a floating translucent tab bar overlaid at the bottom.
 * [openNowPlaying] (from the media notification) opens Now Playing once a track is there, then [onNowPlayingOpened];
 * a request no track arrives for is dropped the same way (see [nowPlayingRequest]).
 * [openConversionBatch] (from a conversion notification) switches to the Converter tab showing that batch, then
 * [onConversionBatchOpened]. [openArchive] (a ZIP opened or shared with Sonora) starts its import, whose sheets show over
 * every tab, then [onArchiveOpened]. [openDuplicates] (from the duplicates notification) opens Settings › Duplicates in the
 * Library tab (back returns to Settings, then to the Library page it covered), then [onDuplicatesOpened]. "Convert…" in
 * the Library or Search switches to the Converter tab showing its sources, and
 * "Export as Rekordbox Playlist" to the Export tab while its request is pending.
 */
@Composable
fun SonoraRoot(
    modifier: Modifier = Modifier,
    openNowPlaying: Boolean = false,
    onNowPlayingOpened: () -> Unit = {},
    openConversionBatch: Long? = null,
    onConversionBatchOpened: () -> Unit = {},
    openArchive: ArchiveToImport? = null,
    onArchiveOpened: () -> Unit = {},
    openDuplicates: Boolean = false,
    onDuplicatesOpened: () -> Unit = {},
    playerViewModel: NowPlayingViewModel = hiltViewModel(),
    importViewModel: ImportArchiveViewModel = hiltViewModel(key = IMPORT_ARCHIVE_VIEW_MODEL_KEY),
    exportViewModel: ExportShellViewModel = hiltViewModel(),
    navigationViewModel: NavigationViewModel = hiltViewModel(),
) {
    // Without the position (always 0 in it): the player republishes it four times a second while playing, and the whole shell
    // would recompose with each. Nothing here shows the position.
    val playback by playerViewModel.steadyState.collectAsStateWithLifecycle()
    NotificationPermissionOnFirstPlay(isPlaying = playback.isPlaying)
    var currentTab by rememberSaveable { mutableStateOf(TopLevelRoute.Library) }
    // The four tab screens sit side by side on one strip; the tab bar and the screens share its position.
    val strip = rememberTabStripState(TopLevelRoute.entries.indexOf(currentTab), TopLevelRoute.entries.size)
    var addingToPlaylist by remember { mutableStateOf<Track?>(null) }
    // Songs sent from the Library's "Convert…" wait in the shared pending sources; the Converter shows them once.
    var showConverterSources by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val gestureLog = remember(context) { GestureLog.of(context) }

    // One open progress for Now Playing, driven by the mini player (tap, drag up), the sheet (drag down) and back.
    val sheet = rememberSheetPresentation()
    SideEffect { sheet.log = gestureLog }
    val hasTrack = playback.currentTrack != null
    val openConverter = {
        currentTab = TopLevelRoute.Converter
        showConverterSources = true
        if (sheet.targetOpen) sheet.close()
    }
    val shell = shellVisibility(
        sheetShown = sheet.shown,
        sheetCovers = sheet.covers,
        hasTrack = hasTrack,
        atTopLevel = true, // every root destination is a tab; inner stacks live inside the tab screens
    )
    LaunchedEffect(shell) { gestureLog?.invoke("shell $shell at progress ${sheet.progress.value}") }
    // A settled Now Playing turns sideways when the rotation lock allows it, the Library's Cover Flow lists whenever the
    // phone is held sideways; the rest stays upright (see screenOrientation). Saved, so an activity recreated by another
    // configuration change (theme, language) asks for the same orientation from its first frame (the Library reports
    // its list only after composing). Rotations themselves do not recreate it.
    var libraryCoverFlow by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    // Phone or tablet, upright or sideways (provided once in MainActivity).
    val windowClass = LocalWindowClass.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Where the sheet is headed, not where it is: a finger dragging Now Playing down must not turn the phone mid-gesture
    // (it would relayout under the finger with the sheet still open, and turn sideways again).
    val nowPlayingOpen = hasTrack && sheet.targetOpen
    // Dismissing a sideways Now Playing holds the app upright, except over a Cover Flow list, which shows its Cover Flow
    // (see UprightHold). wasOpen starts from the restored sheet, so a recreated activity starts no hold on its own.
    var uprightHold by rememberSaveable { mutableStateOf(false) }
    val holdWatch = remember { UprightHoldWatch(wasOpen = nowPlayingOpen) }
    val landscapeNow by rememberUpdatedState(landscape)
    val nowPlayingOpenNow by rememberUpdatedState(nowPlayingOpen)
    val coverFlowListNow by rememberUpdatedState(coverFlowList(currentTab, libraryCoverFlow))
    val largeNow by rememberUpdatedState(windowClass.isLarge)
    // Gravity mode (Settings): an open Now Playing hangs its groups level instead of turning, and its magnetic detents
    // latch where a dismissal leads (T63.8): sideways, the window turns beneath it (unseen) so the Cover Flow is there
    // to reveal; upright (or upside down), the portrait screen. Null while closed: it starts from the window's side.
    // Tablets (large windows) never use it, whatever Settings says; they also get no orientation lock (see screenOrientation).
    val gravitySetting by playerViewModel.gravity.collectAsStateWithLifecycle()
    val gravity = gravityEnabled(gravitySetting, windowClass)
    val gravityNow by rememberUpdatedState(gravity)
    var gravityDestination by rememberSaveable { mutableStateOf<GravityDestination?>(null) }
    val destination = gravityDestination ?: GravityDestination.opening(landscape)
    val destinationNow by rememberUpdatedState(destination)
    LaunchedEffect(nowPlayingOpen) {
        val gravityUpright = closesGravityUpright(gravityNow, destinationNow)
        // The upright hold is a phone rule (a large window is never locked), so a tablet starts none.
        if (holdWatch.onChange(nowPlayingOpen, landscapeNow, coverFlowListNow, gravityUpright) && !largeNow) uprightHold = true
        gravityDestination = if (nowPlayingOpen) gravityDestination ?: GravityDestination.opening(landscapeNow) else null
    }
    UprightHoldRelease(uprightHold) { uprightHold = false }
    // The window follows the destination only between gestures: never under a finger dragging Now Playing.
    val latchedSideways = gravity && nowPlayingOpen && destination == GravityDestination.Sideways
    val windowSide = remember { GravityWindowSide() }
    val windowSideways = windowSide.sideways(latchedSideways, sheet.dragging)
    val orientation = screenOrientation(nowPlayingOpen, currentTab, libraryCoverFlow, uprightHold, gravity, windowSideways, windowClass)
    // Sideways, the Library shows its fullscreen Cover Flow, already beneath a sideways Now Playing (so dragging it down
    // reveals the Cover Flow): no tab bar or mini player over it.
    // A large screen shows the Cover Flow only when a Library page asks for it (see LibraryScreen's coverFlowOpen).
    var libraryCoverFlowRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentTab) { if (currentTab != TopLevelRoute.Library) libraryCoverFlowRequested = false }
    val coverFlowMode = libraryCoverFlowMode(landscape, currentTab, libraryCoverFlow, nowPlayingOpen, windowClass, libraryCoverFlowRequested)
    // Now Playing grows out of a capsule as a translucent bubble, the capsule's artwork carried inside it to Now
    // Playing's, and closing shrinks it back (T67, see nowPlayingBubble): the Cover Flow's "Now Playing" capsule when it
    // opened from there (saved, so a recreated activity keeps it until Now Playing closes), else the mini player above
    // the tab bar. With "Remove animations" (taken when it opens or closes, as the sheet settles without motion then)
    // Now Playing cross-fades in and out at full screen instead, what lies beneath left as it is.
    var bubbleOpened by rememberSaveable { mutableStateOf(false) }
    var bubbleFades by rememberSaveable { mutableStateOf(false) }
    val reducedMotion = remember(sheet.targetOpen) { context.prefersReducedMotion() }
    val coverFlowHandoff = remember { CoverFlowHandoff().apply { progress = { sheet.progress.value } } }
    val miniPlayerHandoff = remember { CoverFlowHandoff().apply { progress = { sheet.progress.value } } }
    // As from the Cover Flow, Now Playing grows out of the mini player only when its capsule is placed as it heads open;
    // else it opens plainly. Taken only on opening: a capsule that leaves while Now Playing rests open is still met on
    // the way back (TranslucentBubble.frames).
    val miniPlayerPlaced = remember(sheet.targetOpen) { !sheet.targetOpen || miniPlayerHandoff.capsule != null }
    val bubbleKind = nowPlayingBubble(bubbleOpened, coverFlowMode, gravity).let { kind ->
        if (kind == NowPlayingBubble.MiniPlayer && !miniPlayerPlaced) NowPlayingBubble.None else kind
    }
    val fades = when (bubbleKind) {
        NowPlayingBubble.CoverFlow -> bubbleFades
        NowPlayingBubble.MiniPlayer -> reducedMotion
        NowPlayingBubble.None -> false
    }
    val bubbled = bubbleKind != NowPlayingBubble.None
    val morphs = bubbled && !fades
    val fadesNow by rememberUpdatedState(fades)
    val handoff = if (bubbleKind == NowPlayingBubble.CoverFlow) coverFlowHandoff else miniPlayerHandoff
    val handoffNow by rememberUpdatedState(handoff)
    val shellFrame = remember { ShellFrame() }
    // Dismissed, the bubble starts as a card with the sheet's own rounded corners (T67.6).
    val density by rememberUpdatedState(LocalDensity.current)
    val bubble = remember(sheet) {
        TranslucentBubble(progress = { sheet.progress.value }, fullScreen = { fadesNow }, capsule = {
            handoffNow.capsule?.let { MorphFrame(it.rect.translate(-shellFrame.origin), it.radius) }
        }, screenRadius = { with(density) { NowPlayingSheetRadius.toPx() } }, liquidLimit = if (windowClass.isLarge) TranslucentBubble.LARGE_LIQUID_LIMIT else 1f).also { bubble ->
            // The artwork is carried inside the bubble, so the two move as one (T67.4).
            val frames = { bubble.frames(shellFrame.size)?.translate(shellFrame.origin) }
            coverFlowHandoff.bubble = frames
            miniPlayerHandoff.bubble = frames
        }
    }
    // Which translucent layers of the bubble draw anything now, read as the sheet moves; the shell is not laid out yet at first, when the
    // size is empty and the hand-over alone decides.
    val liquidTranslucentShown by remember(bubble) { derivedStateOf { bubble.liquidAlpha(shellFrame.size) > 0f } }
    val flatTranslucentShown by remember(bubble) { derivedStateOf { bubble.flatAlpha(shellFrame.size) > 0f } }
    SideEffect {
        coverFlowHandoff.active = morphs && bubbleKind == NowPlayingBubble.CoverFlow
        miniPlayerHandoff.active = morphs && bubbleKind == NowPlayingBubble.MiniPlayer
    }
    // The playing artwork waits in memory at the flight's size, so a bubble's first frame already carries it; asked
    // again as a Cover Flow shows and as Now Playing heads open or closed, in case the cache let it go meanwhile.
    PrefetchSharedArtwork(playback.currentTrack?.artworkUri, refresh = coverFlowMode to sheet.targetOpen)
    // Its backdrop colours too, so the opening does not start from the fallback ones and blend to the cover's.
    WarmBackdropPalette(playback.currentTrack?.artworkUri)
    val crossFade = remember { Animatable(if (sheet.targetOpen) 1f else 0f) }
    LaunchedEffect(fades, sheet.targetOpen) {
        val target = if (sheet.targetOpen) 1f else 0f
        // The fade is the reduced motion: it keeps its own time while the system scales animations to nothing.
        if (fades) withContext(UnscaledMotion) { crossFade.animateTo(target, tween(BUBBLE_CROSS_FADE_MS)) } else crossFade.snapTo(target)
    }
    // A cross-fading Now Playing stays on screen until it has faded out (the sheet itself closed at once).
    val fadingOut = fades && crossFade.value > 0f
    LaunchedEffect(activity, orientation) { activity?.requestedOrientation = orientation }
    // Settings may cover a sideways Now Playing with the albums Cover Flow. Its Now Playing button (and the Library
    // Cover Flow's, which opens Now Playing) asks for the landscape Now Playing instead until the phone is upright
    // again or Now Playing closes.
    val rotation by playerViewModel.rotation.collectAsStateWithLifecycle()
    // Saved: another configuration change (theme, locale) while sideways must not bring the overlay back.
    var albumCoverFlowDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(landscape, shell.nowPlaying) { if (!landscape || !shell.nowPlaying) albumCoverFlowDismissed = false }
    // Gravity mode never covers its level Now Playing with the albums Cover Flow (Settings says the rotation choice does
    // not apply there); a sideways dismissal follows the landscape flow instead (T63.8).
    val albumCoverFlow = showsAlbumCoverFlow(rotation, landscape, nowPlayingOpen = shell.nowPlaying && !shell.chrome, albumCoverFlowDismissed, gravity)
    // The activity handles rotations itself (manifest configChanges): an open Now Playing turns its two blocks into the
    // new layout over a seamless window rotation; everything else keeps the system's rotation animation.
    val nowPlayingTurns = nowPlayingTurnsBlocks(nowPlayingOpen, rotation, albumCoverFlowDismissed, gravity)
    WindowRotationAnimation(windowRotationAnimation(nowPlayingRotatesSeamlessly(nowPlayingOpen, rotation, albumCoverFlowDismissed, gravity)))
    // A saved request must not outlive its track (e.g. after process death or when the queue empties).
    LaunchedEffect(hasTrack) { if (!hasTrack) sheet.closeNow() }
    // A restored queue reaches a connected player a moment later; if none does, the request goes with this launch
    // instead of opening Now Playing whenever a track first plays. A track arriving meanwhile restarts this.
    LaunchedEffect(openNowPlaying, playback.isConnected, hasTrack) {
        when (nowPlayingRequest(openNowPlaying, playback.isConnected, hasTrack)) {
            NowPlayingRequest.Open -> {
                sheet.open()
                onNowPlayingOpened()
            }
            NowPlayingRequest.Expire -> {
                delay(NOW_PLAYING_REQUEST_GRACE_MS)
                onNowPlayingOpened()
            }
            NowPlayingRequest.None, NowPlayingRequest.Wait -> Unit
        }
    }
    val exportPending by exportViewModel.pending.collectAsStateWithLifecycle()
    LaunchedEffect(exportPending != null) {
        if (exportPending != null) {
            currentTab = TopLevelRoute.Export
            if (sheet.targetOpen) sheet.close() // uncover the tab
        }
    }
    val latestArchiveOpened by rememberUpdatedState(onArchiveOpened)
    LaunchedEffect(openArchive) {
        if (openArchive != null) {
            importViewModel.open(context, openArchive)
            latestArchiveOpened()
        }
    }
    // Library and Search pages ask each other's tab for a page ("Go to Album" in Search opens it in the Library); the
    // shell asks for Settings on one of its pages (settingsPage, taken by Settings once it shows).
    var libraryLink by remember { mutableStateOf<LibraryLink?>(null) }
    var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    // Settings › Duplicates, from its notification or the first analysis' notice: inside the Library tab's Settings.
    val showDuplicates = {
        settingsPage = SettingsPage.Duplicates
        libraryLink = LibraryLink.Settings
        currentTab = TopLevelRoute.Library
        if (sheet.targetOpen) sheet.close() // uncover the tab
    }
    val latestDuplicatesOpened by rememberUpdatedState(onDuplicatesOpened)
    LaunchedEffect(openDuplicates) {
        if (openDuplicates) {
            showDuplicates()
            latestDuplicatesOpened()
        }
    }
    LaunchedEffect(openConversionBatch) {
        if (openConversionBatch != null) {
            currentTab = TopLevelRoute.Converter
            if (sheet.targetOpen) sheet.close() // uncover the tab
        }
    }

    // The floating chrome minimises while any screen scrolls toward its end (one root nested scroll connection) and
    // expands on the way back, at the top, on a tab switch, under Now Playing, and when a sheet appears; pushing or
    // popping a screen keeps its size.
    val chrome = rememberBottomChromeState()
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    SideEffect {
        chrome.navigationBar = navigationBar.value
        chrome.hasMiniPlayer = hasTrack
        chrome.floating = windowClass.isLarge
    }
    LaunchedEffect(currentTab) { chrome.expand() }
    LaunchedEffect(shell.chrome) { if (!shell.chrome) chrome.expand() } // returns expanded when Now Playing closes
    LaunchedEffect(chrome.collapsed) { gestureLog?.invoke("chrome collapsed=${chrome.collapsed}") }

    // Large screens: the translucent sidebar, or the pill at the top of the content once the user hid it (the choice is kept per
    // device). Back hides the sidebar when nothing above it takes the back; it is registered here, before any screen's
    // callback, so it ranks below all of them. A phone keeps its bottom tab bar.
    val sidebarChoice by navigationViewModel.sidebarShown.collectAsStateWithLifecycle()
    val navigation = navigationChrome(windowClass, sidebarChoice ?: true, coverFlowOnScreen = coverFlowMode != LibraryCoverFlowMode.None)
    val chromeVisible = shell.chrome && coverFlowMode == LibraryCoverFlowMode.None
    val sidebarReveal = rememberSidebarReveal(sidebarRevealTarget(navigation), known = sidebarChoice != null)
    BackHandler(enabled = backHidesSidebar(navigation, chromeVisible)) { navigationViewModel.setSidebarShown(false) }
    // The Library page on top, which the sidebar highlights.
    var libraryPage by remember { mutableStateOf<LibraryPage?>(null) }

    // The tab screens, background included, are what the floating chrome's translucency samples.
    val translucent = rememberTranslucentBackdrop()
    val chromeTint = remember { ChromeTint() }
    val fileInfo: @Composable (Track, () -> Unit) -> Unit = { track, onClose -> LibraryFileInfo(track, onClose) }
    // Tapping the selected tab again goes back to its root screen (Settings or a page pushed on it pops, as Back does),
    // or at the root brings its list back to the top, with the chrome expanded (see tabTap). Another tab keeps its stack.
    val scrollToTop = remember { TopLevelRoute.entries.associateWith { ScrollToTop() } }
    val tabStacks = remember { TopLevelRoute.entries.associateWith { TabStack() } }
    var tabSettings by rememberTabSettings()
    val selectTab: (TopLevelRoute) -> Unit = { tab ->
        val stack = tabStacks.getValue(tab)
        when (tabTap(currentTab, tab, stack.depth, stack.backEnabled)) {
            TabTap.Switch -> currentTab = tab
            TabTap.PopToRoot -> {
                chrome.expand()
                stack.popToRoot()
            }
            TabTap.ScrollToTop -> {
                chrome.expand()
                scrollToTop.getValue(tab).request()
            }
            TabTap.Held -> Unit
        }
    }
    val selectSidebar: (SidebarDestination) -> Unit = { destination ->
        when (destination) {
            is SidebarDestination.Tab -> selectTab(destination.route)
            is SidebarDestination.Page -> {
                libraryLink = LibraryLink.Page(destination.page)
                currentTab = TopLevelRoute.Library
            }
        }
    }
    // The mini player over the tab bar on a phone, floating alone on a large screen (never collapsed there).
    val miniPlayerFor: (() -> Float, () -> Boolean) -> (@Composable () -> Unit)? = { collapse, collapsed ->
        playback.currentTrack?.let { track ->
            {
                MiniPlayer(
                    track = track,
                    itemKey = playback.queue.getOrNull(playback.currentIndex)?.id,
                    isPlaying = playback.isPlaying,
                    skips = miniPlayerSkips(playback.queue.size, playback.currentIndex, playback.repeatMode),
                    sheet = sheet,
                    onTogglePlay = playerViewModel::togglePlayPause,
                    onNext = playerViewModel::next,
                    onPrevious = playerViewModel::previous,
                    collapse = collapse,
                    collapsed = collapsed(),
                    handoff = miniPlayerHandoff,
                )
            }
        }
    }
    // The long-press context menu blurs and dims the whole shell, chrome included.
    // Pages keep their first content clear of the top pill (a large screen with the sidebar hidden), whatever they are.
    val topInset = topChromeInset(navigation).dp
    CompositionLocalProvider(LocalBottomChrome provides chrome, LocalChromeTint provides chromeTint, LocalTopChromeInset provides topInset) {
        ContextMenuHost(modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { sheet.heightPx = it.height.toFloat() }
                    // The Cover Flow reports its capsule in the root; the bubble is drawn from here.
                    .onGloballyPositioned {
                        shellFrame.origin = it.positionInRoot()
                        shellFrame.size = it.size.toSize()
                    },
            ) {
                // Search, Converter and Export open Settings over themselves from their gear, each tab on its own (U4).
                val settingsOver: @Composable (TopLevelRoute, @Composable (onOpenSettings: () -> Unit) -> Unit) -> Unit = { route, screen ->
                    TabSettingsHost(
                        tab = route,
                        open = tabSettings,
                        onOpenChange = { tabSettings = it },
                        backEnabled = !(hasTrack && sheet.targetOpen),
                        screen = screen,
                    )
                }
                // Nothing of the screens is drawn while Now Playing rests open over them (see tabContentDrawn); read in the layer, so
                // it costs no recomposition.
                val tabsDrawn: GraphicsLayerScope.() -> Unit = {
                    alpha = if (tabContentDrawn(shell.nowPlaying, sheet.covers, nowPlayingOpaque = !fades || crossFade.value >= 1f)) 1f else 0f
                }
                val tabContent: @Composable (TopLevelRoute) -> Unit = { route ->
                    CompositionLocalProvider(
                        LocalScrollToTop provides scrollToTop.getValue(route),
                        LocalTabStack provides tabStacks.getValue(route),
                    ) {
                        when (route) {
                            // The open Now Playing sheet owns back; the library stack waits underneath.
                            TopLevelRoute.Library -> CompositionLocalProvider(LocalCoverFlowHandoff provides coverFlowHandoff) {
                                LibraryScreen(
                                    settings = { onBack ->
                                        SettingsScreen(onBack, openPage = settingsPage, onPageOpened = { settingsPage = null })
                                    },
                                    onOpenConverter = openConverter,
                                    backEnabled = !(hasTrack && sheet.targetOpen),
                                    fileInfo = fileInfo,
                                    openLink = libraryLink,
                                    onLinkOpened = { libraryLink = null },
                                    // Until Now Playing heads closed, its Cover Flow waits beneath without taking the bars or back.
                                    coverFlowCovered = coverFlowMode != LibraryCoverFlowMode.Shown,
                                    onCoverFlowChange = { libraryCoverFlow = it },
                                    onPageChange = { libraryPage = it },
                                    coverFlowOpen = libraryCoverFlowRequested,
                                    onCoverFlowOpenChange = { libraryCoverFlowRequested = it },
                                    onOpenNowPlaying = {
                                        if (hasTrack) {
                                            albumCoverFlowDismissed = true
                                            // From the Cover Flow on screen, Now Playing grows out of its capsule (T67).
                                            bubbleOpened = coverFlowMode == LibraryCoverFlowMode.Shown && coverFlowHandoff.capsule != null
                                            bubbleFades = context.prefersReducedMotion()
                                            sheet.open()
                                        }
                                    },
                                )
                            }
                            TopLevelRoute.Search -> settingsOver(route) { onOpenSettings ->
                                SearchScreen(
                                    onOpenConverter = openConverter,
                                    onOpenLibrary = { link ->
                                        libraryLink = link
                                        currentTab = TopLevelRoute.Library
                                    },
                                    fileInfo = fileInfo,
                                    onOpenSettings = onOpenSettings,
                                )
                            }
                            TopLevelRoute.Converter -> settingsOver(route) { onOpenSettings ->
                                ConverterScreen(
                                    libraryPicker = { onDone -> LibraryPicker(onDone) },
                                    openBatchId = openConversionBatch,
                                    onBatchOpened = onConversionBatchOpened,
                                    showSources = showConverterSources,
                                    onSourcesShown = { showConverterSources = false },
                                    backEnabled = !(hasTrack && sheet.targetOpen),
                                    onOpenSettings = onOpenSettings,
                                )
                            }
                            TopLevelRoute.Export -> settingsOver(route) { onOpenSettings -> ExportScreen(onOpenSettings = onOpenSettings) }
                        }
                    }
                }
                if (windowClass.isLarge) {
                    // The sidebar's width is left free beside the content; the sidebar samples this whole layer. The pages are
                    // laid out once at the settled inset when the choice changes, not on every frame of the sidebar's slide (T10).
                    val contentInset = settledContentInsetPx(navigation, windowClass, LocalDensity.current.density)
                    Box(Modifier.fillMaxSize().graphicsLayer(tabsDrawn).translucentBackdropSource(translucent).background(MaterialTheme.colorScheme.background)) {
                        // A tinted page's colour runs under the sidebar too, as the pages themselves start after it.
                        UnderSidebarTint(contentInset)
                        TabSlideHost(
                            tabs = TopLevelRoute.entries,
                            selected = currentTab,
                            strip = strip,
                            stateKey = { it.name },
                            modifier = Modifier.fillMaxSize().startInset(contentInset).nestedScroll(chrome),
                            content = tabContent,
                        )
                    }
                } else {
                    TabSlideHost(
                        tabs = TopLevelRoute.entries,
                        selected = currentTab,
                        strip = strip,
                        stateKey = { it.name },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(tabsDrawn)
                            .nestedScroll(chrome)
                            .translucentBackdropSource(translucent)
                            .background(MaterialTheme.colorScheme.background),
                        content = tabContent,
                    )
                }

                if (chromeVisible && navigation == NavigationChrome.BottomTabBar) {
                    CompositionLocalProvider(LocalTranslucentBackdrop provides translucent) {
                        val collapse = { chrome.progress.value }
                        MorphingChrome(
                            state = chrome,
                            tabCount = TopLevelRoute.entries.size,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            tabBar = { geometry ->
                                TranslucentTabBar(
                                    tabs = TopLevelRoute.entries,
                                    selected = currentTab,
                                    onSelect = selectTab,
                                    strip = strip,
                                    geometry = geometry,
                                    collapse = collapse,
                                    collapsed = chrome.collapsed,
                                    onExpand = chrome::expand,
                                )
                            },
                            miniPlayer = miniPlayerFor(collapse) { chrome.collapsed },
                        )
                    }
                }
                if (windowClass.isLarge) {
                    TabletNavigation(
                        navigation = navigation,
                        visible = chromeVisible,
                        reveal = sidebarReveal,
                        windowClass = windowClass,
                        selected = sidebarSelected(currentTab, libraryPage),
                        selectedTab = currentTab,
                        onSelect = selectSidebar,
                        onSelectTab = selectTab,
                        strip = strip,
                        onSidebarChange = navigationViewModel::setSidebarShown,
                        chrome = chrome,
                        miniPlayer = miniPlayerFor({ 0f }) { false },
                        translucent = translucent,
                    )
                }

                LaunchedEffect(shell.nowPlaying, fadingOut) { if (!shell.nowPlaying && !fadingOut) bubbleOpened = false }
                if (shell.nowPlaying || fadingOut) {
                    // The bubble's translucent surface, over what it samples and under Now Playing growing in it: the capsule's
                    // own, the Cover Flow's clear dark one or the mini player's tinted one. Drawn over the capsule, it
                    // cross-fades with it near the capsule (TranslucentBubble.translucentAlpha), so landing it shows the real capsule.
                    if (morphs) {
                        val miniPlayerTranslucent = bubbleKind == NowPlayingBubble.MiniPlayer
                        // Each part is composed only while it shows: a hidden translucent layer costs its whole area every frame.
                        if (flatTranslucentShown) {
                            TranslucentBubbleSurface(
                                bubble,
                                translucent,
                                Modifier.fillMaxSize(),
                                darkTheme = if (miniPlayerTranslucent) SonoraTheme.isDark else true,
                                tinted = miniPlayerTranslucent,
                                flat = true,
                            )
                        }
                        if (liquidTranslucentShown) {
                            TranslucentBubbleSurface(
                                bubble,
                                translucent,
                                Modifier.fillMaxSize(),
                                darkTheme = if (miniPlayerTranslucent) SonoraTheme.isDark else true,
                                tinted = miniPlayerTranslucent,
                            )
                        }
                    }
                    // Closed and only fading out, it is no longer there to touch (nor to TalkBack).
                    val takesTouch = nowPlayingTakesTouch(fades, sheet.targetOpen)
                    NowPlayingScreen(
                        sheet = sheet,
                        modifier = Modifier
                            .then(if (fades) Modifier.graphicsLayer { alpha = crossFade.value } else Modifier)
                            .then(if (takesTouch) Modifier else Modifier.clearAndSetSemantics {}),
                        bubble = bubble.takeIf { bubbled },
                        handoff = handoff.takeIf { morphs },
                        onAddToPlaylist = { addingToPlaylist = it },
                        turnsBlocks = nowPlayingTurns,
                        onGravityDetent = { detent ->
                            gravityDestination = GravityDestination.onDetent(gravityDestination, GravityDestination.opening(landscapeNow), detent, nowPlayingOpenNow)
                        },
                        viewModel = playerViewModel,
                    )
                    // The shared artwork, above both screens while it flies between them.
                    if (morphs) SharedArtworkFlight(handoff, playback.currentTrack?.artworkUri, Modifier.fillMaxSize())
                    if (!takesTouch) TouchShield(Modifier.fillMaxSize())
                }
                val playingAlbumId = playback.currentTrack?.albumId
                if (albumCoverFlow && playingAlbumId != null) {
                    AlbumsCoverFlow(playingAlbumId, onShowNowPlaying = { albumCoverFlowDismissed = true }, modifier = Modifier.fillMaxSize())
                }
                // Over Now Playing: the playlist picker sheet and its "Added to …" banner.
                AddToPlaylistOverlay(
                    tracks = addingToPlaylist?.let(::listOf),
                    onClose = { addingToPlaylist = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The first duplicate analysis' notice, at the top (clear of the mini player and the tab bar), under Now Playing.
            if (!shell.nowPlaying) {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalTranslucentBackdrop provides translucent) {
                        DuplicateNoticeHost(
                            onOpen = showDuplicates,
                            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                                // Under the top pill, when the sidebar is hidden.
                                .padding(top = if (navigation == NavigationChrome.TopPill && chromeVisible) TopPillHeight.dp else 0.dp),
                        )
                    }
                }
            }
            // An archive import's sheets, over every tab (Settings › Import Archive… or "Open with Sonora"); inside the
            // context menu host, so its translucent result panel floats over the blurred shell.
            ArchiveImportHost(importViewModel)
        }
    }
}

/** Takes every touch that lands on it, so nothing beneath (a Now Playing fading out) reacts. */
@Composable
private fun TouchShield(modifier: Modifier) {
    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent().changes.forEach { it.consume() }
            }
        },
    )
}

/** How long Now Playing cross-fades over its capsule with "Remove animations" on, instead of the bubble. */
private const val BUBBLE_CROSS_FADE_MS = 250

/** Animations at their own speed, whatever the system's animator duration scale. */
private object UnscaledMotion : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

/** Where the shell is laid out in the root and how big it is, read at draw time without recomposing. */
private class ShellFrame {
    var origin = Offset.Zero
    var size = Size.Zero
}

/** Asks for POST_NOTIFICATIONS (API 33+) once, the first time playback starts; denial is harmless. */
@Composable
private fun NotificationPermissionOnFirstPlay(isPlaying: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(isPlaying) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (isPlaying && !asked && !granted) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** How long a request to open Now Playing waits, once the player is connected, for a restored track to arrive. */
private const val NOW_PLAYING_REQUEST_GRACE_MS = 3_000L

/** What the shell does with a request to open Now Playing (from the media notification). */
internal enum class NowPlayingRequest {
    /** No request. */
    None,

    /** A track is there: open Now Playing and clear the request. */
    Open,

    /** The player is not connected yet: its track (or restored queue) may still come. */
    Wait,

    /** Connected with no track: clear the request unless a restored track arrives shortly. */
    Expire,
}

/** What a request to open Now Playing ([requested]) asks for with the player [connected] and [hasTrack]. */
internal fun nowPlayingRequest(requested: Boolean, connected: Boolean, hasTrack: Boolean): NowPlayingRequest = when {
    !requested -> NowPlayingRequest.None
    hasTrack -> NowPlayingRequest.Open
    !connected -> NowPlayingRequest.Wait
    else -> NowPlayingRequest.Expire
}

/**
 * Follows Now Playing opening and closing for [UprightHold], starting from [wasOpen] (the restored sheet, so a recreated
 * activity starts no hold on its own).
 */
internal class UprightHoldWatch(private var wasOpen: Boolean) {
    /** Now Playing is now [isOpen]; returns whether this change starts the hold (see [UprightHold.startsOnClose]). */
    fun onChange(isOpen: Boolean, landscape: Boolean, coverFlowList: Boolean, gravityUpright: Boolean = false): Boolean =
        UprightHold.startsOnClose(wasOpen, isOpen, landscape, coverFlowList, gravityUpright).also { wasOpen = isOpen }
}

/** Whether the Library's fullscreen Cover Flow is there, and whether it is what the user sees. */
internal enum class LibraryCoverFlowMode {
    /** No Cover Flow: upright, another tab, or a Library screen without one. */
    None,

    /** Composed beneath a sideways Now Playing (open or being dragged), so dragging it down reveals the Cover Flow. */
    Covered,

    /** On screen, fullscreen with the system bars hidden. */
    Shown,
}

/**
 * The Library's Cover Flow, sideways ([landscape]) on a Cover Flow list ([coverFlowList]): beneath Now Playing while it
 * is headed open ([nowPlayingOpen], so also during a drag), on screen once it heads closed. On a large [windowClass] it
 * is there only while [coverFlowRequested], in either orientation.
 */
internal fun libraryCoverFlowMode(
    landscape: Boolean,
    tab: TopLevelRoute,
    libraryCoverFlow: Boolean,
    nowPlayingOpen: Boolean,
    windowClass: WindowClass = WindowClass.Phone,
    coverFlowRequested: Boolean = false,
): LibraryCoverFlowMode {
    // A large screen never turns into a Cover Flow by being turned sideways: it shows one only when asked for.
    val wanted = if (windowClass.isLarge) coverFlowRequested else landscape
    return when {
        !wanted || !coverFlowList(tab, libraryCoverFlow) -> LibraryCoverFlowMode.None
        nowPlayingOpen -> LibraryCoverFlowMode.Covered
        else -> LibraryCoverFlowMode.Shown
    }
}

/** What a tap on a tab bar tab does, as on iOS. */
internal enum class TabTap {
    /** Shows the tapped tab, with whatever screens are pushed on it. */
    Switch,

    /** Takes the selected tab back to its root screen, as Back does (one pop from the screen on top). */
    PopToRoot,

    /** Brings the selected tab's visible list back to its top. */
    ScrollToTop,

    /** Leaves the selected tab as it is: Back is held above its stack (Now Playing open), so a tap pops nothing either. */
    Held,
}

/**
 * A tap on [tapped] while [selected] shows: another tab is switched to, its stack untouched; the same tab goes back to
 * its root while its stack holds more than its root screen ([tappedDepth] > 1), and at its root scrolls to the top.
 * The tap pops only what Back would: while Back does not reach the tapped stack ([tappedBackEnabled] false, the Now
 * Playing sheet owning it), its screens stay where they are.
 */
internal fun tabTap(selected: TopLevelRoute, tapped: TopLevelRoute, tappedDepth: Int, tappedBackEnabled: Boolean): TabTap =
    when {
        tapped != selected -> TabTap.Switch
        tappedDepth <= 1 -> TabTap.ScrollToTop
        tappedBackEnabled -> TabTap.PopToRoot
        else -> TabTap.Held
    }
