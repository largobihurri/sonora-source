package io.sonora.feature.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.marks.PinOrdering
import io.sonora.core.domain.library.marks.Pinned
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.DeletePlaylist
import io.sonora.core.domain.playlist.EditPlaylistDetails
import io.sonora.core.domain.playlist.MovePlaylistEntry
import io.sonora.core.domain.playlist.ObservePlaylist
import io.sonora.core.domain.playlist.PlaylistEdits
import io.sonora.core.domain.playlist.PlaylistItem
import io.sonora.core.domain.playlist.RemovePlaylistEntry
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.sort.LibrarySort
import io.sonora.core.domain.sort.ListSorting
import io.sonora.core.domain.sort.SortKey
import io.sonora.core.domain.sort.SortList
import io.sonora.core.domain.sort.SortOrder
import io.sonora.core.domain.sort.SortTarget
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One playlist: [playlist] once read (`null` with [loading] false once it is deleted), whether it is in Edit mode, and
 * its entries as listed ([items], in the playlist's [order]; the playlist itself keeps its own order and cover).
 */
data class PlaylistDetailUiState(
    val loading: Boolean = true,
    val playlist: ResolvedPlaylist? = null,
    val editing: Boolean = false,
    val order: SortOrder = SortList.PLAYLIST.default,
    val items: List<PlaylistItem> = playlist?.items.orEmpty(),
    /** Song URIs pinned to the top of this playlist (not applied while editing). */
    val pinned: Set<String> = emptySet(),
) {
    /** The playlist was deleted (here or elsewhere); the screen closes. */
    val deleted: Boolean get() = !loading && playlist == null

    /** Songs can be dragged into place only while the playlist is listed in its own order. */
    val canReorder: Boolean get() = order.key == SortKey.PLAYLIST_ORDER

    /** What Play and Shuffle play: the available songs as listed. */
    val playableTracks: List<Track> get() = items.mapNotNull { it.track }
}

/**
 * Playlist detail: Play / Shuffle and tapped songs play only the available songs, in the order listed; Edit mode
 * removes entries and, in Playlist Order, reorders them, saved as they happen. Each playlist keeps its own sort.
 */
@HiltViewModel(assistedFactory = PlaylistDetailViewModel.Factory::class)
class PlaylistDetailViewModel @AssistedInject constructor(
    @Assisted private val playlistId: Long,
    observe: ObservePlaylist,
    private val editDetails: EditPlaylistDetails,
    private val deletePlaylist: DeletePlaylist,
    private val removeEntry: RemovePlaylistEntry,
    private val moveEntry: MovePlaylistEntry,
    private val playback: PlaybackController,
    private val sorting: ListSorting,
    private val marks: LibraryMarksRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(playlistId: Long): PlaylistDetailViewModel
    }

    private val editing = MutableStateFlow(false)
    private val target = SortTarget(SortList.PLAYLIST, playlistId)

    private val sorted = sorting.sorted(observe(playlistId), target) { playlist, order, locale ->
        playlist to playlist?.let { LibrarySort.playlist(it.items, order, locale) }.orEmpty()
    }

    // Pins sit above the sort, except in Edit, which shows (and reorders) the playlist's own order.
    val state: StateFlow<PlaylistDetailUiState> = combine(sorted, editing, marks.pins(PinContext.playlist(playlistId))) { sorted, editing, pins ->
        val (playlist, items) = sorted.items
        val editable = editing && playlist != null
        val pinned = if (editable) Pinned(items, emptySet()) else PinOrdering.apply(items, pins) { it.entry.trackUri }
        PlaylistDetailUiState(
            loading = false,
            playlist = playlist,
            editing = editable,
            order = sorted.order,
            items = pinned.items,
            pinned = pinned.pinnedKeys,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistDetailUiState())

    private val playable get() = state.value.playableTracks

    fun play() {
        if (playable.isNotEmpty()) playback.playQueue(playable, 0)
    }

    fun shuffle() {
        if (playable.isNotEmpty()) playback.playQueue(playable, 0, shuffle = true)
    }

    /** A tapped entry: plays the available songs from it; an unavailable entry does nothing. */
    fun play(entryId: Long) {
        val items = state.value.items
        val index = items.indexOfFirst { it.entry.id == entryId }
        if (index < 0 || !items[index].isAvailable) return
        playback.playQueue(playable, items.take(index).count { it.isAvailable })
    }

    fun startEditing() {
        editing.value = true
    }

    fun finishEditing() {
        editing.value = false
    }

    /** Lists the playlist by [order] from now on; reordering needs Playlist Order. */
    fun setSort(order: SortOrder) = launchSafely("sort a playlist") { sorting.select(target, order) }

    fun remove(entryId: Long) = launchSafely("remove a playlist entry") { removeEntry(playlistId, entryId) }

    /** Moves entry [entryId] to [toIndex] among all entries (unavailable ones included). */
    fun move(entryId: Long, toIndex: Int) = launchSafely("move a playlist entry") { moveEntry(playlistId, entryId, toIndex) }

    /** Renames the playlist and sets its description; a blank name does nothing. */
    fun editDetails(name: String, description: String) {
        if (PlaylistEdits.cleanName(name) == null) return
        launchSafely("edit a playlist") { editDetails(playlistId, name, description) }
    }

    fun delete() {
        editing.value = false
        launchSafely("delete a playlist") {
            deletePlaylist(playlistId)
            sorting.forget(target)
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
        const val TAG = "SonoraPlaylist"
    }
}
