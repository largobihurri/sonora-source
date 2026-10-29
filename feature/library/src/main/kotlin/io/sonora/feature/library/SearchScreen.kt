package io.sonora.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.TranslucentBarCircle
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.ConfirmationBanner
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.LocalBottomChrome
import io.sonora.core.designsystem.component.ProvideContextMenuHost
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.component.rememberBannerState
import io.sonora.core.designsystem.translucent.translucentBackdropSource
import io.sonora.core.designsystem.translucent.rememberTranslucentBackdrop
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.designsystem.layout.ContentWidth
import io.sonora.core.designsystem.layout.contentWidth
import io.sonora.core.designsystem.layout.LocalTopChromeInset
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.model.Track
import io.sonora.feature.library.ui.CompressHost
import io.sonora.feature.library.ui.EmptyMessage
import io.sonora.feature.library.ui.TranslucentBarPage
import io.sonora.feature.library.ui.TranslucentTopBar
import io.sonora.feature.library.ui.TranslucentTopBarSpace
import io.sonora.feature.library.ui.LibraryMenuEffects
import io.sonora.feature.library.ui.LocalSelectionActions
import io.sonora.feature.library.ui.MenuHandlers
import io.sonora.feature.library.ui.MenuPlace
import io.sonora.feature.library.ui.MenuTarget
import io.sonora.feature.library.ui.SelectButton
import io.sonora.feature.library.ui.SelectionActions
import io.sonora.feature.library.ui.SelectionBar
import io.sonora.feature.library.ui.SelectionBarSpace
import io.sonora.feature.library.ui.SelectionHeader
import io.sonora.feature.library.ui.SongRow
import io.sonora.feature.library.ui.SongSelection
import io.sonora.feature.library.ui.keyedByUri
import io.sonora.feature.library.ui.rememberLibraryMenu
import io.sonora.feature.library.ui.dragSelect
import io.sonora.feature.library.ui.dragSelectRow
import io.sonora.feature.library.ui.rememberSongSelection
import io.sonora.feature.library.search.LayoutBottomPadding
import io.sonora.feature.library.search.searchCapsuleBottom
import io.sonora.feature.library.search.searchContentPadding

/**
 * Searches the library index (titles, artists, albums). "Convert…" on a song opens the Converter ([onOpenConverter]);
 * "Go to Album" and "Go to Artist" ask the Library tab to open the page ([onOpenLibrary]); "View Credits and
 * Metadata" shows [fileInfo]. The top bar's gear opens Settings ([onOpenSettings]), when given.
 */
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    onOpenConverter: () -> Unit = {},
    onOpenLibrary: ((LibraryLink) -> Unit)? = null,
    fileInfo: (@Composable (track: Track, onClose: () -> Unit) -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    viewModel: SearchViewModel = hiltViewModel(),
    // Their own instances: playlist, convert, menu and compress events must reach only this tab.
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(key = "search-playlists"),
    convertViewModel: ConvertViewModel = hiltViewModel(key = "search-convert"),
    menuViewModel: LibraryMenuViewModel = hiltViewModel(key = "search-menu"),
    compressViewModel: CompressViewModel = hiltViewModel(key = "search-compress"),
) = ProvideContextMenuHost {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    var playlistSource by remember { mutableStateOf<PlaylistSource?>(null) }
    var infoTrack by remember { mutableStateOf<Track?>(null) }
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

    val selectionActions = remember(menuViewModel, compressViewModel) {
        SelectionActions(
            canExport = menuViewModel.canExport,
            onCompress = { name, tracks ->
                compressViewModel.start(CompressTarget(ArchiveKind.SELECTION, name, PlaylistSource.Tracks(tracks)))
            },
            onAddToPlaylist = { playlistSource = PlaylistSource.Tracks(it) },
            onExport = menuViewModel::exportTracks,
            onQueue = menuViewModel::queueTracks,
        )
    }
    val recent by viewModel.recentSearches.collectAsStateWithLifecycle()
    var fieldFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // A focused field gives Back to putting the keyboard away (the system's first Back hides the keyboard only).
    BackHandler(enabled = fieldFocused) { focusManager.clearFocus() }

    // The search capsule and the banner sample the results through this backdrop.
    val translucent = rememberTranslucentBackdrop()
    CompositionLocalProvider(LocalSelectionActions provides selectionActions) {
        val selection = rememberSongSelection()
        val selecting = selection?.active == true
        // Selection takes the capsule's place at the bottom, and the keyboard goes away.
        LaunchedEffect(selecting) { if (selecting) focusManager.clearFocus() }
        Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .translucentBackdropSource(translucent)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                val menu = rememberLibraryMenu(
                    menuViewModel,
                    MenuHandlers(
                        onAddToPlaylist = { playlistSource = it },
                        onConvert = convertViewModel::convert,
                        onCompress = compressViewModel::start,
                        onOpenAlbum = onOpenLibrary?.let { open -> { id -> open(LibraryLink.Album(id)) } },
                        onOpenArtist = onOpenLibrary?.let { open -> { id, name -> open(LibraryLink.Artist(id, name)) } },
                        onShowInfo = if (fileInfo != null) { track -> infoTrack = track } else null,
                    ),
                )
                SearchResults(
                    query = query,
                    results = results,
                    selection = selection,
                    // An empty, focused field offers the recent searches instead of results.
                    recent = recent.takeIf { query.isEmpty() && fieldFocused && !selecting }.orEmpty(),
                    viewModel = viewModel,
                    onChooseRecent = { entry ->
                        viewModel.searchAgain(entry)
                        focusManager.clearFocus()
                    },
                    onMenu = { menu.open(MenuTarget.Song(it), MenuPlace.Unpinnable) },
                    onOpenSettings = onOpenSettings,
                )
            }
            if (!selecting) {
                val chrome = LocalBottomChrome.current
                val ime = WindowInsets.ime
                SearchCapsule(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    focused = fieldFocused,
                    onFocusChange = { fieldFocused = it },
                    onSearch = {
                        viewModel.onSubmit()
                        focusManager.clearFocus()
                    },
                    onCancel = {
                        viewModel.onQueryChange("")
                        focusManager.clearFocus()
                    },
                    backdrop = translucent,
                    // Floats above the mini player and tab bar, and rises with the keyboard, without recomposing.
                    modifier = Modifier.align(Alignment.BottomCenter).contentWidth(ContentWidth.LIST_DP.dp).offset {
                        val bottom = searchCapsuleBottom(chrome.contentPadding.value, ime.getBottom(this).toDp().value)
                        IntOffset(0, -bottom.dp.roundToPx())
                    },
                )
            }
            ConfirmationBanner(banner, Modifier.align(Alignment.TopCenter).padding(top = 8.dp + LocalTopChromeInset.current), backdrop = translucent)
        }
    }
    LibraryMenuEffects(menuViewModel, banner)
    CompressHost(compressViewModel, banner)
    val shownInfo = infoTrack
    if (fileInfo != null && shownInfo != null) fileInfo(shownInfo) { infoTrack = null }
    AddToPlaylistHost(playlistSource, onClose = { playlistSource = null }, banner = banner, viewModel = playlistsViewModel)
}

@Composable
private fun SearchResults(
    query: String,
    results: List<Track>,
    selection: SongSelection?,
    recent: List<String>,
    viewModel: SearchViewModel,
    onChooseRecent: (String) -> Unit,
    onMenu: (Track) -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    val title = stringResource(R.string.search_title)
    val shown = remember(results) { results.keyedByUri() }
    val selecting = selection?.active == true
    // A new search starts outside selection.
    LaunchedEffect(query) { selection?.exit() }
    val chrome = LocalBottomChrome.current
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // The results scroll under the capsule (or the selection bar) and end clear of it, keyboard up or down.
    val contentPadding = remember(selection, chrome, ime, density) {
        LayoutBottomPadding(
            bottom = {
                if (selection?.active == true) {
                    chrome.contentPadding + SelectionBarSpace
                } else {
                    searchContentPadding(chrome.contentPadding.value, ime.getBottom(this).toDp().value).dp
                }
            },
            density = density,
        )
    }
    TranslucentBarPage(
        bar = {
            val canSelect = selection != null && results.isNotEmpty()
            when {
                selecting -> SelectionHeader(selection, shown.map { it.first })
                canSelect || onOpenSettings != null -> TranslucentTopBar {
                    if (canSelect) SelectButton({ selection?.start() })
                    if (onOpenSettings != null) TranslucentBarCircle(SonoraIcons.Settings, stringResource(R.string.library_settings), onOpenSettings)
                }
            }
        },
        scrollEdge = selecting,
        overlay = {
            if (selection != null) SelectionBar(selection, shown, query.trim().ifEmpty { title }, Modifier.align(Alignment.BottomCenter).padding(bottom = BottomChromePadding))
        },
    ) {
        ContentWidthLazyColumn(Modifier.fillMaxSize().dragSelect(selection, listState, shown), state = listState, contentPadding = contentPadding, max = ContentWidth.TABLE_DP.dp) {
            item {
                TranslucentTopBarSpace()
                LargeTitle(title)
                Spacer(Modifier.height(8.dp))
                if (query.isNotBlank() && results.isEmpty()) EmptyMessage(stringResource(R.string.search_no_results, query.trim()))
            }
            if (recent.isNotEmpty()) {
                recentSearchItems(
                    recent,
                    onSearch = onChooseRecent,
                    onRemove = viewModel::removeRecentSearch,
                    onClear = viewModel::clearRecentSearches,
                )
            } else {
                itemsIndexed(results, key = { _, t -> t.id }) { index, track ->
                    SongRow(
                        track,
                        onClick = selection?.click(track.uri) { viewModel.play(results, index) } ?: { viewModel.play(results, index) },
                        onLongClick = selection?.longClick(track.uri) { onMenu(track) } ?: { onMenu(track) },
                        selected = selection?.mark(track.uri),
                        modifier = Modifier.dragSelectRow(selection, track.uri),
                    )
                }
            }
        }
    }
}

/** iOS-style rounded search field with magnifier and clear button; the keyboard's search key calls [onSearch]. */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search_placeholder),
    onSearch: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .contentWidth()
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .background(colors.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SonoraIcons.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, fontSize = 17.sp, color = colors.onSurfaceVariant)
                    }
                    inner()
                },
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                SonoraIcons.Clear,
                contentDescription = stringResource(R.string.search_clear),
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp).clickable { onValueChange("") },
            )
        }
    }
}
