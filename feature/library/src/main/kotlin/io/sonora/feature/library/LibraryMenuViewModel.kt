package io.sonora.feature.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.library.removal.DeleteResult
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.QueueAction
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.FolderBrowser
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.domain.port.RekordboxExportRequester
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import io.sonora.core.watchport.WatchSyncController
import io.sonora.core.watchport.WatchSyncState
import io.sonora.feature.library.ui.MenuAction
import io.sonora.feature.library.ui.MenuTarget
import io.sonora.feature.library.ui.playlistSource
import java.util.Optional
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The favourites the menus show as filled stars, by kind. */
internal data class Favourites(
    val songs: Set<String> = emptySet(),
    val albums: Set<String> = emptySet(),
    val artists: Set<String> = emptySet(),
    val playlists: Set<String> = emptySet(),
) {
    operator fun contains(ref: ItemRef): Boolean = when (ref.kind) {
        MarkKind.SONG -> ref.key in songs
        MarkKind.ALBUM -> ref.key in albums
        MarkKind.ARTIST -> ref.key in artists
        MarkKind.PLAYLIST -> ref.key in playlists
        MarkKind.FOLDER -> false
    }
}

/** Where "Remove from Library" is: each step is one dialog. */
internal sealed interface RemoveStep {
    val target: MenuTarget

    /** Remove from Sonora, Delete from Phone or Cancel; asked every time. */
    data class Choose(override val target: MenuTarget) : RemoveStep

    /** The irreversible confirmation for [tracks]' files. */
    data class ConfirmDelete(override val target: MenuTarget, val tracks: List<Track>) : RemoveStep

    /** A folder is read-only: pick it again to allow deleting; [wrongFolder] after another folder was picked. */
    data class NeedsAccess(
        override val target: MenuTarget,
        val tracks: List<Track>,
        val request: WriteAccessRequest,
        val wrongFolder: Boolean = false,
    ) : RemoveStep

    data class Deleting(override val target: MenuTarget) : RemoveStep
}

/** One-off outcomes the screen shows (a banner) or carries out (the share sheet). */
internal sealed interface MenuEvent {
    data class Queued(val action: QueueAction) : MenuEvent

    /** Share these files ([uris]) through the system chooser. */
    data class Share(val uris: List<String>, val mimeTypes: List<String>) : MenuEvent

    data class Hidden(val title: String) : MenuEvent

    data class Deleted(val count: Int) : MenuEvent

    /** The playlist [name] is gone; its songs' files stay. */
    data class PlaylistDeleted(val name: String) : MenuEvent

    /** Only [deleted] of the files could be deleted; [failed] are still there. */
    data class DeleteIncomplete(val deleted: Int, val failed: Int) : MenuEvent

    /** The folder was picked again but its storage does not allow deleting. */
    data object StorageRefused : MenuEvent

    data class ExportQueued(val name: String) : MenuEvent

    /** A send of [name] to the watch began; the Watch page in Settings follows it. */
    data class WatchSendStarted(val name: String) : MenuEvent

    /** Another send to the watch is running, so this one did not start. */
    data object WatchBusy : MenuEvent

    /** The send this menu started ended, finished or failed ([state] is a `Finished` or `Failed`). */
    data class WatchSendEnded(val state: WatchSyncState) : MenuEvent

    /** The target turned out to hold no songs. */
    data object NoSongs : MenuEvent

    /** The songs could not be read; nothing happened. */
    data object Failed : MenuEvent
}

/**
 * Carries out the context menu shared by the Library and Search: favourites, pins, queueing, sharing, the CDJ export
 * "Send to Watch" and "Remove from Library" with its Remove from Sonora / Delete from Phone choice.
 */
@HiltViewModel
class LibraryMenuViewModel @Inject constructor(
    private val marks: LibraryMarksRepository,
    private val library: LibraryRepository,
    private val folders: FolderBrowser,
    private val playback: PlaybackController,
    private val deleteFromPhone: DeleteFromPhone,
    private val files: LibraryFileDeleter,
    private val playlists: PlaylistRepository,
    private val watch: WatchSyncController,
    exportRequester: Optional<RekordboxExportRequester>,
) : ViewModel() {

    private val exporter: RekordboxExportRequester? = exportRequester.orElse(null)

    /** Whether "Export as Rekordbox Playlist" can open the CDJ export. */
    val canExport: Boolean get() = exporter != null

    /** Whether "Send to Watch" is offered (false in a build without the watch link). */
    val canSendToWatch: Boolean get() = watch.isAvailable

    internal val favourites: StateFlow<Favourites> = combine(
        marks.favourites(MarkKind.SONG),
        marks.favourites(MarkKind.ALBUM),
        marks.favourites(MarkKind.ARTIST),
        marks.favourites(MarkKind.PLAYLIST),
    ) { songs, albums, artists, playlists ->
        Favourites(songs.toSet(), albums.toSet(), artists.toSet(), playlists.toSet())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Favourites())

    private val _removeStep = MutableStateFlow<RemoveStep?>(null)
    internal val removeStep: StateFlow<RemoveStep?> = _removeStep.asStateFlow()

    private val _events = Channel<MenuEvent>(Channel.BUFFERED)
    internal val events: Flow<MenuEvent> = _events.receiveAsFlow()

    /** The song read or folder grant that leads to the next dialog; [cancelRemove] stops it so it cannot reopen one. */
    private var pendingStep: Job? = null

    /** The keys pinned in [context], in pin order. */
    fun pins(context: PinContext): Flow<List<String>> = marks.pins(context)

    internal fun toggleFavourite(target: MenuTarget) {
        val ref = target.ref
        val favourite = ref !in favourites.value
        launchSafely("change a favourite") { marks.setFavourite(ref, favourite) }
    }

    internal fun setPinned(context: PinContext, target: MenuTarget, pinned: Boolean) =
        launchSafely("change a pin") { marks.setPinned(context, target.ref, pinned) }

    /** Play, Shuffle, Play Now, Play Next or Play Last ([action]) with [target]'s songs. */
    internal fun queue(target: MenuTarget, action: MenuAction) = withTracks(target) { tracks -> queueNow(tracks, action) }

    /** Play Next or Play Last ([action]) with the songs picked in a list (the selection bar), in their order. */
    internal fun queueTracks(tracks: List<Track>, action: MenuAction) {
        if (tracks.isEmpty()) return
        launchSafely("queue songs") { queueNow(tracks, action) }
    }

    /** Queues the songs picked in a list in the CDJ export as a Rekordbox playlist called [name]. */
    internal fun exportTracks(name: String, tracks: List<Track>) {
        val exporter = exporter ?: return
        if (tracks.isEmpty()) return
        launchSafely("export songs") {
            exporter.request(name, tracks.map { it.uri })
            _events.send(MenuEvent.ExportQueued(name))
        }
    }

    private suspend fun queueNow(tracks: List<Track>, action: MenuAction) {
        when (action) {
            MenuAction.PLAY -> playback.playQueue(tracks, 0)
            MenuAction.SHUFFLE -> playback.playQueue(tracks, 0, shuffle = true)
            MenuAction.PLAY_NOW -> playback.enqueue(tracks, QueueAction.PLAY_NOW)
            MenuAction.PLAY_NEXT -> {
                playback.enqueue(tracks, QueueAction.PLAY_NEXT)
                _events.send(MenuEvent.Queued(QueueAction.PLAY_NEXT))
            }
            MenuAction.PLAY_LAST -> {
                playback.enqueue(tracks, QueueAction.ADD_TO_QUEUE)
                _events.send(MenuEvent.Queued(QueueAction.ADD_TO_QUEUE))
            }
            else -> Unit
        }
    }

    internal fun share(target: MenuTarget) = withTracks(target) { tracks ->
        _events.send(MenuEvent.Share(tracks.map { it.uri }.distinct(), tracks.map { it.mimeType }.distinct()))
    }

    /** Queues [target]'s songs in the CDJ export as a Rekordbox playlist named after it. */
    internal fun exportToRekordbox(target: MenuTarget, name: String) {
        val exporter = exporter ?: return
        withTracks(target) { tracks ->
            exporter.request(name, tracks.map { it.uri })
            _events.send(MenuEvent.ExportQueued(name))
        }
    }

    // Send to Watch

    /** Sends an album or a playlist to the watch; other targets are not sent. */
    internal fun sendToWatch(target: MenuTarget) {
        when (target) {
            is MenuTarget.AlbumItem -> startWatchSend(target.album.title) { watch.sendAlbum(target.album.id) }
            is MenuTarget.PlaylistItem -> sendPlaylistToWatch(target.playlist.playlist.id, target.playlist.playlist.name)
            else -> Unit
        }
    }

    /** Sends the playlist whose page is open (its overflow menu has no [MenuTarget]). */
    internal fun sendPlaylistToWatch(playlistId: Long, name: String) = startWatchSend(name) { watch.sendPlaylist(playlistId) }

    /**
     * Starts a send with [start] and says so; when it ends, says how it went (a missing watch shows within a second). The
     * send itself runs in the controller, so it goes on if this screen goes away.
     */
    private fun startWatchSend(name: String, start: () -> Boolean) {
        viewModelScope.launch {
            val before = watch.state.value
            if (!start()) {
                _events.send(MenuEvent.WatchBusy)
                return@launch
            }
            _events.send(MenuEvent.WatchSendStarted(name))
            val ended = awaitEnd(before)
            // Idle is a cancelled send: the user knows.
            if (ended !is WatchSyncState.Idle) _events.send(MenuEvent.WatchSendEnded(ended))
        }
    }

    /**
     * The state this send ended in. The controller's state is a [kotlinx.coroutines.flow.StateFlow] that replays its
     * current value, and a result stays there until dismissed, so the value from before the send ([before]) must not
     * count as this send's end: until this send has shown `Running`, a state that is the very same instance as [before]
     * is skipped. The controller sets `Running` before `start` returns, so in practice the first value is already this
     * send's; this keeps the banner right even if a controller flipped it later.
     */
    private suspend fun awaitEnd(before: WatchSyncState): WatchSyncState {
        var sawRunning = false
        return watch.state.first { state ->
            if (state is WatchSyncState.Running) {
                sawRunning = true
                false
            } else {
                sawRunning || state !== before
            }
        }
    }

    // Remove from Library

    internal fun remove(target: MenuTarget) {
        _removeStep.value = RemoveStep.Choose(target)
    }

    internal fun cancelRemove() {
        pendingStep?.cancel()
        pendingStep = null
        _removeStep.value = null
    }

    /** "Remove from Sonora": hides the target; its files stay and Settings › Hidden Items brings it back. */
    internal fun hide(title: String, subtitle: String, nowMs: Long = System.currentTimeMillis()) {
        val step = _removeStep.value as? RemoveStep.Choose ?: return
        _removeStep.value = null
        launchSafely("hide an item") {
            marks.hide(HiddenItem(step.target.ref, title, subtitle, nowMs))
            _events.send(MenuEvent.Hidden(title))
        }
    }

    /**
     * "Delete from Phone": reads the songs, then asks for the irreversible confirmation. For a playlist it is "Delete
     * Playlist" instead, which deletes the list at once and leaves its songs' files alone.
     */
    internal fun chooseDelete() {
        val step = _removeStep.value as? RemoveStep.Choose ?: return
        (step.target as? MenuTarget.PlaylistItem)?.let { return deletePlaylist(it.playlist.playlist) }
        _removeStep.value = RemoveStep.Deleting(step.target)
        pendingStep = viewModelScope.launch {
            val tracks = tracksOrNull(step.target)
            _removeStep.value = if (tracks.isNullOrEmpty()) null else RemoveStep.ConfirmDelete(step.target, tracks)
            when {
                tracks == null -> _events.send(MenuEvent.Failed)
                tracks.isEmpty() -> _events.send(MenuEvent.NoSongs)
            }
        }
    }

    /** The user confirmed deleting the files for good. */
    internal fun confirmDelete() {
        val step = _removeStep.value as? RemoveStep.ConfirmDelete ?: return
        delete(step.target, step.tracks)
    }

    /**
     * The user picked [treeUri] to allow deleting; on the right folder the deletion goes ahead. The folder dialog
     * closes at once, so a second pick (the picker opened twice) finds none and is ignored.
     */
    internal fun accessPicked(treeUri: String) {
        val step = _removeStep.value as? RemoveStep.NeedsAccess ?: return
        _removeStep.value = RemoveStep.Deleting(step.target)
        pendingStep = viewModelScope.launch {
            val grant = try {
                files.grantWriteAccess(step.request, treeUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot keep write access", e)
                WriteGrant.REFUSED
            }
            when (grant) {
                WriteGrant.GRANTED -> delete(step.target, step.tracks)
                WriteGrant.WRONG_FOLDER -> _removeStep.value = step.copy(wrongFolder = true)
                WriteGrant.REFUSED -> {
                    _removeStep.value = null
                    _events.send(MenuEvent.StorageRefused)
                }
            }
        }
    }

    private fun delete(target: MenuTarget, tracks: List<Track>) {
        _removeStep.value = RemoveStep.Deleting(target)
        viewModelScope.launch {
            val result = try {
                deleteFromPhone(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot delete songs", e)
                null
            }
            // A cancel meanwhile wins: no folder dialog comes back. What happened to the files is reported either way.
            if (_removeStep.value == RemoveStep.Deleting(target)) {
                _removeStep.value = (result as? DeleteResult.NeedsWriteAccess)?.let {
                    RemoveStep.NeedsAccess(target, tracks, it.request)
                }
            }
            if ((result as? DeleteResult.Deleted)?.cleanUpFailed == true) {
                Log.w(TAG, "Some playlists or marks still name deleted songs")
            }
            when (result) {
                null -> _events.send(MenuEvent.Failed)
                is DeleteResult.NeedsWriteAccess -> Unit
                DeleteResult.NothingToDelete -> _events.send(MenuEvent.NoSongs)
                is DeleteResult.Deleted -> _events.send(
                    if (result.failed == 0) MenuEvent.Deleted(result.deleted) else MenuEvent.DeleteIncomplete(result.deleted, result.failed),
                )
            }
        }
    }

    private fun deletePlaylist(playlist: Playlist) {
        _removeStep.value = null
        launchSafely("delete a playlist") {
            playlists.delete(playlist.id)
            _events.send(MenuEvent.PlaylistDeleted(playlist.name))
        }
    }

    // Songs

    private suspend fun tracksOrNull(target: MenuTarget): List<Track>? = try {
        target.playlistSource().tracks(library, folders)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read the songs of a menu target", e)
        null
    }

    /** Runs [block] with [target]'s songs; says so when there are none or they cannot be read. */
    private fun withTracks(target: MenuTarget, block: suspend (List<Track>) -> Unit) {
        viewModelScope.launch {
            val tracks = tracksOrNull(target)
            when {
                tracks == null -> _events.send(MenuEvent.Failed)
                tracks.isEmpty() -> _events.send(MenuEvent.NoSongs)
                else -> block(tracks)
            }
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
        const val TAG = "SonoraMenu"
    }
}
