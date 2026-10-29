package io.sonora.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.smart.ObserveSmartPlaylist
import io.sonora.core.domain.library.smart.SmartPlaylist
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PlaybackController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One smart playlist's songs, in the playlist's own order. */
data class SmartPlaylistUiState(
    val loading: Boolean = true,
    val songs: List<Track> = emptyList(),
)

/**
 * Library > Playlists > Recently Added, Most Played or Never Played: the songs the playlist's rule picks, live, in
 * the rule's order (which is the point of the playlist, so there is no sort).
 */
@HiltViewModel(assistedFactory = SmartPlaylistViewModel.Factory::class)
class SmartPlaylistViewModel @AssistedInject constructor(
    @Assisted val playlist: SmartPlaylist,
    observe: ObserveSmartPlaylist,
    private val playback: PlaybackController,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(playlist: SmartPlaylist): SmartPlaylistViewModel
    }

    val state: StateFlow<SmartPlaylistUiState> = observe(playlist)
        .map { SmartPlaylistUiState(loading = false, songs = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartPlaylistUiState())

    fun play(index: Int) {
        val songs = state.value.songs
        if (songs.isNotEmpty()) playback.playQueue(songs, index.coerceIn(songs.indices))
    }

    fun shuffle() {
        val songs = state.value.songs
        if (songs.isNotEmpty()) playback.playQueue(songs, 0, shuffle = true)
    }
}
