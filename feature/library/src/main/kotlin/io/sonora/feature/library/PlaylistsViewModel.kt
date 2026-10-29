package io.sonora.feature.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.marks.PinOrdering
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.AddTracksToPlaylist
import io.sonora.core.domain.playlist.CreatePlaylist
import io.sonora.core.domain.playlist.ObservePlaylists
import io.sonora.core.domain.playlist.PlaylistEdits
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.core.domain.port.FolderBrowser
import io.sonora.core.domain.port.LibraryRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Library > Playlists: every playlist by name; [loading] until first read. */
data class PlaylistsUiState(
    val loading: Boolean = true,
    val playlists: List<ResolvedPlaylist> = emptyList(),
    /** Ids of the playlists pinned to the top. */
    val pinned: Set<String> = emptySet(),
)

/** The songs an "Add to Playlist" (or a "Convert…") takes, resolved when it is chosen ([tracks]). */
sealed interface PlaylistSource {
    data class Tracks(val tracks: List<Track>) : PlaylistSource

    /** The album's songs in album order. */
    data class Album(val albumId: Long) : PlaylistSource

    /** The artist's songs: their albums' songs, without other artists' songs on compilations. */
    data class Artist(val artistId: Long) : PlaylistSource

    /** Every song in the folder and its subfolders, in folder order. */
    data class Folder(val folder: FolderRef) : PlaylistSource
}

/** [this] source's songs, read from [library] and [folders] now. */
internal suspend fun PlaylistSource.tracks(library: LibraryRepository, folders: FolderBrowser): List<Track> = when (this) {
    is PlaylistSource.Tracks -> tracks
    is PlaylistSource.Album -> library.tracksForAlbum(albumId).first()
    is PlaylistSource.Artist -> library.albumsForArtist(artistId).first().flatMap { album ->
        library.tracksForAlbum(album.id).first().filter { it.artistId == artistId || album.artistId == artistId }
    }
    is PlaylistSource.Folder -> folders.tracksUnder(folder)
}

/** One-off outcomes the screen reacts to. */
sealed interface PlaylistEvent {
    /** A playlist made from the Playlists screen, to open. */
    data class Created(val playlistId: Long) : PlaylistEvent

    /** Songs were added to [playlistName] ("Added to …" banner). */
    data class Added(val playlistName: String) : PlaylistEvent

    /** The source turned out to hold no songs, so nothing was added (or created). */
    data object NothingToAdd : PlaylistEvent

    /** The songs could not be read or stored; nothing was added (or created). */
    data object AddFailed : PlaylistEvent
}

/** The playlists list, "New Playlist…" and the "Add to Playlist" picker, shared by the Library stack. */
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    observe: ObservePlaylists,
    private val createPlaylist: CreatePlaylist,
    private val addTracks: AddTracksToPlaylist,
    private val library: LibraryRepository,
    private val folders: FolderBrowser,
    private val marks: LibraryMarksRepository,
) : ViewModel() {

    /** Every playlist but the ones removed from Sonora, the pinned ones first. */
    val state: StateFlow<PlaylistsUiState> = combine(observe(), marks.hidden(), marks.pins(PinContext.PLAYLISTS)) { all, hidden, pins ->
        val hiddenIds = hidden.filter { it.ref.kind == MarkKind.PLAYLIST }.mapTo(HashSet()) { it.ref.key }
        val pinned = PinOrdering.apply(all.filterNot { it.playlist.id.toString() in hiddenIds }, pins) { it.playlist.id.toString() }
        PlaylistsUiState(loading = false, playlists = pinned.items, pinned = pinned.pinnedKeys)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsUiState())

    private val _events = Channel<PlaylistEvent>(Channel.BUFFERED)
    val events: Flow<PlaylistEvent> = _events.receiveAsFlow()

    /** Creates an empty playlist and asks to open it; a blank name does nothing. */
    fun create(name: String, description: String) {
        if (PlaylistEdits.cleanName(name) == null) return
        launchSafely("create a playlist") {
            _events.send(PlaylistEvent.Created(createPlaylist(name, description)))
        }
    }

    /** Appends [source]'s songs to playlist [playlistId]; says so when there are none or they cannot be added. */
    fun addTo(playlistId: Long, playlistName: String, source: PlaylistSource) {
        addReporting(source) { tracks ->
            addTracks(playlistId, tracks)
            playlistName
        }
    }

    /**
     * "New Playlist…" in the picker: creates the playlist already holding [source]'s songs. It is created only with
     * songs: a source without any, or one that cannot be read, creates nothing and says so.
     */
    fun createAndAdd(name: String, description: String, source: PlaylistSource) {
        val clean = PlaylistEdits.cleanName(name) ?: return
        addReporting(source) { tracks ->
            createPlaylist(clean, description, tracks)
            clean
        }
    }

    /** Resolves [source], runs [add] with its songs and reports the outcome: always one event per request. */
    private fun addReporting(source: PlaylistSource, add: suspend (List<Track>) -> String) {
        viewModelScope.launch {
            val event = try {
                val tracks = tracksOf(source)
                if (tracks.isEmpty()) PlaylistEvent.NothingToAdd else PlaylistEvent.Added(add(tracks))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot add to a playlist", e)
                PlaylistEvent.AddFailed
            }
            _events.send(event)
        }
    }

    private suspend fun tracksOf(source: PlaylistSource): List<Track> = source.tracks(library, folders)

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
        const val TAG = "SonoraPlaylists"
    }
}
