package io.sonora.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.conversion.batch.PendingConversionSources
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.LibraryRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** The songs picked so far, in the order they were picked (one entry per file). */
data class TrackSelection(val tracks: List<Track> = emptyList()) {
    private val uris = tracks.mapTo(HashSet()) { it.uri }

    val count: Int get() = tracks.size

    fun isSelected(track: Track): Boolean = track.uri in uris

    /** Whether every song of [list] is picked; false for an empty list. */
    fun allSelected(list: List<Track>): Boolean = list.isNotEmpty() && list.all(::isSelected)
}

/**
 * The Converter's "Add Music…" picker over the library: multi-select (tap toggles, "Select All" per list), search
 * through the library's search, and Add, which sends the picked songs to the Converter's [pending] sources. The lists
 * themselves come from the Library's own view models.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
@HiltViewModel
class LibraryPickerViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val pending: PendingConversionSources,
) : ViewModel() {

    private val _selection = MutableStateFlow(TrackSelection())
    val selection: StateFlow<TrackSelection> = _selection.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Songs matching [query]; none while it is blank. */
    val results: StateFlow<List<Track>> = _query
        .flatMapLatest { query -> if (query.isBlank()) flowOf(emptyList()) else library.search(query.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun toggle(track: Track) = _selection.update { selection ->
        TrackSelection(if (selection.isSelected(track)) selection.tracks.filterNot { it.uri == track.uri } else selection.tracks + track)
    }

    /** "Select All" for [list]: picks the songs of it not picked yet; when all are picked already, unpicks them. */
    fun toggleAll(list: List<Track>) = _selection.update { selection ->
        if (selection.allSelected(list)) {
            val dropped = list.mapTo(HashSet()) { it.uri }
            TrackSelection(selection.tracks.filterNot { it.uri in dropped })
        } else {
            TrackSelection((selection.tracks + list).distinctBy { it.uri })
        }
    }

    /** Cancel: forgets the picks, sending nothing. */
    fun clearSelection() {
        _selection.value = TrackSelection()
    }

    /** Sends the picked songs to the Converter and starts over; false when nothing was picked. */
    fun add(): Boolean {
        val picked = _selection.value.tracks
        if (picked.isEmpty()) return false
        pending.add(picked.map { it.asConversionSource() })
        _selection.value = TrackSelection()
        return true
    }
}
