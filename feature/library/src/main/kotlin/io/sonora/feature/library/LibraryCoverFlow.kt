package io.sonora.feature.library

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.NowPlayingBars
import io.sonora.core.designsystem.component.coverflow.CoverFlow
import io.sonora.core.designsystem.component.coverflow.CoverFlowAction
import io.sonora.core.designsystem.component.coverflow.CoverFlowCaption
import io.sonora.core.designsystem.component.coverflow.CoverFlowItem
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.domain.library.CoverFlowArtwork
import io.sonora.core.domain.library.marks.Pinned
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playback.PlaybackBandLevels

/**
 * The albums Cover Flow on its own, opened on [playingAlbumId] (the album playing, from the caller's live player state:
 * the view model's cached button state may still name the previous song when this appears): the shell shows it over a
 * sideways Now Playing when Settings asks for it. Its Now Playing button calls [onShowNowPlaying]. Black until the
 * albums are loaded, so it never opens on the wrong cover; a library with no albums says so ([albumsCoverFlowEmptyText]).
 */
@Composable
fun AlbumsCoverFlow(
    playingAlbumId: Long,
    onShowNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (coverFlowAlbums(state).isEmpty()) {
        CoverFlowEmpty(albumsCoverFlowEmptyText(state), modifier)
        return
    }
    // Nothing is saved: each time it appears (the activity is recreated on every turn) it opens on the album playing,
    // never on a cover restored from an earlier appearance.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        LibraryCoverFlow(
            LibraryDest.Albums, state, viewModel, playlistsViewModel, modifier, onShowNowPlaying,
            initialTarget = CoverFlowTarget.Album(playingAlbumId),
        )
    }
}

/**
 * What the albums Cover Flow says while it has no albums ([coverFlowAlbums]): nothing while the library loads (it stays
 * black), else why it is empty (every album hidden for lacking artwork, no library folders, still scanning, or no music
 * found), as the Library's own lists say it.
 */
internal fun albumsCoverFlowEmptyText(state: LibraryUiState): Int? = when {
    state.loading || coverFlowAlbums(state).isNotEmpty() -> null
    state.albums.isNotEmpty() -> R.string.coverflow_no_albums_with_artwork
    state.hasFolders == false -> R.string.library_folders_empty
    state.scanning -> R.string.library_scanning
    else -> R.string.library_no_music
}

/** The albums the albums Cover Flow lists: the Library's, without those lacking artwork when Settings hides them. */
internal fun coverFlowAlbums(state: LibraryUiState): List<Album> =
    CoverFlowArtwork.albums(state.albums, state.hideAlbumsWithoutArtwork)

/**
 * These covers without the album covers of [albums] that have no artwork, when [hide]; artist and playlist covers are
 * never dropped.
 */
internal fun List<CoverFlowItem<CoverFlowTarget>>.withArtwork(albums: List<Album>, hide: Boolean): List<CoverFlowItem<CoverFlowTarget>> {
    if (!hide) return this
    val listed = CoverFlowArtwork.albums(albums, hideWithoutArtwork = true).mapTo(HashSet()) { it.id }
    return filter { val target = it.key; target !is CoverFlowTarget.Album || target.albumId in listed }
}

/**
 * A Cover Flow showing [shown], the covers of [all] that the artwork setting leaves. Its places (where it opens and
 * where it was left) are kept as indexes in [all], so turning the setting on and off never moves them to another cover:
 * a hidden cover hands the centre to its nearest listed neighbour ([CoverFlowArtwork.keptCentre]).
 */
internal class CoverFlowShown<K>(private val all: List<K>, private val shown: List<K>) {

    /** Where the place [allIndex] of [all] is among [shown]. */
    fun shownIndex(allIndex: Int): Int = if (all === shown) allIndex else CoverFlowArtwork.keptCentre(all, allIndex, shown)

    /** The place in [all] of the shown cover [shownIndex]; an index outside [shown] is left as it is. */
    fun allIndex(shownIndex: Int): Int =
        shown.getOrNull(shownIndex)?.let { key -> all.indexOf(key).takeIf { it >= 0 } } ?: shownIndex
}

/**
 * The landscape Cover Flow of the Library list [dest] (see [hasCoverFlow]), fullscreen with the system bars hidden.
 * Its covers follow the list's order; a flipped cover lists its songs, and tapping one plays from it. While a track is
 * loaded, a translucent capsule in the top-right corner (its artwork, "Now Playing", the song and artist) calls
 * [onOpenNowPlaying] (when given), and its row on a flipped cover shows the now-playing bars. It opens on the cover of
 * [initialTarget] (the first cover when not listed); with a target it stays black until the list is loaded, so it
 * never opens on the wrong cover. Without a target it opens on [initialIndex] (see [CoverFlowPlaces]) and reports its
 * centre cover to [onCentreChange] as it moves. While Settings hides albums without artwork, the album covers lacking it
 * are left out (Albums and Songs); places stay indexes in the full list ([CoverFlowShown]), and turning the setting on
 * or off keeps the centre cover, or its nearest neighbour when it is hidden. With every cover hidden it says why.
 */
@Composable
internal fun LibraryCoverFlow(
    dest: LibraryDest,
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    playlistsViewModel: PlaylistsViewModel,
    modifier: Modifier = Modifier,
    onOpenNowPlaying: (() -> Unit)? = null,
    initialTarget: CoverFlowTarget? = null,
    initialIndex: Int = 0,
    onCentreChange: (Int) -> Unit = {},
) {
    HideSystemBars()
    val resources = LocalResources.current
    val playlists by playlistsViewModel.state.collectAsStateWithLifecycle()
    val all = when (dest) {
        LibraryDest.Albums -> remember(state.albums) { CoverFlowItems.albums(state.albums) }
        LibraryDest.Songs -> remember(state.tracks, state.albums) { CoverFlowItems.songs(state.tracks, state.albums) }
        LibraryDest.Artists -> remember(state.artists, resources) {
            CoverFlowItems.artists(state.artists) { resources.getQuantityString(R.plurals.library_album_count, it, it) }
        }
        LibraryDest.Playlists -> remember(playlists.playlists, resources) {
            CoverFlowItems.playlists(playlists.playlists) { resources.getQuantityString(R.plurals.playlist_song_count, it, it) }
        }
        else -> emptyList()
    }
    if (initialTarget != null && all.isEmpty()) {
        Box(modifier.background(Color.Black))
        return
    }
    val hideArtless = state.hideAlbumsWithoutArtwork
    val items = remember(all, state.albums, hideArtless) { all.withArtwork(state.albums, hideArtless) }
    if (items.isEmpty() && all.isNotEmpty()) {
        CoverFlowEmpty(R.string.coverflow_no_albums_with_artwork, modifier)
        return
    }
    val shown = remember(all, items) { CoverFlowShown(all, items) }
    val opening = remember(all) { coverFlowOpening(all, initialTarget, initialIndex) }
    // The centre's cover and place in the full list; not state, so following it never recomposes this.
    val centre = remember { CoverFlowCentre<CoverFlowTarget>() }
    val playButton by viewModel.playButton.collectAsStateWithLifecycle()
    val play = stringResource(R.string.library_play)
    val pause = stringResource(R.string.coverflow_pause)
    val action = playButton?.let { button ->
        when (button) {
            CoverFlowPlayButton.PLAY -> CoverFlowAction(SonoraIcons.Play, play, viewModel::togglePlayPause)
            CoverFlowPlayButton.PAUSE -> CoverFlowAction(SonoraIcons.Pause, pause, viewModel::togglePlayPause)
        }
    }
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val showNowPlaying = stringResource(R.string.coverflow_now_playing)
    val nowPlayingLabel = stringResource(R.string.coverflow_now_playing_label)
    val nowPlayingAction = nowPlaying?.takeIf { onOpenNowPlaying != null }?.let { playing ->
        CoverFlowAction(
            SonoraIcons.MusicNote,
            showNowPlaying,
            { onOpenNowPlaying?.invoke() },
            artwork = playing.artworkUri,
            caption = CoverFlowCaption(nowPlayingLabel, playing.caption),
        )
    }
    // A large screen has no turn back to the list: a visible close button (the system back closes it too).
    val closeRequest = LocalCoverFlowRequest.current
    val closeLabel = stringResource(R.string.coverflow_close)
    val closeAction = closeRequest?.let { CoverFlowAction(SonoraIcons.Clear, closeLabel, it.onClose) }
    // Turning the setting on or off starts a new strip on the same cover (or its nearest listed neighbour), so the
    // Cover Flow's own index never lands on whichever cover took the old one's place.
    key(hideArtless) {
        CoverFlow(
            items,
            stringResource(R.string.coverflow_info),
            modifier,
            leadingAction = action,
            trailingTopAction = nowPlayingAction,
            closeAction = closeAction,
            initialIndex = remember(items) { shown.shownIndex(centre.place(all.map { it.key }, opening)) },
            onCentreChange = { index ->
                val place = shown.allIndex(index)
                centre.report(place, all.getOrNull(place)?.key)
                onCentreChange(place)
            },
        ) { item, close ->
            CompositionLocalProvider(LocalBandLevels provides viewModel.bandLevels) {
                when (val target = item.key) {
                    is CoverFlowTarget.Album -> AlbumBack(target.albumId, item, viewModel, nowPlaying, close)
                    is CoverFlowTarget.Playlist -> PlaylistBack(target.playlistId, item, nowPlaying, close)
                    is CoverFlowTarget.Artist -> ArtistBack(target.artistId, item, viewModel, nowPlaying, close)
                }
            }
        }
    }
}

/**
 * Where a Cover Flow's centre was last reported: its cover's key, and its place in the full list then. The key keeps the
 * centre on the same cover when the list changes (a rescan adds or removes covers); the place is the fallback once that
 * cover is gone.
 */
internal class CoverFlowCentre<K> {
    private var place: Int? = null
    private var key: K? = null

    /** The centre moved to [place] of the full list, the cover [key]. */
    fun report(place: Int, key: K?) {
        this.place = place
        this.key = key
    }

    /** The centre's place in [all]: where its cover is now, else where it was, or [opening] before it reports one. */
    fun place(all: List<K>, opening: Int): Int = key?.let { all.indexOf(it).takeIf { index -> index >= 0 } } ?: place ?: opening
}

/** The cover a Cover Flow opens on: [target]'s when it has one (a page's, first cover when not listed), else [listPlace]. */
internal fun coverFlowOpening(items: List<CoverFlowItem<CoverFlowTarget>>, target: CoverFlowTarget?, listPlace: Int): Int =
    if (target != null) CoverFlowItems.indexOf(items, target) else listPlace

/** A Cover Flow with no covers: black, saying [text] when given. */
@Composable
private fun CoverFlowEmpty(text: Int?, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        text?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyLarge,
                color = BackSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}

/**
 * Where each Library list's Cover Flow (Songs, Albums, Artists, Playlists) was left: its centre cover, so turning the
 * phone sideways again opens it there. The activity handles rotation itself (no recreation on a turn), so a Cover Flow
 * leaves composition while the phone is upright and its own saved state goes with it; the Library keeps these places
 * above its landscape check instead. A page's Cover Flow ([LibraryCoverFlowView.target]) always opens centred on that
 * page and records nothing. A flipped cover is not kept: each turn opens with every cover face up. Places are indexes in
 * the list's current order, as the Cover Flow's own saved index was.
 */
@Immutable
internal data class CoverFlowPlaces(private val byList: Map<LibraryDest, Int> = emptyMap()) {

    /** Where [list]'s Cover Flow was left: the first cover until it has been shown. */
    fun of(list: LibraryDest): Int = byList[list] ?: 0

    /** The cover [view]'s Cover Flow opens on, among [items] (see [coverFlowOpening]). */
    fun opening(view: LibraryCoverFlowView, items: List<CoverFlowItem<CoverFlowTarget>>): Int =
        coverFlowOpening(items, view.target, of(view.list))

    /** These places once [view]'s Cover Flow has [index] in the centre; a page's leaves them as they are. */
    fun left(view: LibraryCoverFlowView, index: Int): CoverFlowPlaces =
        if (view.target != null || byList[view.list] == index) this else CoverFlowPlaces(byList + (view.list to index))

    /** One "list=index" entry per list, the list as the Library's saved stack names it. */
    fun encode(): List<String> = byList.map { (list, index) -> "${encodeLibraryStack(listOf(list)).single()}=$index" }

    companion object {
        /** The places [encode] wrote; an entry it cannot read is dropped. */
        fun decode(encoded: List<String>): CoverFlowPlaces = CoverFlowPlaces(
            encoded.mapNotNull { entry ->
                val list = decodeLibraryStack(listOf(entry.substringBefore('=', ""))).drop(1).singleOrNull()
                val index = entry.substringAfter('=', "").toIntOrNull()
                if (list != null && index != null) list to index else null
            }.toMap(),
        )

        val Saver: Saver<CoverFlowPlaces, Any> = listSaver(save = { it.encode() }, restore = { decode(it) })
    }
}

/**
 * An album's back: its songs in album order (pinned first, as on its page); a tapped song plays the album from it. The
 * song [nowPlaying] shows the now-playing bars instead of its number, here as on the other backs.
 */
@Composable
private fun AlbumBack(
    albumId: Long,
    item: CoverFlowItem<CoverFlowTarget>,
    viewModel: LibraryViewModel,
    nowPlaying: CoverFlowNowPlaying?,
    close: () -> Unit,
) {
    val pinned by remember(albumId) { viewModel.albumTracks(albumId) }.collectAsStateWithLifecycle(Pinned(emptyList(), emptySet()))
    val tracks = pinned.items
    BackPanel(item.title, item.subtitle, close) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            BackSongRow(track.trackNumber ?: (index + 1), track, nowPlaying, onClick = { viewModel.play(tracks, index) })
        }
    }
}

/**
 * An artist's back: their albums newest first, scrolling vertically, each with its cover, title and songs; a tapped
 * song plays its album from it.
 */
@Composable
private fun ArtistBack(
    artistId: Long,
    item: CoverFlowItem<CoverFlowTarget>,
    viewModel: LibraryViewModel,
    nowPlaying: CoverFlowNowPlaying?,
    close: () -> Unit,
) {
    val albums by remember(artistId) { viewModel.artistAlbums(artistId) }.collectAsStateWithLifecycle(emptyList())
    val sections = remember(albums) { CoverFlowItems.artistBackAlbums(albums) }
    BackPanel(item.title, item.subtitle, close) {
        items(sections, key = { it.id }) { album -> ArtistAlbumSection(album, viewModel, nowPlaying) }
    }
}

@Composable
private fun ArtistAlbumSection(album: Album, viewModel: LibraryViewModel, nowPlaying: CoverFlowNowPlaying?) {
    val pinned by remember(album.id) { viewModel.albumTracks(album.id) }.collectAsStateWithLifecycle(Pinned(emptyList(), emptySet()))
    val tracks = pinned.items
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(album.artworkUri, Modifier.size(48.dp), RoundedCornerShape(4.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(album.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                album.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = BackSecondary) }
            }
        }
        tracks.forEachIndexed { index, track ->
            BackSongRow(track.trackNumber ?: (index + 1), track, nowPlaying, onClick = { viewModel.play(tracks, index) })
        }
    }
}

/**
 * A playlist's back: its songs as its page lists them; a tapped song plays the playlist from it. Its view model lives in
 * a store of the back's own, cleared when the cover flips back (or the Cover Flow goes), so flipping one playlist after
 * another keeps none of them.
 */
@Composable
private fun PlaylistBack(
    playlistId: Long,
    item: CoverFlowItem<CoverFlowTarget>,
    nowPlaying: CoverFlowNowPlaying?,
    close: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel<PlaylistDetailViewModel, PlaylistDetailViewModel.Factory>(
        rememberViewModelStoreOwner(),
        key = "playlist:$playlistId",
    ) { it.create(playlistId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackPanel(item.title, item.subtitle, close) {
        itemsIndexed(state.items, key = { _, entry -> entry.entry.id }) { index, entry ->
            val track = entry.track
            if (track != null) {
                BackSongRow(index + 1, track, nowPlaying, onClick = { viewModel.play(entry.entry.id) })
            } else {
                BackRow(index + 1, CoverFlowRowState.NUMBER, entry.entry.title, duration = "", enabled = false, onClick = {})
            }
        }
    }
}

/** The dark list on a flipped cover: a header (tapping it flips the cover back) above [content]. */
@Composable
internal fun BackPanel(title: String, subtitle: String, close: () -> Unit, content: LazyListScope.() -> Unit) {
    val showCover = stringResource(R.string.coverflow_show_cover)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = showCover, onClick = close)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = BackSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Icon(SonoraIcons.Album, contentDescription = showCover, tint = BackSecondary)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BackDivider))
        LazyColumn(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
internal fun BackSongRow(number: Int, track: Track, nowPlaying: CoverFlowNowPlaying?, onClick: () -> Unit) = BackRow(
    number,
    coverFlowRowState(track.id, nowPlaying),
    track.title,
    LocaleFormats.duration(track.durationMs),
    enabled = true,
    onClick = onClick,
)

/**
 * A song row: its [number], or the now-playing bars when its [state] says it is the loaded song. Playing, the bars
 * follow the music's bass, kick, mids and highs ([LocalBandLevels]), warming to orange and red as they get loud.
 */
@Composable
private fun BackRow(number: Int, state: CoverFlowRowState, title: String, duration: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            when (state) {
                CoverFlowRowState.NUMBER ->
                    Text(number.toString(), style = MaterialTheme.typography.bodyMedium, color = BackSecondary)
                CoverFlowRowState.PLAYING, CoverFlowRowState.PAUSED -> NowPlayingBars(
                    animating = state == CoverFlowRowState.PLAYING,
                    color = SonoraTheme.accentOnDark,
                    contentDescription = stringResource(R.string.coverflow_now_playing_label),
                    levels = LocalBandLevels.current,
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(duration, style = MaterialTheme.typography.bodyMedium, color = BackSecondary)
    }
}

/** Hides the status and navigation bars while shown (a swipe from an edge brings them back for a moment). */
@Composable
private fun HideSystemBars() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** The live band levels for the flipped covers' playing row; none outside the Cover Flow. */
private val LocalBandLevels = staticCompositionLocalOf<PlaybackBandLevels?> { null }

private val BackSecondary = Color.White.copy(alpha = 0.6f)
private val BackDivider = Color.White.copy(alpha = 0.12f)
