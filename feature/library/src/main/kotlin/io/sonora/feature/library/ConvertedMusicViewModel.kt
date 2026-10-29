package io.sonora.feature.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.ObserveConvertedMusic
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.marks.PinOrdering
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.sort.ConvertedGroup
import io.sonora.core.domain.sort.LibrarySort
import io.sonora.core.domain.sort.ListSorting
import io.sonora.core.domain.sort.SortList
import io.sonora.core.domain.sort.SortOrder
import io.sonora.core.domain.sort.SortTarget
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Library > Converted: [groups] in the chosen [order] (date sections when sorting by date converted, one list
 * otherwise), [loading] until the files were first read.
 */
data class ConvertedMusicUiState(
    val groups: List<ConvertedGroup> = emptyList(),
    val loading: Boolean = true,
    val order: SortOrder = SortList.CONVERTED.default,
    /** URIs of the songs pinned to the top. */
    val pinned: Set<String> = emptySet(),
) {
    /** Every listed song in display order, the queue that Play, Shuffle and a tapped song use. */
    val tracks: List<Track> = groups.flatMap { it.tracks }
}

/** Library > Converted: the converter's songs that still exist ([ObserveConvertedMusic]), played as one list. */
@HiltViewModel
class ConvertedMusicViewModel @Inject constructor(
    observe: ObserveConvertedMusic,
    private val playback: PlaybackController,
    private val sorting: ListSorting,
    private val marks: LibraryMarksRepository,
) : ViewModel() {

    // A failed read is logged and tried again a little later, so the list comes back on its own; until something was
    // read it shows as empty (not loading), and once it was, it keeps what it showed.
    private val sections = flow {
        var shown = false
        emitAll(
            observe().onEach { shown = true }.retryWhen { e, _ ->
                Log.w(TAG, "Cannot list converted music", e)
                if (!shown) {
                    shown = true
                    emit(emptyList())
                }
                delay(RETRY_DELAY_MS)
                true
            },
        )
    }

    // Songs removed from Sonora are left out; pinned songs form a first group above the sort's groups.
    val state: StateFlow<ConvertedMusicUiState> = combine(
        sorting.sorted(sections, TARGET, LibrarySort::converted),
        marks.hidden(),
        marks.pins(PinContext.CONVERTED),
    ) { sorted, hidden, pins ->
        val hiddenUris = hidden.filter { it.ref.kind == MarkKind.SONG }.mapTo(HashSet()) { it.ref.key }
        val visible = sorted.items.map { group -> group.copy(tracks = group.tracks.filterNot { it.uri in hiddenUris }) }
        val pinned = PinOrdering.apply(visible.flatMap { it.tracks }, pins) { it.uri }
        val top = pinned.items.takeWhile { it.uri in pinned.pinnedKeys }
        val groups = listOfNotNull(top.takeIf { it.isNotEmpty() }?.let { ConvertedGroup(null, it) }) +
            visible.map { group -> group.copy(tracks = group.tracks.filterNot { it.uri in pinned.pinnedKeys }) }
        ConvertedMusicUiState(groups.filter { it.tracks.isNotEmpty() }, loading = false, order = sorted.order, pinned = pinned.pinnedKeys)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConvertedMusicUiState())

    /** Sorts the list by [order] from now on; a sort that cannot be saved is dropped. */
    fun setSort(order: SortOrder) = launchSafely("save the converted music sort") { sorting.select(TARGET, order) }

    /** Plays the whole list from [startIndex]. */
    fun play(startIndex: Int = 0) {
        val tracks = state.value.tracks
        if (startIndex in tracks.indices) playback.playQueue(tracks, startIndex)
    }

    /** A tapped song: plays the whole list from it. */
    fun play(track: Track) = play(state.value.tracks.indexOfFirst { it.id == track.id })

    fun shuffle() {
        val tracks = state.value.tracks
        if (tracks.isNotEmpty()) playback.playQueue(tracks, 0, shuffle = true)
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

    internal companion object {
        private const val TAG = "SonoraConverted"
        private val TARGET = SortTarget(SortList.CONVERTED)

        /** How long after a failed read the converted music is read again. */
        const val RETRY_DELAY_MS = 5_000L
    }
}
