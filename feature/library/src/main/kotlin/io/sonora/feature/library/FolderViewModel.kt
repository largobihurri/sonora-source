package io.sonora.feature.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.marks.PinOrdering
import io.sonora.core.domain.log.redactForLog
import io.sonora.core.domain.model.FolderContents
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.QueueAction
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.FolderBrowser
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.sort.LibrarySort
import io.sonora.core.domain.sort.ListSorting
import io.sonora.core.domain.sort.SortList
import io.sonora.core.domain.sort.SortOrder
import io.sonora.core.domain.sort.SortTarget
import io.sonora.core.domain.sort.Sorted
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One folder as listed: [contents] with its pinned subfolders and songs first; [pinned] holds their keys. */
data class FolderView(val contents: FolderContents, val pinned: Set<String>)

/** Library > Folders: the library folders' real tree. Play, Shuffle and the folder menu cover all subfolders. */
@HiltViewModel
class FolderViewModel @Inject constructor(
    private val browser: FolderBrowser,
    private val playback: PlaybackController,
    private val sorting: ListSorting,
    private val marks: LibraryMarksRepository,
) : ViewModel() {

    /** The library folders; `null` until loaded. */
    val roots: StateFlow<List<FolderEntry>?> =
        browser.roots().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [folder]'s subfolders and songs in the folder sort (shared by every folder screen), sorted off the main thread. */
    fun contents(folder: FolderRef): Flow<Sorted<FolderView>> =
        combine(sorting.sorted(browser.contents(folder), FOLDER, LibrarySort::folder), marks.pins(PinContext.folder(folder))) { sorted, pins ->
            val subfolders = PinOrdering.apply(sorted.items.subfolders, pins) { ItemRef.folder(it.folder).key }
            val tracks = PinOrdering.apply(sorted.items.tracks, pins) { it.uri }
            val contents = sorted.items.copy(subfolders = subfolders.items, tracks = tracks.items)
            Sorted(sorted.order, FolderView(contents, subfolders.pinnedKeys + tracks.pinnedKeys))
        }

    /** Sorts every folder screen by [order] from now on; a sort that cannot be saved is dropped. */
    fun setSort(order: SortOrder) = launchSafely("save the folder sort") { sorting.select(FOLDER, order) }

    /** Plays every track in [folder] and its subfolders, in folder order. */
    fun play(folder: FolderRef) = withTracks(folder) { playback.playQueue(it, 0) }

    /** Shuffles every track in [folder] and its subfolders. */
    fun shuffle(folder: FolderRef) = withTracks(folder) { playback.playQueue(it, 0, shuffle = true) }

    /** Queues every track in [folder] and its subfolders, in folder order. */
    fun enqueue(folder: FolderRef, action: QueueAction) = withTracks(folder) { playback.enqueue(it, action) }

    /** A tapped song: plays the folder's own songs from it, like an album. */
    fun playTracks(tracks: List<Track>, index: Int) = playback.playQueue(tracks, index)

    fun enqueueTrack(track: Track, action: QueueAction) = playback.enqueue(listOf(track), action)

    /** Runs [block] with the folder's tracks; if the index cannot be read, the action does nothing. */
    private fun withTracks(folder: FolderRef, block: (List<Track>) -> Unit) {
        viewModelScope.launch {
            val tracks = try {
                browser.tracksUnder(folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot load the tracks of ${redactForLog(folder.path)}", e)
                return@launch
            }
            if (tracks.isNotEmpty()) block(tracks)
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
        const val TAG = "SonoraFolders"
        val FOLDER = SortTarget(SortList.FOLDER)
    }
}
