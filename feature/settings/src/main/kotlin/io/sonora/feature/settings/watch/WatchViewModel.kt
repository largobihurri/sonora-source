package io.sonora.feature.settings.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.watchport.WatchSyncController
import io.sonora.core.watchport.WatchSyncState
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Songs the user asked to remove from the watch, waiting for the confirmation. */
data class PendingRemoval(val songIds: List<String>) {
    val count: Int get() = songIds.size
}

/**
 * What Settings › Watch shows. [groups] and [space] come from the last successful read of the watch and are empty while
 * the watch cannot be read; [transfer] follows the send the controller runs, whoever started it.
 */
data class WatchUiState(
    val link: WatchLink = WatchLink.CHECKING,
    val space: WatchSpace? = null,
    val groups: List<WatchAlbumGroup> = emptyList(),
    val transfer: WatchTransfer = WatchTransfer.Idle,
    val pendingRemoval: PendingRemoval? = null,
    val removing: Boolean = false,
    val message: WatchMessage? = null,
)

/**
 * Settings › Watch over the [WatchSyncController]: the connection, the send in progress and the last result, the watch's
 * space and its songs by album, and removing songs. The watch is read when the page opens, when a send ends or is
 * cancelled, after a removal and on request; never while a send holds the connection.
 */
@HiltViewModel
class WatchViewModel @Inject constructor(private val watch: WatchSyncController) : ViewModel() {

    private val _state = MutableStateFlow(WatchUiState())
    val state: StateFlow<WatchUiState> = _state.asStateFlow()

    private var reading: Job? = null

    init {
        viewModelScope.launch {
            var previous: WatchSyncState? = null
            watch.state.collect { sync ->
                val first = previous == null
                val wasRunning = previous is WatchSyncState.Running
                previous = sync
                _state.update {
                    it.copy(
                        transfer = WatchTransfers.of(sync),
                        link = when {
                            sync is WatchSyncState.Running -> WatchLink.SENDING
                            it.link == WatchLink.SENDING -> WatchLink.CHECKING
                            else -> it.link
                        },
                    )
                }
                // What a send changed on the watch shows once it is over (also when it was cancelled).
                if (sync !is WatchSyncState.Running && (first || wasRunning)) read(restart = wasRunning)
            }
        }
    }

    /** Reads the watch again; does nothing while a read or a send is going on. */
    fun refresh() = read(restart = false)

    fun cancelSend() = watch.cancel()

    /** Clears the last result (the controller keeps it until then). */
    fun dismissResult() = watch.dismissResult()

    /** Asks to remove [songIds] (those the watch holds, as listed); the page confirms with [confirmRemove]. */
    fun askRemove(songIds: List<String>) {
        val current = _state.value
        if (current.removing || current.transfer is WatchTransfer.Running) return
        val onWatch = current.groups.flatMap { it.songIds }.toSet()
        val wanted = songIds.filter { it in onWatch }.distinct()
        if (wanted.isEmpty()) return
        _state.update { it.copy(pendingRemoval = PendingRemoval(wanted)) }
    }

    fun cancelRemove() = _state.update { it.copy(pendingRemoval = null) }

    fun confirmRemove() {
        val pending = _state.value.pendingRemoval ?: return
        if (_state.value.removing) return
        _state.update { it.copy(pendingRemoval = null, removing = true) }
        viewModelScope.launch {
            val message = watch.removeFromWatch(pending.songIds).fold(
                onSuccess = { removed ->
                    when {
                        removed.isEmpty() -> WatchMessage.RemoveFailed
                        removed.size < pending.count -> WatchMessage.PartlyRemoved(removed.size, pending.count)
                        else -> WatchMessage.Removed(removed.size)
                    }
                },
                onFailure = { WatchMessage.RemoveFailed },
            )
            _state.update { it.copy(removing = false, message = message) }
            read(restart = true)
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** [restart]: a read already under way is replaced (its answer is older than what just happened). */
    private fun read(restart: Boolean) {
        if (_state.value.transfer is WatchTransfer.Running) return
        if (reading?.isActive == true) {
            if (!restart) return
            reading?.cancel()
        }
        reading = viewModelScope.launch {
            _state.update { it.copy(link = WatchLink.CHECKING) }
            watch.readWatch().fold(
                onSuccess = { watchState ->
                    _state.update {
                        it.copy(
                            link = WatchLink.CONNECTED,
                            space = WatchSpace.of(watchState),
                            groups = WatchLibrary.groups(watchState.tracks),
                        )
                    }
                },
                onFailure = { error ->
                    val link = WatchLinks.of(error)
                    // A refused read only means a send is running: what was shown stays. Any other failure leaves nothing true to show.
                    _state.update {
                        if (link == WatchLink.SENDING) it.copy(link = link) else it.copy(link = link, space = null, groups = emptyList())
                    }
                },
            )
        }
    }
}
