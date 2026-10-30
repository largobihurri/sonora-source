package io.sonora.feature.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.marks.Pinned
import io.sonora.core.domain.model.AddFolderResult
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.QueueAction
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playback.PlaybackBandLevels
import io.sonora.core.domain.port.VisualCollectionPreferencesRepository
import io.sonora.core.domain.port.LibraryFolderRepository
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.LibraryScanner
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.sort.LibrarySort
import io.sonora.core.domain.sort.ListSorting
import io.sonora.core.domain.sort.SortList
import io.sonora.core.domain.sort.SortOrder
import io.sonora.core.domain.sort.SortTarget
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loading: Boolean = true,
    /** `null` until the folder list is known; `false` shows the add-folders onboarding. */
    val hasFolders: Boolean? = null,
    val scanning: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val recentlyAdded: List<Album> = emptyList(),
    val songsSort: SortOrder = SortList.SONGS.default,
    val albumsSort: SortOrder = SortList.ALBUMS.default,
    val artistsSort: SortOrder = SortList.ARTISTS.default,
    /** Keys pinned to the top of Songs (song URIs), Albums and Artists (ids). */
    val pinnedSongs: Set<String> = emptySet(),
    val pinnedAlbums: Set<String> = emptySet(),
    val pinnedArtists: Set<String> = emptySet(),
    /** Settings hides albums without artwork from visual collection (see [visualCollectionAlbums]); the Albums list lists them all. */
    val hideAlbumsWithoutArtwork: Boolean = false,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val folders: LibraryFolderRepository,
    scanner: LibraryScanner,
    private val playback: PlaybackController,
    private val sorting: ListSorting,
    private val marks: LibraryMarksRepository,
    /** The visual collection's own settings: whether it hides albums without artwork. */
    visualCollection: VisualCollectionPreferencesRepository,
    /** The live band levels the visual collection's now-playing bars follow. */
    val bandLevels: PlaybackBandLevels,
) : ViewModel() {

    // Songs, Albums and Artists each follow their own saved sort; sorting runs off the main thread.
    private val content = combine(
        sorting.sorted(repository.tracks(), SortTarget(SortList.SONGS), LibrarySort::tracks)
            .pinnedBy(marks.pins(PinContext.SONGS)) { it.uri },
        sorting.sorted(repository.albums(), SortTarget(SortList.ALBUMS), LibrarySort::albums)
            .pinnedBy(marks.pins(PinContext.ALBUMS)) { it.id.toString() },
        sorting.sorted(repository.artists(), SortTarget(SortList.ARTISTS), LibrarySort::artists)
            .pinnedBy(marks.pins(PinContext.ARTISTS)) { it.id.toString() },
        repository.recentlyAddedAlbums(RECENT_LIMIT),
    ) { tracks, albums, artists, recent ->
        LibraryUiState(
            loading = false,
            tracks = tracks.items.items,
            albums = albums.items.items,
            artists = artists.items.items,
            recentlyAdded = recent,
            songsSort = tracks.order,
            albumsSort = albums.order,
            artistsSort = artists.order,
            pinnedSongs = tracks.items.pinnedKeys,
            pinnedAlbums = albums.items.pinnedKeys,
            pinnedArtists = artists.items.pinnedKeys,
        )
    }

    // Loading lasts until the visual collection setting is read too, so a visual collection never opens on the unfiltered albums.
    val uiState: StateFlow<LibraryUiState> = combine(
        content,
        folders.folders(),
        scanner.progress,
        visualCollection.hideAlbumsWithoutArtwork,
    ) { state, list, progress, hideArtless ->
        state.copy(hasFolders = list.isNotEmpty(), scanning = progress is ScanProgress.Scanning, hideAlbumsWithoutArtwork = hideArtless)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** The visual collection's play/pause button, `null` with nothing loaded. */
    val playButton: StateFlow<VisualCollectionPlayButton?> = playback.state.map(::visualCollectionPlayButton).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The visual collection's Now Playing capsule and playing-row cue, `null` with nothing loaded. */
    internal val nowPlaying: StateFlow<VisualCollectionNowPlaying?> = playback.state.map(::visualCollectionNowPlaying).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _addFailed = MutableStateFlow(false)

    /** True after a picked folder could not be added; cleared by [dismissAddFailure]. */
    val addFailed: StateFlow<Boolean> = _addFailed.asStateFlow()

    /** Adds the first library folder; the scanner starts on its own once the folder is stored. */
    fun addFolder(treeUri: String) {
        viewModelScope.launch {
            _addFailed.value = try {
                folders.add(treeUri) !is AddFolderResult.Added
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
        }
    }

    fun dismissAddFailure() {
        _addFailed.value = false
    }

    /** Sorts Songs, Albums or Artists ([list]) by [order] from now on; a sort that cannot be saved is dropped. */
    fun setSort(list: SortList, order: SortOrder) = launchSafely("save a sort") { sorting.select(SortTarget(list), order) }

    /** The album's songs in album order, the ones pinned on its page first. */
    fun albumTracks(albumId: Long): Flow<Pinned<Track>> = repository.tracksForAlbum(albumId).pinned(marks.pins(PinContext.album(albumId))) { it.uri }

    fun artistAlbums(artistId: Long): Flow<List<Album>> = repository.albumsForArtist(artistId)

    /** The artist's own songs, by title, the ones pinned on their page first. */
    fun artistTracks(artistId: Long): Flow<Pinned<Track>> = repository.tracksForArtist(artistId).pinned(marks.pins(PinContext.artist(artistId))) { it.uri }

    fun play(queue: List<Track>, index: Int) = playback.playQueue(queue, index)

    fun shuffle(queue: List<Track>) = playback.playQueue(queue, 0, shuffle = true)

    fun enqueue(tracks: List<Track>, action: QueueAction) = playback.enqueue(tracks, action)

    fun togglePlayPause() = playback.togglePlayPause()

    /** Queues the album's tracks in album order (disc, then track number, as the repository returns them). */
    fun enqueueAlbum(albumId: Long, action: QueueAction) {
        viewModelScope.launch {
            val tracks = repository.tracksForAlbum(albumId).first()
            if (tracks.isNotEmpty()) playback.enqueue(tracks, action)
        }
    }

    private fun launchSafely(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot $what", e)
            }
        }
    }

    private companion object {
        const val TAG = "SonoraLibrary"
        const val RECENT_LIMIT = 12
    }
}
