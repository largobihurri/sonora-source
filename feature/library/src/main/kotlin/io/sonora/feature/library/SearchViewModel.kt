package io.sonora.feature.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.model.QueueAction
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.SearchHistoryRepository
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val playback: PlaybackController,
    private val history: SearchHistoryRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val results: StateFlow<List<Track>> = _query
        .flatMapLatest { repository.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    /** The recent searches, most recent first, shown while the field is empty and focused. */
    val recentSearches: StateFlow<List<String>> = history.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The keyboard's search key: the current search counts as recent. */
    fun onSubmit() = recordSearch(_query.value)

    /** A recent search tapped: search for it again, and it becomes the most recent. */
    fun searchAgain(entry: String) {
        _query.value = entry
        recordSearch(entry)
    }

    fun removeRecentSearch(entry: String) = editHistory("forget a recent search") { history.remove(entry) }

    fun clearRecentSearches() = editHistory("clear the recent searches") { history.clear() }

    /** Opening a result plays it and counts its search as recent. */
    fun play(queue: List<Track>, index: Int) {
        recordSearch(_query.value)
        playback.playQueue(queue, index)
    }

    fun enqueue(track: Track, action: QueueAction) = playback.enqueue(listOf(track), action)

    private fun recordSearch(query: String) {
        if (query.isBlank()) return
        editHistory("record a search") { history.record(query) }
    }

    /** The history is a convenience: a store that cannot be written (a full disk) only loses the change. */
    private fun editHistory(what: String, edit: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                edit()
            } catch (e: IOException) {
                Log.w(TAG, "Cannot $what", e)
            }
        }
    }

    private companion object {
        const val TAG = "SonoraSearch"
    }
}
