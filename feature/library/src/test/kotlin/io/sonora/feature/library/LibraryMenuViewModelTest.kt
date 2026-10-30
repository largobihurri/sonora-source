package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.model.FolderContents
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.QueueAction
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.PlaylistItem
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.core.domain.port.FolderBrowser
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.RekordboxExportRequester
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import io.sonora.core.watchport.WatchSyncFailure
import io.sonora.core.watchport.WatchSyncState
import io.sonora.core.watchsync.sender.SendReport
import io.sonora.feature.library.ui.MenuAction
import io.sonora.feature.library.ui.MenuTarget
import java.util.Optional
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryMenuViewModelTest {

    private class FakeFiles : LibraryFileDeleter {
        val readOnly = mutableSetOf<String>()
        var grant = WriteGrant.GRANTED
        /** Granting waits for this when set, like the system's grant call. */
        var grantGate: CompletableDeferred<Unit>? = null
        val deleted = mutableListOf<String>()
        override suspend fun writeAccessFor(uri: String) = if (uri in readOnly) REQUEST else null
        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String): WriteGrant {
            grantGate?.await()
            if (grant == WriteGrant.GRANTED) readOnly.clear()
            return grant
        }
        override suspend fun delete(uri: String): Boolean {
            deleted += uri
            return true
        }
    }

    private class QueueRecorder : PlaybackController {
        val calls = mutableListOf<String>()
        private fun record(op: String, tracks: List<Track>) {
            calls += "$op:" + tracks.joinToString(",") { it.id.toString() }
        }
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean) = record(if (shuffle) "shuffle" else "play", queue)
        override fun playNow(tracks: List<Track>) = record("now", tracks)
        override fun playNext(tracks: List<Track>) = record("next", tracks)
        override fun addToQueue(tracks: List<Track>) = record("last", tracks)
        override fun moveQueueItem(from: Int, to: Int) = Unit
        override fun removeQueueItem(index: Int) = Unit
        override fun clearUpcoming() = Unit
        override fun skipToQueueItem(index: Int) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun togglePlayPause() = Unit
        override fun skipToNext() = Unit
        override fun skipToPrevious() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setShuffle(enabled: Boolean) = Unit
        override fun setRepeatMode(mode: RepeatMode) = Unit
        override fun cycleRepeatMode() = Unit
    }

    private val one = libraryTrack(1, album = 7)
    private val two = libraryTrack(2, album = 7)
    private val other = libraryTrack(3, album = 8)
    private val marks = FakeLibraryMarks()
    private val files = FakeFiles()
    private val playlists = FakePlaylistRepository()
    private val playback = QueueRecorder()
    private val exported = mutableListOf<Pair<String, List<String>>>()
    private val watch = FakeWatchController()

    /** A folder whose songs are read only once [gate] opens. */
    private class SlowFolders(private val tracks: List<Track>) : FolderBrowser {
        val gate = CompletableDeferred<Unit>()
        override fun roots(): Flow<List<FolderEntry>> = emptyFlow()
        override fun contents(folder: FolderRef): Flow<FolderContents> = emptyFlow()
        override suspend fun tracksUnder(folder: FolderRef): List<Track> {
            gate.await()
            return tracks
        }
    }

    private fun viewModel(export: Boolean = true, folders: FolderBrowser = FakeFolderBrowser(listOf(one, two))) = LibraryMenuViewModel(
        marks = marks,
        library = FakeTrackLibrary(listOf(one, two, other)),
        folders = folders,
        playback = playback,
        deleteFromPhone = DeleteFromPhone(files, playlists, marks),
        files = files,
        playlists = playlists,
        watch = watch,
        exportRequester = if (export) Optional.of(RekordboxExportRequester { name, uris -> exported += name to uris }) else Optional.empty(),
    )

    private val albumTarget = MenuTarget.AlbumItem(album(7, artist = 1))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `favourites toggle and show as filled`() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.favourites.collect {} }
        vm.toggleFavourite(MenuTarget.Song(one))
        assertThat(ItemRef.song(one) in vm.favourites.value).isTrue()
        vm.toggleFavourite(MenuTarget.Song(one))
        assertThat(ItemRef.song(one) in vm.favourites.value).isFalse()
    }

    @Test
    fun `pins are set in the list the menu was opened from`() = runTest {
        val vm = viewModel()
        vm.setPinned(PinContext.album(7), MenuTarget.Song(two), pinned = true)
        assertThat(marks.pins.value).containsExactly(PinContext.album(7) to ItemRef.song(two))
        vm.setPinned(PinContext.album(7), MenuTarget.Song(two), pinned = false)
        assertThat(marks.pins.value).isEmpty()
    }

    @Test
    fun `sharing one song names it for the share sheet, sharing several does not`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.share(MenuTarget.Song(one))
        vm.share(albumTarget)
        assertThat(events.map { (it as MenuEvent.Share).title }).containsExactly(one.title, null).inOrder()
    }

    @Test
    fun `queue items play the target's songs and confirm the quiet ones`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.queue(albumTarget, MenuAction.PLAY)
        vm.queue(albumTarget, MenuAction.SHUFFLE)
        vm.queue(MenuTarget.Song(other), MenuAction.PLAY_NOW)
        vm.queue(MenuTarget.Song(other), MenuAction.PLAY_NEXT)
        vm.queue(albumTarget, MenuAction.PLAY_LAST)
        assertThat(playback.calls).containsExactly("play:1,2", "shuffle:1,2", "now:3", "next:3", "last:1,2").inOrder()
        assertThat(events).containsExactly(MenuEvent.Queued(QueueAction.PLAY_NEXT), MenuEvent.Queued(QueueAction.ADD_TO_QUEUE)).inOrder()
    }

    @Test
    fun `export hands the album's song URIs to the CDJ export under its name`() = runTest {
        val vm = viewModel()
        vm.exportToRekordbox(albumTarget, "Album 7")
        assertThat(exported).containsExactly("Album 7" to listOf(one.uri, two.uri))
    }

    @Test
    fun `the selection bar queues and exports the picked songs as given`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.queueTracks(listOf(two, one), MenuAction.PLAY_NEXT)
        vm.queueTracks(listOf(other), MenuAction.PLAY_LAST)
        vm.exportTracks("Picks", listOf(two, other))
        assertThat(playback.calls).containsExactly("next:2,1", "last:3").inOrder()
        assertThat(exported).containsExactly("Picks" to listOf(two.uri, other.uri))
        assertThat(events).containsExactly(
            MenuEvent.Queued(QueueAction.PLAY_NEXT), MenuEvent.Queued(QueueAction.ADD_TO_QUEUE), MenuEvent.ExportQueued("Picks"),
        ).inOrder()
    }

    @Test
    fun `the selection bar exports nothing without songs or without the CDJ export`() = runTest {
        val events = mutableListOf<MenuEvent>()
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.exportTracks("Picks", emptyList())
        vm.queueTracks(emptyList(), MenuAction.PLAY_NEXT)
        viewModel(export = false).exportTracks("Picks", listOf(one))

        assertThat(exported).isEmpty()
        assertThat(playback.calls).isEmpty()
        assertThat(events).isEmpty()
    }

    @Test
    fun `without the CDJ export the item does nothing`() = runTest {
        val vm = viewModel(export = false)
        assertThat(vm.canExport).isFalse()
        vm.exportToRekordbox(albumTarget, "Album 7")
        assertThat(exported).isEmpty()
    }

    @Test
    fun `Remove from Sonora hides the item, keeps the files and says how to restore it`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.remove(albumTarget)
        assertThat(vm.removeStep.value).isEqualTo(RemoveStep.Choose(albumTarget))
        vm.hide("Album 7", "Artist 1", nowMs = 42)
        assertThat(vm.removeStep.value).isNull()
        assertThat(marks.hidden.value).containsExactly(HiddenItem(ItemRef.album(7), "Album 7", "Artist 1", 42))
        assertThat(files.deleted).isEmpty()
        assertThat(events).containsExactly(MenuEvent.Hidden("Album 7"))
    }

    @Test
    fun `Delete from Phone asks for the irreversible confirmation before deleting`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.remove(albumTarget)
        vm.chooseDelete()
        assertThat(vm.removeStep.value).isEqualTo(RemoveStep.ConfirmDelete(albumTarget, listOf(one, two)))
        assertThat(files.deleted).isEmpty()

        vm.confirmDelete()
        assertThat(files.deleted).containsExactly(one.uri, two.uri)
        assertThat(vm.removeStep.value).isNull()
        assertThat(events).containsExactly(MenuEvent.Deleted(2))
    }

    @Test
    fun `cancelling at any step deletes and hides nothing`() = runTest {
        val vm = viewModel()
        vm.remove(albumTarget)
        vm.cancelRemove()
        vm.confirmDelete()
        vm.hide("Album 7", "", 1)
        vm.remove(albumTarget)
        vm.chooseDelete()
        vm.cancelRemove()
        vm.confirmDelete()
        assertThat(files.deleted).isEmpty()
        assertThat(marks.hidden.value).isEmpty()
    }

    @Test
    fun `a read-only folder asks to pick it again, then deletes`() = runTest {
        val vm = viewModel()
        files.readOnly += two.uri
        vm.remove(albumTarget)
        vm.chooseDelete()
        vm.confirmDelete()
        assertThat(vm.removeStep.value).isEqualTo(RemoveStep.NeedsAccess(albumTarget, listOf(one, two), REQUEST))
        assertThat(files.deleted).isEmpty()

        vm.accessPicked("content://tree/music")
        assertThat(files.deleted).containsExactly(one.uri, two.uri)
        assertThat(vm.removeStep.value).isNull()
    }

    @Test
    fun `picking another folder asks again, a refusing storage gives up`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        files.readOnly += one.uri
        vm.remove(MenuTarget.Song(one))
        vm.chooseDelete()
        vm.confirmDelete()

        files.grant = WriteGrant.WRONG_FOLDER
        vm.accessPicked("content://tree/other")
        assertThat((vm.removeStep.value as RemoveStep.NeedsAccess).wrongFolder).isTrue()

        files.grant = WriteGrant.REFUSED
        vm.accessPicked("content://tree/music")
        assertThat(vm.removeStep.value).isNull()
        assertThat(files.deleted).isEmpty()
        assertThat(events).containsExactly(MenuEvent.StorageRefused)
    }

    @Test
    fun `cancelling while the songs are read keeps the confirmation closed`() = runTest {
        val folders = SlowFolders(listOf(one, two))
        val vm = viewModel(folders = folders)
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        val target = MenuTarget.FolderItem(FolderEntry(FolderRef("content://tree/music", "Music"), trackCount = 2))
        vm.remove(target)
        vm.chooseDelete()
        assertThat(vm.removeStep.value).isEqualTo(RemoveStep.Deleting(target))

        vm.cancelRemove()
        folders.gate.complete(Unit)

        assertThat(vm.removeStep.value).isNull()
        vm.confirmDelete()
        assertThat(files.deleted).isEmpty()
        assertThat(events).isEmpty()
    }

    @Test
    fun `picking the folder twice deletes once`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        files.readOnly += two.uri
        vm.remove(albumTarget)
        vm.chooseDelete()
        vm.confirmDelete()
        val gate = CompletableDeferred<Unit>().also { files.grantGate = it }

        vm.accessPicked("content://tree/music")
        vm.accessPicked("content://tree/music")
        gate.complete(Unit)

        assertThat(files.deleted).containsExactly(one.uri, two.uri)
        assertThat(events).containsExactly(MenuEvent.Deleted(2))
        assertThat(vm.removeStep.value).isNull()
    }

    @Test
    fun `cancelling while access is being kept deletes nothing`() = runTest {
        val vm = viewModel()
        files.readOnly += two.uri
        vm.remove(albumTarget)
        vm.chooseDelete()
        vm.confirmDelete()
        val gate = CompletableDeferred<Unit>().also { files.grantGate = it }

        vm.accessPicked("content://tree/music")
        vm.cancelRemove()
        gate.complete(Unit)

        assertThat(files.deleted).isEmpty()
        assertThat(vm.removeStep.value).isNull()
    }

    @Test
    fun `deleted songs leave playlists and lose their marks`() = runTest {
        val vm = viewModel()
        val id = playlists.create("Mix", "", listOf(PlaylistEntry.of(one), PlaylistEntry.of(other)))
        vm.remove(MenuTarget.Song(one))
        vm.chooseDelete()
        vm.confirmDelete()
        assertThat(playlists.entries(id).map { it.trackUri }).containsExactly(other.uri)
        assertThat(marks.forgotten).containsExactly(one.uri)
    }

    @Test
    fun `deleting a playlist deletes only the list and keeps its songs' files`() = runTest {
        val id = playlists.create("Road", "", listOf(PlaylistEntry.of(one), PlaylistEntry.of(two)))
        val items = listOf(one, two).map { PlaylistItem(PlaylistEntry.of(it), it) }
        val target = MenuTarget.PlaylistItem(ResolvedPlaylist(Playlist(id, "Road"), items))
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.remove(target)
        vm.chooseDelete()
        assertThat(playlists.state.value).isEmpty()
        assertThat(files.deleted).isEmpty()
        assertThat(marks.forgotten).isEmpty()
        assertThat(vm.removeStep.value).isNull()
        assertThat(events).containsExactly(MenuEvent.PlaylistDeleted("Road"))
    }

    // Send to Watch

    private val emptyReport = SendReport(emptyList(), emptyList(), emptyList(), attempts = 1)
    private val mixTarget = MenuTarget.PlaylistItem(ResolvedPlaylist(Playlist(3, "Mix"), emptyList()))

    @Test
    fun `sending an album or a playlist to the watch hands the controller its id and says it started`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.sendToWatch(albumTarget)
        watch.sync.value = WatchSyncState.Idle
        vm.sendToWatch(mixTarget)
        assertThat(watch.calls).containsExactly("album:7", "playlist:3").inOrder()
        assertThat(events).containsExactly(MenuEvent.WatchSendStarted("Album 7"), MenuEvent.WatchSendStarted("Mix")).inOrder()
    }

    @Test
    fun `the playlist page sends its playlist by id`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.sendPlaylistToWatch(playlistId = 9, name = "Road")
        assertThat(watch.calls).containsExactly("playlist:9")
        assertThat(events).containsExactly(MenuEvent.WatchSendStarted("Road"))
    }

    @Test
    fun `a send already running is not started again and the user is told`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        watch.accepts = false
        vm.sendToWatch(albumTarget)
        assertThat(events).containsExactly(MenuEvent.WatchBusy)
    }

    @Test
    fun `when the send ends the user is told how it went`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.sendToWatch(albumTarget)
        val finished = WatchSyncState.Finished(emptyReport)
        watch.sync.value = finished
        assertThat(events).containsExactly(MenuEvent.WatchSendStarted("Album 7"), MenuEvent.WatchSendEnded(finished)).inOrder()
    }

    @Test
    fun `a watch that cannot be found is reported as soon as the send fails`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.sendToWatch(albumTarget)
        val failed = WatchSyncState.Failed(WatchSyncFailure.NoWatch)
        watch.sync.value = failed
        assertThat(events.last()).isEqualTo(MenuEvent.WatchSendEnded(failed))
    }

    @Test
    fun `a result left over from an earlier send is not reported as this send's end`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        val stale = WatchSyncState.Finished(emptyReport)
        watch.sync.value = stale
        watch.startsLate = true
        vm.sendToWatch(albumTarget)
        assertThat(events).containsExactly(MenuEvent.WatchSendStarted("Album 7"))

        watch.sync.value = WatchSyncState.Running(progress = null, songCount = 2)
        assertThat(events).containsExactly(MenuEvent.WatchSendStarted("Album 7"))

        val failed = WatchSyncState.Failed(WatchSyncFailure.ConnectionLost)
        watch.sync.value = failed
        assertThat(events).containsExactly(MenuEvent.WatchSendStarted("Album 7"), MenuEvent.WatchSendEnded(failed)).inOrder()
    }

    @Test
    fun `a send whose Running arrives late is still reported when it ends`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        watch.startsLate = true
        vm.sendToWatch(albumTarget)
        watch.sync.value = WatchSyncState.Running(progress = null, songCount = 1)
        val finished = WatchSyncState.Finished(emptyReport)
        watch.sync.value = finished
        assertThat(events).containsExactly(MenuEvent.WatchSendStarted("Album 7"), MenuEvent.WatchSendEnded(finished)).inOrder()
    }

    @Test
    fun `a cancelled send says nothing more`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.sendToWatch(albumTarget)
        watch.cancel()
        assertThat(events).containsExactly(MenuEvent.WatchSendStarted("Album 7"))
    }

    @Test
    fun `songs, artists and folders are not sent to the watch`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<MenuEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.sendToWatch(MenuTarget.Song(one))
        vm.sendToWatch(MenuTarget.ArtistItem(1, "Artist 1"))
        assertThat(watch.calls).isEmpty()
        assertThat(events).isEmpty()
    }

    private companion object {
        val REQUEST = WriteAccessRequest("content://tree/music", "Music", "content://tree/music/document/root")
    }
}
