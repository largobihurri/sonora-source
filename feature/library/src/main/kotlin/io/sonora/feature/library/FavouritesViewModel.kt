package io.sonora.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.ObservePlaylists
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaybackController
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The Favourites smart playlist: favourite songs, then favourite albums and playlists. */
data class FavouritesUiState(
    val loading: Boolean = true,
    val songs: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<ResolvedPlaylist> = emptyList(),
)

/** Library > Playlists > Favourites, kept in the order the favourites repository lists them (newest first). */
@HiltViewModel
class FavouritesViewModel @Inject constructor(
    library: LibraryRepository,
    marks: LibraryMarksRepository,
    private val playback: PlaybackController,
    observePlaylists: ObservePlaylists,
) : ViewModel() {

    private val songs = combine(marks.favourites(MarkKind.SONG), library.tracks()) { keys, tracks ->
        keys.pick(tracks) { it.uri }
    }
    private val albums = combine(marks.favourites(MarkKind.ALBUM), library.albums()) { keys, all ->
        keys.pick(all) { it.id.toString() }
    }
    // Playlists removed from Sonora are left out, as Playlists leaves them out.
    private val playlists = combine(marks.favourites(MarkKind.PLAYLIST), observePlaylists(), marks.hidden()) { keys, all, hidden ->
        val hiddenIds = hidden.filter { it.ref.kind == MarkKind.PLAYLIST }.mapTo(HashSet()) { it.ref.key }
        keys.filterNot { it in hiddenIds }.pick(all) { it.playlist.id.toString() }
    }

    val state: StateFlow<FavouritesUiState> = combine(songs, albums, playlists) { songs, albums, playlists ->
        FavouritesUiState(loading = false, songs = songs, albums = albums, playlists = playlists)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavouritesUiState())

    fun play(index: Int) {
        val songs = state.value.songs
        if (songs.isNotEmpty()) playback.playQueue(songs, index.coerceIn(songs.indices))
    }

    fun shuffle() {
        val songs = state.value.songs
        if (songs.isNotEmpty()) playback.playQueue(songs, 0, shuffle = true)
    }
}

/** The items these favourite keys name, in the keys' order; keys of items no longer in the library are skipped. */
private fun <T> List<String>.pick(items: List<T>, key: (T) -> String): List<T> {
    val byKey = items.associateBy(key)
    return mapNotNull(byKey::get)
}
