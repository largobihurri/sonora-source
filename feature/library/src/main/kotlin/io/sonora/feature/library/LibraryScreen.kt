package io.sonora.feature.library

import android.content.res.Configuration
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import io.sonora.core.designsystem.component.FastScrollThumb
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.ConfirmationBanner
import io.sonora.core.designsystem.component.TranslucentBarCircle
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.PillButton
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.component.ProvideContextMenuHost
import io.sonora.core.designsystem.component.StackedArtwork
import io.sonora.core.designsystem.component.rememberBannerState
import io.sonora.core.designsystem.translucent.translucentBackdropSource
import io.sonora.core.designsystem.translucent.rememberTranslucentBackdrop
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.AdaptiveGrid
import io.sonora.core.designsystem.layout.ContentWidth
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.designsystem.layout.EdgeMarginCells
import io.sonora.core.designsystem.layout.LocalWindowClass
import io.sonora.core.designsystem.layout.contentWidth
import io.sonora.core.designsystem.motion.PredictiveBackStack
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.currentLocale
import io.sonora.core.designsystem.layout.LocalTopChromeInset
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.marks.Pinned
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.sort.SortList
import io.sonora.feature.library.ui.AlbumTile
import io.sonora.feature.library.ui.VisualCollectionButton
import io.sonora.feature.library.ui.EmptyMessage
import io.sonora.feature.library.ui.NavigationRow
import io.sonora.feature.library.ui.PinnedGlyph
import io.sonora.feature.library.ui.PlaylistDetailsDialog
import io.sonora.feature.library.ui.CompressHost
import io.sonora.feature.library.ui.LocalSelectionActions
import io.sonora.feature.library.ui.TranslucentBarPage
import io.sonora.feature.library.ui.TranslucentTopBar
import io.sonora.feature.library.ui.TranslucentTopBarHeight
import io.sonora.feature.library.ui.TranslucentTopBarSpace
import io.sonora.feature.library.ui.SelectButton
import io.sonora.feature.library.ui.SelectionActions
import io.sonora.feature.library.ui.SelectionBar
import io.sonora.feature.library.ui.SelectionBarSpace
import io.sonora.feature.library.ui.SelectionHeader
import io.sonora.feature.library.ui.keyedByUri
import io.sonora.feature.library.ui.dragSelect
import io.sonora.feature.library.ui.dragSelectRow
import io.sonora.feature.library.ui.rememberSongSelection
import io.sonora.feature.library.ui.LibraryMenuEffects
import io.sonora.feature.library.ui.MenuHandlers
import io.sonora.feature.library.ui.MenuOpener
import io.sonora.feature.library.ui.MenuPlace
import io.sonora.feature.library.ui.MenuTarget
import io.sonora.feature.library.ui.pressable
import io.sonora.feature.library.ui.rememberLibraryMenu
import io.sonora.feature.library.ui.SongRow
import io.sonora.feature.library.ui.SortButton
import io.sonora.feature.library.ui.SortControl
import io.sonora.feature.library.ui.newestFirst
import io.sonora.feature.library.ui.rememberFolderPicker

private val LibraryStackSaver = listSaver<SnapshotStateList<LibraryDest>, String>(
    save = { encodeLibraryStack(it) },
    restore = { decodeLibraryStack(it).toMutableStateList() },
)

/**
 * A library page another tab asks the Library to open ("Go to Album" in Search), or Settings, which the shell opens on
 * one of its pages (Settings › Duplicates from the duplicates notification).
 */
sealed interface LibraryLink {
    data class Album(val albumId: Long) : LibraryLink

    data class Artist(val artistId: Long, val name: String) : LibraryLink

    data object Settings : LibraryLink

    /** One of the Library's pages (a row of the large-screen sidebar), in place of whatever the Library shows. */
    data class Page(val page: LibraryPage) : LibraryLink
}

/**
 * The Library tab with its own iOS-style push/pop stack (predictive back follows the gesture). [settings]
 * renders the Settings screen (another feature) pushed from the header's gear button; it receives the pop
 * action. "Convert…" sends songs to the Converter's sources, then [onOpenConverter] shows the Converter tab.
 * [backEnabled] lets the shell keep back for an overlay (Now Playing) shown above the tab. Long-pressing an item opens
 * the iOS context menu; its "View Credits and Metadata" shows [fileInfo] (another feature) for the song. [openLink]
 * pushes a page asked for by another tab, then [onLinkOpened]. [onVisualCollectionChange] tells the shell whether the list on
 * top has a visual collection (so the phone may turn sideways); turned sideways it shows one. While [visualCollectionCovered] (an
 * overlay such as Now Playing covers the tab) the visual collection is already there underneath, so dragging the overlay away
 * reveals it, but it neither hides the system bars nor takes back; its Now Playing button calls [onOpenNowPlaying].
 * [onPageChange] reports the page on top (null at the Library's home) for the sidebar to highlight.
 *
 * On a large screen the visual collection never takes the screen over by turning sideways: it shows only while [visualCollectionOpen],
 * which a page asks for through [LocalVisualCollectionRequest] ([onVisualCollectionOpenChange] carries the change to the shell, which
 * hides its navigation meanwhile) and which the system back closes.
 * On a large screen Albums, Artists and Playlists also show a visual collection button in their bar, which asks for the
 * Visual collection through [LocalVisualCollectionRequest].
 */
@Composable
fun LibraryScreen(
    settings: @Composable (onBack: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    onOpenConverter: () -> Unit = {},
    backEnabled: Boolean = true,
    fileInfo: (@Composable (track: Track, onClose: () -> Unit) -> Unit)? = null,
    openLink: LibraryLink? = null,
    onLinkOpened: () -> Unit = {},
    visualCollectionCovered: Boolean = false,
    onVisualCollectionChange: (Boolean) -> Unit = {},
    onOpenNowPlaying: (() -> Unit)? = null,
    onPageChange: (LibraryPage?) -> Unit = {},
    visualCollectionOpen: Boolean = false,
    onVisualCollectionOpenChange: (Boolean) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
    folderViewModel: FolderViewModel = hiltViewModel(),
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(),
    convertViewModel: ConvertViewModel = hiltViewModel(),
    menuViewModel: LibraryMenuViewModel = hiltViewModel(),
    compressViewModel: CompressViewModel = hiltViewModel(),
) = ProvideContextMenuHost {

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addFailed by viewModel.addFailed.collectAsStateWithLifecycle()
    var pickerMissing by remember { mutableStateOf(false) }
    val pickFolder = rememberFolderPicker(onPicked = viewModel::addFolder)
    val stack = rememberSaveable(saver = LibraryStackSaver) { listOf<LibraryDest>(LibraryDest.Home).toMutableStateList() }
    val push: (LibraryDest) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val close: (LibraryDest) -> Unit = { closeDestination(stack, it) }

    var playlistSource by remember { mutableStateOf<PlaylistSource?>(null) }
    var infoTrack by remember { mutableStateOf<Track?>(null) }
    var creatingPlaylist by rememberSaveable { mutableStateOf(false) }
    val banner = rememberBannerState()
    val resources = LocalResources.current
    val latestOpenConverter by rememberUpdatedState(onOpenConverter)
    LaunchedEffect(convertViewModel) {
        convertViewModel.events.collect { event ->
            when (event) {
                is ConvertEvent.Sent -> latestOpenConverter()
                ConvertEvent.NothingToConvert -> banner.show(resources.getString(R.string.convert_nothing), SonoraIcons.Info)
                ConvertEvent.Failed -> banner.show(resources.getString(R.string.convert_failed), SonoraIcons.Info)
            }
        }
    }

    val menu = rememberLibraryMenu(
        menuViewModel,
        MenuHandlers(
            onAddToPlaylist = { playlistSource = it },
            onConvert = convertViewModel::convert,
            onCompress = compressViewModel::start,
            onOpenAlbum = { push(LibraryDest.AlbumDetail(it)) },
            onOpenArtist = { id, name -> push(LibraryDest.ArtistDetail(id, name)) },
            onShowInfo = if (fileInfo != null) { track -> infoTrack = track } else null,
        ),
    )
    val latestLinkOpened by rememberUpdatedState(onLinkOpened)
    LaunchedEffect(openLink) {
        if (openLink == null) return@LaunchedEffect
        openLink(stack, openLink)
        latestLinkOpened()
    }

    val top = stack.last()
    val latestVisualCollectionChange by rememberUpdatedState(onVisualCollectionChange)
    val latestVisualCollectionOpenChange by rememberUpdatedState(onVisualCollectionOpenChange)
    LaunchedEffect(top.hasVisualCollection) {
        latestVisualCollectionChange(top.hasVisualCollection)
        // A page without a visual collection cannot keep one asked for.
        if (!top.hasVisualCollection) latestVisualCollectionOpenChange(false)
    }
    val latestPageChange by rememberUpdatedState(onPageChange)
    LaunchedEffect(top.page) { latestPageChange(top.page) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val large = LocalWindowClass.current.isLarge
    val visualCollectionShown = visualCollectionOnScreen(large, landscape, requested = visualCollectionOpen)
    val visualCollectionRequest = remember(large, visualCollectionOpen) {
        if (large) VisualCollectionRequest(visualCollectionOpen, onOpen = { latestVisualCollectionOpenChange(true) }, onClose = { latestVisualCollectionOpenChange(false) }) else null
    }
    // Kept above the landscape check: upright, the visual collection is gone and its own saved state with it.
    var visualCollectionPlaces by rememberSaveable(stateSaver = VisualCollectionPlaces.Saver) { mutableStateOf(VisualCollectionPlaces()) }

    val selectionActions = remember(menuViewModel, compressViewModel) {
        SelectionActions(
            canExport = menuViewModel.canExport,
            onCompress = { name, tracks -> compressViewModel.start(CompressTarget(ArchiveKind.SELECTION, name, PlaylistSource.Tracks(tracks))) },
            onAddToPlaylist = { playlistSource = PlaylistSource.Tracks(it) },
            onExport = menuViewModel::exportTracks,
            onQueue = menuViewModel::queueTracks,
        )
    }

    // The banner's translucency samples the library while it is on screen.
    val translucent = rememberTranslucentBackdrop()
    Box(modifier) {
        CompositionLocalProvider(LocalSelectionActions provides selectionActions, LocalVisualCollectionRequest provides visualCollectionRequest) {
            PredictiveBackStack(
                stack,
                onPop = pop,
                modifier = Modifier
                    .fillMaxSize()
                    .translucentBackdropSource(translucent, enabled = banner.onScreen)
                    .background(MaterialTheme.colorScheme.background)
                    // The visual collection covers the pages on a large screen: TalkBack lists only what is on top.
                    .then(
                        if (listHiddenBehindVisualCollection(large, visualCollectionShown && top.visualCollection != null)) Modifier.clearAndSetSemantics {} else Modifier,
                    ),
                backEnabled = backEnabled,
                onPush = push,
            ) { dest ->
                // Tinted album and artist pages paint behind the status bar themselves.
                val insets = if (dest.isTinted) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars)
                Column(Modifier.fillMaxSize().then(insets)) {
                    LibraryDestination(
                        dest, state, viewModel, folderViewModel, playlistsViewModel, settings, push, pop, close,
                        onAddFolder = { pickerMissing = !pickFolder() },
                        menu = menu,
                        onNewPlaylist = { creatingPlaylist = true },
                        onConvert = convertViewModel::convert,
                        onAddToPlaylist = { playlistSource = it },
                        onCompress = compressViewModel::start,
                        onSendPlaylistToWatch = menuViewModel::sendPlaylistToWatch.takeIf { menuViewModel.canSendToWatch },
                    )
                }
            }
        }
        val visualCollection = top.visualCollection
        if (visualCollectionShown && visualCollection != null) {
            val shown = @Composable {
                CompositionLocalProvider(LocalVisualCollectionRequest provides visualCollectionRequest) {
                    LibraryVisualCollection(
                        visualCollection.list, state, viewModel, playlistsViewModel, Modifier.fillMaxSize(), onOpenNowPlaying,
                        initialTarget = visualCollection.target,
                        initialIndex = visualCollectionPlaces.of(visualCollection.list),
                        onCentreChange = { visualCollectionPlaces = visualCollectionPlaces.left(visualCollection, it) },
                    )
                }
            }
            // A page's visual collection saves nothing, so each turn opens on that page, never on a cover restored from an
            // earlier appearance (an activity recreated by a theme or language change re-saves unconsumed restored values).
            InertWhile(visualCollectionCovered) {
                if (visualCollection.target == null) shown() else CompositionLocalProvider(LocalSaveableStateRegistry provides null, shown)
            }
        }
        ConfirmationBanner(
            banner,
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp + LocalTopChromeInset.current),
            backdrop = translucent,
        )
    }

    // After the page stack's own back callback, so it takes back first: a visual collection asked for closes before a page pops.
    BackHandler(enabled = large && visualCollectionOpen && !visualCollectionCovered) { onVisualCollectionOpenChange(false) }

    LibraryMenuEffects(menuViewModel, banner)
    CompressHost(compressViewModel, banner)
    val shownInfo = infoTrack
    if (fileInfo != null && shownInfo != null) fileInfo(shownInfo) { infoTrack = null }

    AddToPlaylistHost(
        source = playlistSource,
        onClose = { playlistSource = null },
        banner = banner,
        viewModel = playlistsViewModel,
        onCreated = { push(LibraryDest.PlaylistDetail(it)) },
    )

    if (creatingPlaylist) {
        PlaylistDetailsDialog(
            title = stringResource(R.string.playlist_new_title),
            confirmLabel = stringResource(R.string.playlist_create),
            onConfirm = { name, description ->
                playlistsViewModel.create(name, description)
                creatingPlaylist = false
            },
            onDismiss = { creatingPlaylist = false },
        )
    }

    if (addFailed || pickerMissing) {
        val dismissFailure = { viewModel.dismissAddFailure(); pickerMissing = false }
        TranslucentAlert(
            title = stringResource(R.string.library_add_folder_failed_title),
            message = stringResource(
                if (pickerMissing) R.string.library_picker_missing_message else R.string.library_add_folder_failed_message,
            ),
            onDismiss = dismissFailure,
            confirm = TranslucentAlertButton(stringResource(R.string.library_ok), onClick = dismissFailure),
        )
    }
}

@Composable
private fun LibraryDestination(
    dest: LibraryDest,
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    folderViewModel: FolderViewModel,
    playlistsViewModel: PlaylistsViewModel,
    settings: @Composable (onBack: () -> Unit) -> Unit,
    push: (LibraryDest) -> Unit,
    pop: () -> Unit,
    close: (LibraryDest) -> Unit,
    onAddFolder: () -> Unit,
    menu: MenuOpener,
    onNewPlaylist: () -> Unit,
    onConvert: (PlaylistSource) -> Unit,
    onAddToPlaylist: (PlaylistSource) -> Unit,
    onCompress: (CompressTarget) -> Unit,
    onSendPlaylistToWatch: ((playlistId: Long, name: String) -> Unit)?,
) {
    val openAlbum: (Album) -> Unit = { push(LibraryDest.AlbumDetail(it.id)) }
    // The one place the visual collection button is decided: its action on a page that has the button on this window, else null.
    val visualCollectionButton: (() -> Unit)? =
        LocalVisualCollectionRequest.current?.onOpen?.takeIf { dest.hasVisualCollectionButton(LocalWindowClass.current) }
    fun albumMenu(place: MenuPlace): (Album) -> Unit = { menu.open(MenuTarget.AlbumItem(it), place) }
    fun songMenu(place: MenuPlace): (Track) -> Unit = { menu.open(MenuTarget.Song(it), place) }
    when (dest) {
        LibraryDest.Home -> LibraryHome(state, push, onAddFolder, albumMenu(MenuPlace.Unpinnable))
        LibraryDest.Settings -> settings(pop)
        LibraryDest.Songs -> SongsList(
            state.tracks, state.pinnedSongs, pop, onPlay = viewModel::play, onMenu = songMenu(MenuPlace(PinContext.SONGS)),
            sort = SortControl(SortList.SONGS, state.songsSort) { viewModel.setSort(SortList.SONGS, it) },
        )
        LibraryDest.Albums -> AlbumsGrid(
            stringResource(R.string.library_albums), state.albums, pop, openAlbum, albumMenu(MenuPlace(PinContext.ALBUMS)),
            pinned = state.pinnedAlbums,
            sort = SortControl(SortList.ALBUMS, state.albumsSort) { viewModel.setSort(SortList.ALBUMS, it) },
            onVisualCollection = visualCollectionButton,
        )
        LibraryDest.Artists -> ArtistsList(
            state.artists, state.pinnedArtists, pop, SortControl(SortList.ARTISTS, state.artistsSort) { viewModel.setSort(SortList.ARTISTS, it) },
            onMenu = { menu.open(MenuTarget.ArtistItem(it.id, it.name, it.artworkUris.firstOrNull()), MenuPlace(PinContext.ARTISTS)) },
            onVisualCollection = visualCollectionButton,
        ) { push(LibraryDest.ArtistDetail(it.id, it.name)) }
        is LibraryDest.AlbumDetail -> {
            val tracks by remember(dest.albumId) { viewModel.albumTracks(dest.albumId) }
                .collectAsStateWithLifecycle(Pinned(emptyList(), emptySet()))
            // Resolved from the library so a restored stack (ids only) shows current metadata.
            val album = state.albums.firstOrNull { it.id == dest.albumId }
            if (album != null) {
                val artistAlbums by remember(album.artistId) { viewModel.artistAlbums(album.artistId) }
                    .collectAsStateWithLifecycle(emptyList())
                val moreAlbums = remember(artistAlbums, album.id) { newestFirst(artistAlbums.filter { it.id != album.id }) }
                AlbumDetailPage(
                    album, tracks, moreAlbums, pop,
                    onMore = { menu.open(MenuTarget.AlbumItem(album), MenuPlace(albumId = album.id)) },
                    onPlay = viewModel::play,
                    onShuffle = viewModel::shuffle,
                    onAddToPlaylist = { onAddToPlaylist(PlaylistSource.Album(album.id)) },
                    onSongMenu = songMenu(MenuPlace(PinContext.album(album.id), albumId = album.id)),
                    onOpenArtist = { push(LibraryDest.ArtistDetail(album.artistId, album.artist)) },
                    onOpenAlbum = openAlbum,
                    onAlbumMenu = albumMenu(MenuPlace(albumId = album.id)),
                )
            } else {
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) { BackHeader(stringResource(R.string.library_back), pop) }
            }
        }
        is LibraryDest.ArtistDetail -> {
            val albums by remember(dest.artistId) { viewModel.artistAlbums(dest.artistId) }
                .collectAsStateWithLifecycle(emptyList())
            val tracks by remember(dest.artistId) { viewModel.artistTracks(dest.artistId) }
                .collectAsStateWithLifecycle(Pinned(emptyList(), emptySet()))
            ArtistDetailPage(
                dest.name, remember(albums) { newestFirst(albums) }, tracks, pop,
                onMore = {
                    val cover = albums.firstOrNull { it.artworkUri != null }?.artworkUri
                    menu.open(MenuTarget.ArtistItem(dest.artistId, dest.name, cover), MenuPlace(artistId = dest.artistId))
                },
                onPlay = viewModel::play,
                onShuffle = viewModel::shuffle,
                onAddToPlaylist = { onAddToPlaylist(PlaylistSource.Artist(dest.artistId)) },
                onSongMenu = songMenu(MenuPlace(PinContext.artist(dest.artistId), artistId = dest.artistId)),
                onOpenAlbum = openAlbum,
                onAlbumMenu = albumMenu(MenuPlace(artistId = dest.artistId)),
            )
        }
        LibraryDest.Folders -> FoldersScreen(folderViewModel, pop, { push(LibraryDest.Folder(it)) }, menu)
        is LibraryDest.Folder -> FolderScreen(dest.folder, folderViewModel, pop, { push(LibraryDest.Folder(it)) }, menu)
        LibraryDest.Converted -> ConvertedMusicScreen(pop, onSongMenu = songMenu(MenuPlace(PinContext.CONVERTED)))
        LibraryDest.Statistics -> StatsScreen(pop)
        LibraryDest.Playlists -> {
            val playlists by playlistsViewModel.state.collectAsStateWithLifecycle()
            PlaylistsScreen(
                playlists, pop, onOpen = { push(LibraryDest.PlaylistDetail(it)) }, onNew = onNewPlaylist,
                onMenu = { menu.open(MenuTarget.PlaylistItem(it), MenuPlace(PinContext.PLAYLISTS)) },
                onFavourites = { push(LibraryDest.Favourites) },
                onSmart = { push(LibraryDest.Smart(it)) },
                onVisualCollection = visualCollectionButton,
            )
        }
        LibraryDest.Favourites -> FavouritesScreen(
            onBack = pop,
            onOpenAlbum = openAlbum,
            onOpenPlaylist = { push(LibraryDest.PlaylistDetail(it)) },
            onSongMenu = songMenu(MenuPlace.Unpinnable),
            onAlbumMenu = albumMenu(MenuPlace.Unpinnable),
            onPlaylistMenu = { menu.open(MenuTarget.PlaylistItem(it), MenuPlace.Unpinnable) },
        )
        is LibraryDest.Smart -> SmartPlaylistScreen(dest.playlist, onBack = pop, onSongMenu = songMenu(MenuPlace.Unpinnable))
        is LibraryDest.PlaylistDetail -> PlaylistDetailScreen(
            dest.playlistId, pop, onDeleted = { close(dest) },
            onSongMenu = songMenu(MenuPlace(PinContext.playlist(dest.playlistId))),
            onConvert = { onConvert(PlaylistSource.Tracks(it)) },
            onCompress = { name, tracks -> onCompress(CompressTarget(ArchiveKind.PLAYLIST, name, PlaylistSource.Tracks(tracks))) },
            onSendToWatch = onSendPlaylistToWatch,
        )
    }
}

@Composable
private fun LibraryHome(
    state: LibraryUiState,
    push: (LibraryDest) -> Unit,
    onAddFolder: () -> Unit,
    onAlbumMenu: (Album) -> Unit,
) {
    TranslucentBarPage(bar = { LibraryBar(onSettings = { push(LibraryDest.Settings) }) }) {
        if (state.hasFolders == false) {
            Column(Modifier.fillMaxSize()) {
                LibraryHeader()
                AddFoldersPrompt(onAddFolder, Modifier.weight(1f))
            }
        } else {
            LibraryGrid(state, push, onAlbumMenu)
        }
    }
}

@Composable
private fun LibraryGrid(state: LibraryUiState, push: (LibraryDest) -> Unit, onAlbumMenu: (Album) -> Unit) {
    val grid = rememberLazyGridState()
    ScrollToTopEffect(grid)
    if (!LocalWindowClass.current.isLarge) {
        LibraryGridContent(state, push, onAlbumMenu, grid, GridCells.Fixed(AdaptiveGrid.PHONE_COLUMNS), AdaptiveGrid.PHONE_COLUMNS)
        return
    }
    // A large window fits as many columns as its width allows; the tiles keep one width (the margins sit in the edge cells).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = AdaptiveGrid.columns(maxWidth.value)
        LibraryGridContent(state, push, onAlbumMenu, grid, EdgeMarginCells(columns), columns)
    }
}

@Composable
private fun LibraryGridContent(
    state: LibraryUiState,
    push: (LibraryDest) -> Unit,
    onAlbumMenu: (Album) -> Unit,
    grid: LazyGridState,
    cells: GridCells,
    columns: Int,
) {
    LazyVerticalGrid(
        columns = cells,
        state = grid,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = BottomChromePadding),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                // On a large window the title and the shortcuts keep a list's width; the albums below use all of it.
                Column(Modifier.fillMaxWidth().contentWidth()) {
                    LibraryHeader()
                    NavigationRow(SonoraIcons.Queue, stringResource(R.string.playlists_title), { push(LibraryDest.Playlists) }, pushTo = LibraryDest.Playlists)
                    NavigationRow(SonoraIcons.MusicNote, stringResource(R.string.library_songs), { push(LibraryDest.Songs) }, pushTo = LibraryDest.Songs)
                    NavigationRow(SonoraIcons.Album, stringResource(R.string.library_albums), { push(LibraryDest.Albums) }, pushTo = LibraryDest.Albums)
                    NavigationRow(SonoraIcons.Person, stringResource(R.string.library_artists), { push(LibraryDest.Artists) }, pushTo = LibraryDest.Artists)
                    NavigationRow(SonoraIcons.Folder, stringResource(R.string.library_folders), { push(LibraryDest.Folders) }, pushTo = LibraryDest.Folders)
                    NavigationRow(SonoraIcons.Convert, stringResource(R.string.library_converted), { push(LibraryDest.Converted) }, pushTo = LibraryDest.Converted)
                    NavigationRow(
                        SonoraIcons.History, stringResource(R.string.stats_title), { push(LibraryDest.Statistics) },
                        showDivider = false, pushTo = LibraryDest.Statistics,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.library_recently_added),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                if (!state.loading && state.recentlyAdded.isEmpty()) {
                    EmptyMessage(stringResource(if (state.scanning) R.string.library_scanning else R.string.library_no_music))
                }
            }
        }
        gridItemsIndexed(state.recentlyAdded, key = { _, a -> a.id }) { index, album ->
            AlbumTile(
                album,
                onClick = { push(LibraryDest.AlbumDetail(album.id)) },
                onLongClick = { onAlbumMenu(album) },
                modifier = Modifier.padding(
                    start = if (AdaptiveGrid.atStartEdge(index, columns)) 20.dp else 0.dp,
                    end = if (AdaptiveGrid.atEndEdge(index, columns)) 20.dp else 0.dp,
                ),
            )
        }
    }
}

/** The Library's floating bar: the settings gear as a translucent circle. */
@Composable
private fun LibraryBar(onSettings: () -> Unit) {
    TranslucentTopBar(trailing = { TranslucentBarCircle(SonoraIcons.Settings, stringResource(R.string.library_settings), onSettings) })
}

/** Large "Library" title, under the floating bar ([LibraryBar]) until it scrolls. */
@Composable
private fun LibraryHeader() {
    TranslucentTopBarSpace()
    LargeTitle(stringResource(R.string.library_title))
}

/** First-run empty state: the library shows only folders the user adds. */
@Composable
private fun AddFoldersPrompt(onAddFolder: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 40.dp).padding(bottom = BottomChromePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(SonoraIcons.MusicNote, contentDescription = null, tint = colors.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.library_add_folders_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.library_add_folders_message),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PillButton(stringResource(R.string.library_add_folder), SonoraIcons.Add, onAddFolder)
    }
}

@Composable
private fun SongsList(
    tracks: List<Track>,
    pinned: Set<String>,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onMenu: (Track) -> Unit,
    sort: SortControl,
) {
    val list = rememberLazyListState()
    ScrollToTopEffect(list)
    val title = stringResource(R.string.library_songs)
    val selection = rememberSongSelection()
    val shown = remember(tracks) { tracks.keyedByUri() }
    val selecting = selection?.active == true
    TranslucentBarPage(
        bar = {
            if (selecting) {
                SelectionHeader(selection, shown.map { it.first })
            } else {
                BackBar(stringResource(R.string.library_back_library), onBack, onMore = null, sort = sort, onSelect = selection?.let { { it.start() } })
            }
        },
        scrollEdge = selecting,
        overlay = {
            if (selection != null) SelectionBar(selection, shown, title, Modifier.align(Alignment.BottomCenter).padding(bottom = BottomChromePadding))
        },
    ) {
        ContentWidthLazyColumn(
            Modifier.fillMaxSize().dragSelect(selection, list, shown),
            state = list,
            contentPadding = PaddingValues(bottom = BottomChromePadding + if (selecting) SelectionBarSpace else 0.dp),
            max = ContentWidth.TABLE_DP.dp,
        ) {
            item {
                TranslucentTopBarSpace()
                LargeTitle(title)
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                SongRow(
                    track,
                    onClick = selection?.click(track.uri) { onPlay(tracks, index) } ?: { onPlay(tracks, index) },
                    onLongClick = selection?.longClick(track.uri) { onMenu(track) } ?: { onMenu(track) },
                    pinned = track.uri in pinned,
                    selected = selection?.mark(track.uri),
                    modifier = Modifier.dragSelectRow(selection, track.uri),
                )
            }
        }
        FastScrollThumb(list, Modifier.align(Alignment.TopEnd), topPadding = FastScrollTop)
    }
}

@Composable
private fun AlbumsGrid(
    title: String,
    albums: List<Album>,
    onBack: () -> Unit,
    onOpen: (Album) -> Unit,
    onMenu: (Album) -> Unit,
    onMore: (() -> Unit)? = null,
    sort: SortControl? = null,
    pinned: Set<String> = emptySet(),
    onVisualCollection: (() -> Unit)? = null,
) {
    val grid = rememberLazyGridState()
    ScrollToTopEffect(grid)
    // A phone keeps two columns; a large window fits as many tiles of at least 170 dp as its width allows.
    val minTile = AdaptiveGrid.minTileDpFor(LocalWindowClass.current)
    TranslucentBarPage(bar = { BackBar(stringResource(R.string.library_back_library), onBack, onMore, sort, onVisualCollection = onVisualCollection) }) {
        LazyVerticalGrid(
            columns = if (minTile != null) GridCells.Adaptive(minTile.dp) else GridCells.Fixed(AdaptiveGrid.PHONE_COLUMNS),
            state = grid,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = BottomChromePadding),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(start = 0.dp)) {
                    TranslucentTopBarSpace()
                    Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
                }
            }
            items(albums, key = { it.id }) { album ->
                AlbumTile(album, onClick = { onOpen(album) }, onLongClick = { onMenu(album) }, pinned = album.id.toString() in pinned)
            }
        }
        FastScrollThumb(grid, Modifier.align(Alignment.TopEnd), topPadding = FastScrollTop)
    }
}

@Composable
private fun ArtistsList(
    artists: List<Artist>,
    pinned: Set<String>,
    onBack: () -> Unit,
    sort: SortControl,
    onMenu: (Artist) -> Unit,
    onVisualCollection: (() -> Unit)? = null,
    onOpen: (Artist) -> Unit,
) {
    val list = rememberLazyListState()
    ScrollToTopEffect(list)
    TranslucentBarPage(bar = { BackBar(stringResource(R.string.library_back_library), onBack, onMore = null, sort = sort, onVisualCollection = onVisualCollection) }) {
        ContentWidthLazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = BottomChromePadding)) {
            item {
                TranslucentTopBarSpace()
                LargeTitle(stringResource(R.string.library_artists))
            }
            items(artists, key = { "${it.id}-${it.name}" }) { artist ->
                Column(Modifier.pressable(onClick = { onOpen(artist) }, onLongClick = { onMenu(artist) })) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        // The covers of the artist's most played albums, stacked; the placeholder when none has artwork.
                        StackedArtwork(artist.artworkUris, Modifier.padding(vertical = 4.dp), width = ArtistStackWidth)
                        Spacer(Modifier.width(12.dp))
                        // The name takes all the free width (one line, like iOS) so every count lines up at the trailing edge.
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                artist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (artist.id.toString() in pinned) PinnedGlyph(Modifier.padding(start = 8.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(LocaleFormats.integer(artist.albumCount, currentLocale()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Starts under the name, after the artwork, like the other artwork rows.
                    InsetDivider(start = 20.dp + ArtistStackWidth + 12.dp)
                }
            }
        }
        FastScrollThumb(list, Modifier.align(Alignment.TopEnd), topPadding = FastScrollTop)
    }
}

/** The fast-scroll track starts below the floating bar, so the thumb never covers its Sort button. */
private val FastScrollTop = TranslucentTopBarHeight

/** Width of an artist row's stacked covers: the 48 dp front cover and the slivers behind it. */
private val ArtistStackWidth = 60.dp

/**
 * A [TranslucentBarPage]'s floating bar: the back button with, at the trailing edge, an optional visual collection button
 * ([onVisualCollection], large screens), an optional Select button ([onSelect]), an optional Sort menu ([sort]) and an optional
 * "…" menu button (Add to Playlist and the like), all translucent.
 */
@Composable
internal fun BackBar(
    label: String,
    onBack: () -> Unit,
    onMore: (() -> Unit)?,
    sort: SortControl? = null,
    onSelect: (() -> Unit)? = null,
    onVisualCollection: (() -> Unit)? = null,
) {
    TranslucentTopBar(backLabel = label, onBack = onBack) {
        if (onVisualCollection != null) VisualCollectionButton(onVisualCollection)
        if (onSelect != null) SelectButton(onSelect)
        if (sort != null) SortButton(sort)
        if (onMore != null) TranslucentBarCircle(SonoraIcons.More, stringResource(R.string.playlist_more), onMore)
    }
}

/** Album and artist pages, tinted with their artwork. */
private val LibraryDest.isTinted: Boolean
    get() = this is LibraryDest.AlbumDetail || this is LibraryDest.ArtistDetail

/**
 * Keeps [content] composed but inert while [inert]: it sees no activity (so it cannot hide the system bars through the
 * window) and registers its back callbacks with a dispatcher nothing feeds. The locals are always provided, so turning
 * inert or live again keeps the content's state.
 */
@Composable
private fun InertWhile(inert: Boolean, content: @Composable () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val idleBack = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
        }
    }
    val activity = LocalActivity.current
    val back = LocalOnBackPressedDispatcherOwner.current
    CompositionLocalProvider(
        LocalActivity provides activity.takeUnless { inert },
        LocalOnBackPressedDispatcherOwner provides (back.takeUnless { inert } ?: idleBack),
        content = content,
    )
}

